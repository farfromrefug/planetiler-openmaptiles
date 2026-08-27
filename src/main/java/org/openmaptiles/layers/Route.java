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
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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

    class RouteRelationData {
        Double computedDistance = 0.0;
        Envelope envelope = new Envelope();
        long id;

        RouteRelationData(
        ) {}
    }
    /*
     * Generates the shape for roads, trails, ferries, railways with detailed
     * attributes for rendering, but not any names.  The transportation_name
     * layer includes names, but less detailed attributes.
     */

    private static final Logger LOGGER = LoggerFactory.getLogger(Route.class);

    private final Stats stats;
    private final PlanetilerConfig config;
    private final HashMap<Long, RouteRelationData> routeRelationDatas = new HashMap<>();

    /** Drop the per-tile "extent" bbox string. It is relation-global, so every tile a route crosses
     * repeats it. Recoverable from the relation metadata or from the geometry itself. */
    private final boolean dropExtent;
    /** Keep only osmid/class/network on tile features and leave the rest of the relation metadata
     * (name, ref, symbol, ascent, descent, distance, extent) to a side lookup keyed by osmid. */
    private final boolean slimAttrs;
    /** Actually pass the computed minLength to mergeLineStrings instead of the hardcoded 0. */
    private final boolean useMinLength;
    /** Halve the merge tolerance so route geometry is simplified exactly like the transportation
     * layer - otherwise a route drawn over its own track visibly diverges as you zoom. */
    private final boolean roadTolerance;
    /** Decimal places kept in the "extent" bbox string. 3 is ~110m, far finer than a bbox needs. */
    private final int extentDigits;
    /** Replace the osmc:symbol string with a dense integer id, and write the id -> string table to
     * a sidecar json so it can be stored once in the archive instead of once per tile. */
    private final boolean symbolIds;
    private final Path symbolTablePath;
    private final ConcurrentHashMap<String, Integer> symbolRegistry = new ConcurrentHashMap<>();
    private final AtomicInteger nextSymbolId = new AtomicInteger();

    public Route(Translations translations, PlanetilerConfig config, Stats stats) {
        this.config = config;
        this.stats = stats;
        var arguments = config.arguments();
        this.slimAttrs = arguments.getBoolean("route_slim_attrs",
            "route layer: emit only osmid/class/network per tile, relation metadata goes in a side lookup", false);
        this.dropExtent = slimAttrs || arguments.getBoolean("route_drop_extent",
            "route layer: do not emit the per-tile relation bbox 'extent' attribute", false);
        this.useMinLength = arguments.getBoolean("route_min_length",
            "route layer: cull sub-minLength merged segments instead of keeping everything", false);
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
     * Stable dense id per distinct symbol string.
     * <p>
     * The counter is an AtomicInteger rather than symbolRegistry.size(). This runs on the OSM
     * processing pool, and reading the map's own size() inside computeIfAbsent lets two threads
     * inserting into different bins both observe the same size and hand out the same id.
     */
    private int symbolId(String symbol) {
        return symbolRegistry.computeIfAbsent(symbol, s -> nextSymbolId.incrementAndGet());
    }

    @Override
    public void finish(String sourceName, FeatureCollector.Factory featureCollectors,
        Consumer<FeatureCollector.Feature> next) {
        if (!symbolIds || !"osm".equals(sourceName) || symbolRegistry.isEmpty()) {
            return;
        }
        var byId = new TreeMap<Integer, String>();
        symbolRegistry.forEach((symbol, id) -> byId.put(id, symbol));
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
            return List.of(new RouteRelation(
                type,
                name,
                relation.getString("route"),
                ref,
                networkType,
                Parse.meters(relation.getString("ascent")),
                Parse.meters(relation.getString("descent")),
                Parse.meters(relation.getString("distance")),
                relation.getString("osmc:symbol"),
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
                RouteRelationData routeRelationData;
                if (!routeRelationDatas.containsKey(relId)) {
                    routeRelationData = new RouteRelationData();
                    routeRelationDatas.put(relId, routeRelationData);
                } else {
                    routeRelationData = routeRelationDatas.get(relId);

                }
                try {
                    if (relation.distance == null) {
                        routeRelationData.computedDistance += feature.length() * 40075 / 2.0;
                    }
                    routeRelationData.envelope.expandToInclude(feature.worldGeometry().getEnvelopeInternal());
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
                var line = features.line(LAYER_NAME)
                    .setBufferPixels(BUFFER_SIZE)
                    .setAttr("osmid", relId)
                    .setAttr("network", networkType)
                    .setAttr(Fields.CLASS, clazz)
                    .setMinZoom(minzoom)
                    .setSortKey(feature.getWayZorder())
                    .setMinPixelSize(0);
                if (!slimAttrs) {
                    line
                        .setAttr("ref", relation.ref())
                        .setAttr("ascent",
                            relation.ascent() != null ? nullIfLong(Math.round(relation.ascent()), 0) : null)
                        .setAttr("descent",
                            relation.descent() != null ? nullIfLong(Math.round(relation.descent()), 0) : null)
                        .setAttr("distance",
                            relation.distance() != null ? nullIfLong(Math.round(relation.distance()), 0) : null)
                        .setAttr("symbol", symbol == null ? null : symbolIds ? symbolId(symbol) : symbol)
                        .setAttr("name", name);
                }
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
        //     // .setPixelToleranceFactor(0.8)
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

        if (!slimAttrs || !dropExtent) {
            for (int i = 0; i < items.size(); i++) {
                var attrs = items.get(i).attrs();
                Long relId = (Long) attrs.get("osmid");
                var routeRelationData = routeRelationDatas.get(relId);
                if (routeRelationData != null) {
                    if (!dropExtent) {
                        var latLngBounds = GeoUtils.toLatLonBoundsBounds(routeRelationData.envelope);
                        DecimalFormat df =
                            new DecimalFormat(extentDigits <= 0 ? "#" : "#." + "0".repeat(extentDigits));
                        String extent = df.format(latLngBounds.getMinX()) + "," +
                            df.format(latLngBounds.getMinY()) + "," + df.format(latLngBounds.getMaxX()) +
                            "," + df.format(latLngBounds.getMaxY());
                        attrs.put("extent", extent);
                    }
                    if (!slimAttrs && attrs.get("distance") == null) {
                        attrs.put("distance", nullIfLong(Math.round(routeRelationData.computedDistance), 0));
                    }
                }
            }
        }
        double minLength = config.minFeatureSize(zoom) / 2.0;
        // Transportation.postProcess uses config.tolerance(zoom) * 0.5 - match it so a route and the
        // track it follows stay on top of each other at every zoom
        double tolerance = roadTolerance ? config.tolerance(zoom) * 0.5 : config.tolerance(zoom);
        // double tolerance = zoom== 7 ? 100 : config.tolerance(zoom);
        // if (zoom==6) {
        //     tolerance = 2000;
        //     LOGGER.warn("route merging " + zoom + " " + tolerance + " " + items.size());

        // }
        double lengthLimit = useMinLength && zoom < config.maxzoom() ? minLength : 0.0;
        items = FeatureMerge.mergeLineStrings(items, attrs -> lengthLimit, tolerance, BUFFER_SIZE);
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
