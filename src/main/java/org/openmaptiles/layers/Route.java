/*
Copyright (c) 2021, MapTiler.com & OpenMapTiles contributors.
All rights reserved.

Code license: BSD 3-Clause License

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of the copyright holder nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Design license: CC-BY 4.0

See https://github.com/openmaptiles/openmaptiles/blob/master/LICENSE.md for details on usage
*/
package org.openmaptiles.layers;

import static org.openmaptiles.util.Utils.coalesce;
import static org.openmaptiles.util.Utils.nullIfEmpty;
import static org.openmaptiles.util.Utils.nullIfLong;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.FeatureMerge;
import com.onthegomap.planetiler.ForwardingProfile;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.geo.GeoUtils;
import com.onthegomap.planetiler.geo.GeometryException;
import org.openmaptiles.OpenMapTilesProfile;
import org.openmaptiles.generated.OpenMapTilesSchema;
import org.openmaptiles.generated.Tables;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.reader.osm.OsmElement;
import com.onthegomap.planetiler.reader.osm.OsmReader;
import com.onthegomap.planetiler.reader.osm.OsmRelationInfo;
import com.onthegomap.planetiler.stats.Stats;
import com.onthegomap.planetiler.util.Parse;
import com.onthegomap.planetiler.util.Translations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.function.Consumer;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Route implements
    OpenMapTilesSchema.Route,
    Tables.OsmRouteLinestring.Handler,
    OpenMapTilesProfile.FeaturePostProcessor,
    OpenMapTilesProfile.OsmRelationPreprocessor,
    OpenMapTilesProfile.OsmAllProcessor,
    ForwardingProfile.FinishHandler {

    /**
     * Per-relation totals accumulated across every way of a route, from the multi-threaded OSM
     * processing pool.
     * <p>
     * Both fields are written concurrently, so both have to be thread-safe, and both have to be
     * order-independent or the result changes run to run. The distance is therefore accumulated as a
     * long in millimetres rather than a double: integer addition is associative, floating-point
     * addition is not. The envelope is tracked as four min/max accumulators, which are commutative.
     */
    static class RouteRelationData {
        /** Route length in millimetres; see computedDistanceMeters(). */
        final AtomicLong computedDistanceMm = new AtomicLong();
        private final DoubleAccumulator minX = new DoubleAccumulator(Math::min, Double.POSITIVE_INFINITY);
        private final DoubleAccumulator minY = new DoubleAccumulator(Math::min, Double.POSITIVE_INFINITY);
        private final DoubleAccumulator maxX = new DoubleAccumulator(Math::max, Double.NEGATIVE_INFINITY);
        private final DoubleAccumulator maxY = new DoubleAccumulator(Math::max, Double.NEGATIVE_INFINITY);

        void expandToInclude(Envelope e) {
            minX.accumulate(e.getMinX());
            minY.accumulate(e.getMinY());
            maxX.accumulate(e.getMaxX());
            maxY.accumulate(e.getMaxY());
        }

        boolean hasEnvelope() {
            return minX.get() <= maxX.get();
        }

        Envelope envelope() {
            return new Envelope(minX.get(), maxX.get(), minY.get(), maxY.get());
        }

        double computedDistanceMeters() {
            return computedDistanceMm.get() / 1000.0;
        }
    }
    /*
     * Generates the shape for roads, trails, ferries, railways with detailed
     * attributes for rendering, but not any names.  The transportation_name
     * layer includes names, but less detailed attributes.
     */

    private static final Logger LOGGER = LoggerFactory.getLogger(Route.class);

    private final Stats stats;
    private final PlanetilerConfig config;
    /**
     * Concurrent because processAllOsm runs on the OSM processing pool. The previous plain HashMap
     * with a containsKey/put check-then-act lost updates under contention, which is why route
     * distances came out short and varied between runs.
     */
    private final ConcurrentHashMap<Long, RouteRelationData> routeRelationDatas = new ConcurrentHashMap<>();

    /** Halve the merge tolerance so route geometry is simplified exactly like the transportation
     * layer - otherwise a route drawn over its own track visibly diverges as you zoom. */
    private final boolean roadTolerance;
    /** Decimal places kept in the "extent" bbox string. 3 is ~110m, far finer than a bbox needs. */
    private final int extentDigits;
    /** Replace the osmc:symbol string with a dense integer id, and write the id -> string table to
     * a sidecar json so it can be stored once in the archive instead of once per tile. */
    private final boolean symbolIds;
    private final Path symbolTablePath;
    /**
     * Every osmc:symbol seen in OSM pass 1, which visits the same route relations pass 2 later emits
     * features for. Collecting here rather than numbering on first use is what makes the ids
     * reproducible: handing them out as features arrive makes the mapping depend on which processing
     * thread got there first, so two runs of identical code disagree.
     */
    private final Set<String> symbolsSeen = ConcurrentHashMap.newKeySet();
    /** symbol -> id, assigned once from the sorted contents of {@link #symbolsSeen}. */
    private volatile Map<String, Integer> symbolIdsBySymbol;
    /** Anything asked for that pass 1 never registered. Should stay empty; see {@link #symbolAttr}. */
    private final ConcurrentHashMap<String, Integer> symbolOverflow = new ConcurrentHashMap<>();
    private final AtomicInteger nextOverflowId = new AtomicInteger();

    public Route(Translations translations, PlanetilerConfig config, Stats stats) {
        this.config = config;
        this.stats = stats;
        var arguments = config.arguments();
        this.roadTolerance = arguments.getBoolean("route_road_tolerance",
            "route layer: use the same merge tolerance as the transportation layer", false);
        this.extentDigits = arguments.getInteger("route_extent_digits",
            "route layer: decimal places in the 'extent' bbox string", 3);
        this.symbolIds = arguments.getBoolean("route_symbol_id",
            "route layer: emit osmc:symbol as an integer id plus a sidecar lookup table", false);
        this.symbolTablePath = Path.of(arguments.getString("route_symbol_table",
            "route layer: where to write the symbol id table when route_symbol_id is set",
            "route_symbols.json"));
    }

    /**
     * Numbers every symbol pass 1 registered, in sorted order, so the same input always produces the
     * same table. Built once, lazily, on the first request from pass 2 - by which point pass 1 has
     * finished and {@link #symbolsSeen} is complete.
     */
    private Map<String, Integer> symbolIdsBySymbol() {
        var map = symbolIdsBySymbol;
        if (map == null) {
            synchronized (this) {
                map = symbolIdsBySymbol;
                if (map == null) {
                    var sorted = new ArrayList<>(symbolsSeen);
                    Collections.sort(sorted);
                    var assigned = HashMap.<String, Integer>newHashMap(sorted.size());
                    for (int i = 0; i < sorted.size(); i++) {
                        assigned.put(sorted.get(i), i + 1);
                    }
                    symbolIdsBySymbol = map = assigned;
                }
            }
        }
        return map;
    }

    /**
     * The value to store in the "symbol" attribute: an integer id when symbolIds is on, otherwise the
     * string itself.
     * <p>
     * A symbol missing from the pass 1 set would mean the two passes disagree about which relations
     * carry symbols. That should not happen, so it is logged; the raw string is emitted rather than
     * dropping the attribute, because a route must never lose content. Note an overflow id makes the
     * run non-reproducible again, which is the point of the warning.
     */
    private Object symbolAttr(String symbol) {
        if (symbol == null) {
            return null;
        }
        if (!symbolIds) {
            return symbol;
        }
        Integer id = symbolIdsBySymbol().get(symbol);
        if (id == null) {
            id = symbolOverflow.computeIfAbsent(symbol, s -> {
                LOGGER.warn("route symbol not registered in pass 1, ids are no longer reproducible: {}", s);
                return symbolIdsBySymbol().size() + nextOverflowId.incrementAndGet();
            });
        }
        return id;
    }

    @Override
    public void finish(String sourceName, FeatureCollector.Factory featureCollectors,
        Consumer<FeatureCollector.Feature> next) {
        if (!symbolIds || !"osm".equals(sourceName) || symbolsSeen.isEmpty()) {
            return;
        }
        var byId = new TreeMap<Integer, String>();
        symbolIdsBySymbol().forEach((symbol, id) -> byId.put(id, symbol));
        symbolOverflow.forEach((symbol, id) -> byId.put(id, symbol));
        var json = new StringBuilder("{");
        for (var entry : byId.entrySet()) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('"').append(entry.getKey()).append("\":")
                .append('"').append(entry.getValue().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        json.append('}');
        try {
            Files.writeString(symbolTablePath, json.toString());
            LOGGER.info("wrote {} route symbols to {}", byId.size(), symbolTablePath);
        } catch (IOException e) {
            LOGGER.warn("could not write route symbol table to {}: {}", symbolTablePath, e.toString());
        }
    }

    private Integer getNetworkType(String network) {
        return switch (coalesce(network, "")) {
            case "iwn", "icn" -> 1;
            case "nwn", "ncn" -> 2;
            case "rwn", "rcn" -> 3;
            default -> 4;
        };
    }

    @Override
    public List<OsmRelationInfo> preprocessOsmRelation(OsmElement.Relation relation) {
        if (relation.hasTag("type", "route", "superroute") && relation.hasTag("route", "bicycle", "hiking", "foot")) {
            String network = relation.getString("network");
            String type = relation.getString("type");
            Integer networkType = getNetworkType(network);
            String name =
                coalesce(nullIfEmpty(relation.getString("name")), nullIfEmpty(relation.getString("alt_name")));
            String ref = coalesce(nullIfEmpty(relation.getString("ref")), nullIfEmpty(relation.getString("osmc:ref")));
            // register here, in pass 1, so ids can be assigned from the complete sorted set later
            String symbol = nullIfEmpty(relation.getString("osmc:symbol"));
            if (symbolIds && symbol != null) {
                symbolsSeen.add(symbol);
            }
            return List.of(new RouteRelation(
                type,
                name,
                relation.getString("route"),
                ref,
                networkType,
                Parse.meters(relation.getString("ascent")),
                Parse.meters(relation.getString("descent")),
                Parse.meters(relation.getString("distance")),
                symbol,
                relation.id()));
        }
        return null;
    }

    @Override
    public void processAllOsm(SourceFeature feature, FeatureCollector features) {
        List<RouteRelation> relations = getRouteRelations(feature);
        if (relations != null && !relations.isEmpty() && feature.canBeLine()) {
            for (var relation : relations) {
                long relId = relation.id();
                RouteRelationData routeRelationData =
                    routeRelationDatas.computeIfAbsent(relId, id -> new RouteRelationData());
                try {
                    if (relation.distance == null) {
                        routeRelationData.computedDistanceMm
                            .addAndGet(Math.round(feature.length() * 40075 / 2.0 * 1000.0));
                    }
                    routeRelationData.expandToInclude(feature.worldGeometry().getEnvelopeInternal());
                } catch (GeometryException e) {
                    e.log(stats, "route_decode", "Unable to get route length for " + feature.id());
                }
                String name = relation.name();
                String clazz = relation.route();
                int networkType = relation.networkType();
                int minzoom = getMinzoom(clazz, networkType, name != null);
                // if (relation.type.equals("superroute")) {
                // if (networkType == 1) {
                //     LOGGER.warn("processAllOsm route: " + name);
                // }
                String symbol = nullIfEmpty(relation.symbol());
                features.line(LAYER_NAME)
                    .setBufferPixels(BUFFER_SIZE)
                    .setAttr("osmid", relId)
                    .setAttr("network", networkType)
                    .setAttr(Fields.CLASS, clazz)
                    .setMinZoom(minzoom)
                    .setSortKey(feature.getWayZorder())
                    .setMinPixelSize(0)
                    .setAttr("ref", relation.ref())
                    .setAttr("ascent",
                        relation.ascent() != null ? nullIfLong(Math.round(relation.ascent()), 0) : null)
                    .setAttr("descent",
                        relation.descent() != null ? nullIfLong(Math.round(relation.descent()), 0) : null)
                    .setAttr("distance",
                        relation.distance() != null ? nullIfLong(Math.round(relation.distance()), 0) : null)
                    // symbolIds swaps the string for an integer id plus a sidecar lookup - the same
                    // information, stored once per archive instead of once per tile
                    .setAttr("symbol", symbolAttr(symbol))
                    .setAttr("name", name);
            }
        }
    }


    List<RouteRelation> getRouteRelations(SourceFeature feature) {
        // String ref = element.ref();
        List<OsmReader.RelationMember<RouteRelation>> relations = feature.relationInfo(RouteRelation.class);
        List<RouteRelation> result = new ArrayList<>(relations.size() + 1);
        for (var relationMember : relations) {
            var relation = relationMember.relation();
            // avoid duplicates - list should be very small and usually only one
            if (!result.contains(relation)) {
                result.add(relation);
            }
        }
        return result;
    }

    RouteRelation getRouteRelation(SourceFeature feature) {
        List<RouteRelation> all = getRouteRelations(feature);
        return all.isEmpty() ? null : all.get(0);
    }

    @Override
    public void process(Tables.OsmRouteLinestring element, FeatureCollector features) {
        // List<RouteRelation> relations = getRouteRelations(element);
        // List<OsmReader.RelationMember<RouteRelation>> routes = feature.relationInfo(RouteRelation.class);
        // if (routes != null && !routes.isEmpty() && feature.canBeLine()) {
        // for (var relation : relations) {
        //     // var relation = route.relation();
        //     // if (relation.type.equals("superroute")) {
        //     //     LOGGER.warn("processAllOsm superroute: " + relation.name + " " + relation.id());
        //     // }
        //     long relId = relation.id();
        //     RouteRelationData routeRelationData;
        //     if (!routeRelationDatas.containsKey(relId)) {
        //         routeRelationData = new RouteRelationData();
        //         routeRelationDatas.put(relId, routeRelationData);
        //     } else {
        //         routeRelationData = routeRelationDatas.get(relId);

        //     }
        //     try {
        //         if (relation.distance == null) {
        //             routeRelationData.computedDistance += element.source().length() * 40075 / 2.0;
        //         }
        //         routeRelationData.envelope.expandToInclude(element.source().envelope());
        //     } catch (GeometryException e) {
        //         e.log(stats, "route_decode", "Unable to get route length for " + element.source().id());
        //     }
        //     String name = relation.name();
        //     int networkType = relation.networkType();
        //     int minzoom = getMinzoom(networkType, name != null);
        //     // if (relation.type.equals("superroute")) {
        //     // if (networkType == 1) {
        //     //     LOGGER.warn("processAllOsm route: " + name);
        //     // }
        //     features.line(LAYER_NAME)
        //         .setBufferPixels(BUFFER_SIZE)
        //         .setAttr("ref", relation.ref())
        //         .setAttr("osmid", relId)
        //         .setAttr("network", networkType)
        //         .setAttr("ascent", relation.ascent() != null ? nullIfLong(Math.round(relation.ascent()), 0) : null)
        //         .setAttr("descent",
        //             relation.descent() != null ? nullIfLong(Math.round(relation.descent()), 0) : null)
        //         .setMinZoom(minzoom)
        //         .setAttr("distance",
        //             relation.distance() != null ? nullIfLong(Math.round(relation.distance()), 0) : null)
        //         .setAttr("symbol", nullIfEmpty(relation.symbol()))
        //         .setAttr(Fields.CLASS, relation.route())
        //         .setAttr("name", name)
        //         .setSortKey(element.zOrder())
        //         .setMinPixelSize(0);
        //     // }
        // }
        // String network = element.source().getString("network");
        // String ref = element.source().getString("ref");
        // String name = coalesce(nullIfEmpty(element.source().getString("name")),
        //     nullIfEmpty(element.source().getString("alt_name")));
        // Integer networkType = getNetworkType(network);
        // int minzoom = getMinzoom(networkType, name != null);
        // LOGGER.warn("process route without relation: " + name + " " + element.source().id());
        // features.line(LAYER_NAME)
        //     .setBufferPixels(BUFFER_SIZE)
        //     .setAttr(Fields.CLASS, element.route())
        //     .setAttr("ref", ref)
        //     .setAttr("name", name)
        //     .setMinZoom(minzoom)
        //     // details only at higher zoom levels so that named rivers can be merged more aggressively
        //     // at lower zoom levels, we'll merge linestrings and limit length/clip afterwards
        //     .setBufferPixelOverrides(MIN_PIXEL_LENGTHS)
        //     .setMinPixelSizeBelowZoom(11, 0);
    }

    int getMinzoom(String clazz, Integer networkType, boolean hasName) {
        return switch (networkType) {
            case 1 -> hasName ? 5 : 6;
            case 2 -> clazz.equals("bicycle") ? 6 : 8;
            case 3 -> 9;
            default -> 10;
        };
    }


    @Override
    public List<VectorTile.Feature> postProcess(int zoom, List<VectorTile.Feature> items) {

        for (int i = 0; i < items.size(); i++) {
            var attrs = items.get(i).attrs();
            Long relId = (Long) attrs.get("osmid");
            var routeRelationData = routeRelationDatas.get(relId);
            if (routeRelationData != null && routeRelationData.hasEnvelope()) {
                var latLngBounds = GeoUtils.toLatLonBoundsBounds(routeRelationData.envelope());
                DecimalFormat df =
                    new DecimalFormat(extentDigits <= 0 ? "#" : "#." + "0".repeat(extentDigits));
                String extent = df.format(latLngBounds.getMinX()) + "," +
                    df.format(latLngBounds.getMinY()) + "," + df.format(latLngBounds.getMaxX()) +
                    "," + df.format(latLngBounds.getMaxY());
                attrs.put("extent", extent);
                if (attrs.get("distance") == null) {
                    attrs.put("distance", nullIfLong(Math.round(routeRelationData.computedDistanceMeters()), 0));
                }
            }
        }
        // Transportation.postProcess uses config.tolerance(zoom) * 0.5 - match it so a route and the
        // track it follows stay on top of each other at every zoom
        double tolerance = roadTolerance ? config.tolerance(zoom) * 0.5 : config.tolerance(zoom);
        // double tolerance = zoom== 7 ? 100 : config.tolerance(zoom);
        // if (zoom==6) {
        //     tolerance = 2000;
        //     LOGGER.warn("route merging " + zoom + " " + tolerance + " " + items.size());

        // }
        // length limit stays 0: merging may join segments, but nothing is ever dropped
        items = FeatureMerge.mergeLineStrings(items, attrs -> 0.0, tolerance, BUFFER_SIZE);
        // if (zoom==6) {
        //     LOGGER.warn("route merging done " + zoom + " " + tolerance + " " + items.size() + " " + items.get(0).attrs().get("name"));

        // }
        return items;
    }

    /** Information extracted from route relations to use when processing ways in that relation. */
    record RouteRelation(
        String type,
        String name,
        String route,
        String ref,
        Integer networkType,
        Double ascent,
        Double descent,
        Double distance,
        String symbol,
        @Override long id
    ) implements OsmRelationInfo {}
}
