package org.openmaptiles.layers;

import static com.onthegomap.planetiler.TestUtils.newPoint;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.geo.GeometryType;
import com.onthegomap.planetiler.stats.Stats;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.reader.SimpleFeature;
import com.onthegomap.planetiler.reader.SourceFeature;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openmaptiles.OpenMapTilesProfile;

class PoiTest extends AbstractLayerTest {

  private SourceFeature feature(boolean area, Map<String, Object> tags) {
    return area ? polygonFeature(tags) : pointFeature(tags);
  }

  @Test
  void testFenwayPark() {
    assertFeatures(7, List.of(Map.of(
      "_layer", "poi",
      "class", "stadium",
      "subclass", "stadium",
      "name", "Fenway Park",
      "rank", "<null>",
      "_minzoom", 14,
      "_labelgrid_size", 64d
    )), process(pointFeature(Map.of(
      "leisure", "stadium",
      "name", "Fenway Park"
    ))));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testFunicularHalt(boolean area) {
    assertFeatures(7, List.of(Map.of(
      "_layer", "poi",
      "class", "railway",
      "subclass", "halt",
      "rank", "<null>",
      "_minzoom", 12
    )), process(feature(area, Map.of(
      "railway", "station",
      "funicular", "yes",
      "name", "station"
    ))));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testSubway(boolean area) {
    assertFeatures(7, List.of(Map.of(
      "_layer", "poi",
      "class", "railway",
      "subclass", "subway",
      "rank", "<null>",
      "_minzoom", 12
    )), process(feature(area, Map.of(
      "railway", "station",
      "station", "subway",
      "name", "station"
    ))));
  }

  private List<FeatureCollector.Feature> testAggStops(List<SourceFeature> sourceFeatures) {
    sourceFeatures.forEach(this::process);

    List<FeatureCollector.Feature> features = new ArrayList<>();
    profile.finish(OpenMapTilesProfile.OSM_SOURCE, featureCollectorFactory, features::add);

    return features;
  }

  @Test
  void testAggStopJustOne() {
    var result = testAggStops(List.of(pointFeature(Map.of(
      "highway", "bus_stop",
      "name", "station",
      "uic_ref", "1"
    ))));
    assertFeatures(14, List.of(Map.of(
      "_layer", "poi",
      "class", "bus",
      "subclass", "bus_stop",
      "agg_stop", 1,
      "_minzoom", 14
    )), result);
  }

  @Test
  void testAggStopTwoWithSameSubclass() {
    var result = testAggStops(List.of(
      pointFeature(Map.of(
        "railway", "tram_stop",
        "name", "station",
        "name:es", "test 1",
        "uic_ref", "1"
      )),
      pointFeature(Map.of(
        "railway", "tram_stop",
        "name", "station",
        "name:es", "test 2",
        "uic_ref", "1"
      ))
    ));
    assertFeatures(14, List.of(
      Map.of(
        "_layer", "poi",
        "name:es", "test 1",
        "class", "railway",
        "subclass", "tram_stop",
        "agg_stop", 1,
        "_minzoom", 14
      ),
      Map.of(
        "_layer", "poi",
        "name:es", "test 2",
        "class", "railway",
        "subclass", "tram_stop",
        "agg_stop", "<null>",
        "_minzoom", 14
      )
    ), result);
  }

  @Test
  void testAggStopThreeWithMixedSubclass() {
    var result = testAggStops(List.of(
      pointFeature(Map.of(
        "highway", "bus_stop",
        "name", "station",
        "name:es", "test 1",
        "uic_ref", "1"
      )),
      pointFeature(Map.of(
        "highway", "bus_stop",
        "name", "station",
        "name:es", "test 2",
        "uic_ref", "1"
      )),
      pointFeature(Map.of(
        "railway", "tram_stop",
        "name", "station",
        "name:es", "test 3",
        "uic_ref", "1"
      ))
    ));
    assertFeatures(14, List.of(
      Map.of(
        "_layer", "poi",
        "name:es", "test 1",
        "class", "bus",
        "subclass", "bus_stop",
        "agg_stop", "<null>",
        "_minzoom", 14
      ),
      Map.of(
        "_layer", "poi",
        "name:es", "test 2",
        "class", "bus",
        "subclass", "bus_stop",
        "agg_stop", "<null>",
        "_minzoom", 14
      ),
      Map.of(
        "_layer", "poi",
        "name:es", "test 3",
        "class", "railway",
        "subclass", "tram_stop",
        "agg_stop", 1,
        "_minzoom", 14
      )
    ), result);
  }

  @Test
  void testAggStopThreeWithSameSubclass() {
    var result = testAggStops(List.of(
      SimpleFeature.create(newPoint(0, 0), Map.of(
        "highway", "bus_stop",
        "name", "station",
        "name:es", "test 1",
        "uic_ref", "1"
      ), OpenMapTilesProfile.OSM_SOURCE, null, 0),
      SimpleFeature.create(newPoint(1, 0), Map.of(
        "highway", "bus_stop",
        "name", "station",
        "name:es", "test 2",
        "uic_ref", "1"
      ), OpenMapTilesProfile.OSM_SOURCE, null, 1),
      SimpleFeature.create(newPoint(2, 0), Map.of(
        "highway", "bus_stop",
        "name", "station",
        "name:es", "test 3",
        "uic_ref", "1"
      ), OpenMapTilesProfile.OSM_SOURCE, null, 2)
    ));
    assertFeatures(14, List.of(
      Map.of(
        "_layer", "poi",
        "name:es", "test 1",
        "class", "bus",
        "subclass", "bus_stop",
        "agg_stop", "<null>",
        "_minzoom", 14
      ),
      Map.of(
        "_layer", "poi",
        "name:es", "test 2",
        "class", "bus",
        "subclass", "bus_stop",
        "agg_stop", 1,
        "_minzoom", 14
      ),
      Map.of(
        "_layer", "poi",
        "name:es", "test 3",
        "class", "bus",
        "subclass", "bus_stop",
        "agg_stop", "<null>",
        "_minzoom", 14
      )
    ), result);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testPlaceOfWorshipFromReligionTag(boolean area) {
    assertFeatures(7, List.of(Map.of(
      "_layer", "poi",
      "class", "place_of_worship",
      "subclass", "religion value",
      "rank", "<null>",
      "_minzoom", 14
    )), process(feature(area, Map.of(
      "amenity", "place_of_worship",
      "religion", "religion value",
      "name", "station"
    ))));
  }

  @Test
  void testPitchFromSportTag() {
    assertFeatures(7, List.of(Map.of(
      "_layer", "poi",
      "class", "pitch",
      "subclass", "soccer",
      "rank", "<null>"
    )), process(pointFeature(Map.of(
      "leisure", "pitch",
      "sport", "soccer",
      "name", "station"
    ))));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testInformation(boolean area) {
    assertFeatures(7, List.of(Map.of(
      "_layer", "poi",
      "class", "information",
      "subclass", "infotype",
      "layer", 3L,
      "level", 2L,
      "indoor", 1,
      "rank", "<null>"
    )), process(feature(area, Map.of(
      "tourism", "information",
      "information", "infotype",
      "name", "station",
      "layer", "3",
      "level", "2",
      "indoor", "yes"
    ))));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testFerryTerminal(boolean area) {
    assertFeatures(7, List.of(Map.of(
      "_layer", "poi",
      "class", "ferry_terminal",
      "subclass", "ferry_terminal",
      "name", "Water Taxi",
      "_minzoom", 12
    )), process(feature(area, Map.of(
      "amenity", "ferry_terminal",
      "information", "infotype",
      "name", "Water Taxi",
      "layer", "3",
      "level", "2",
      "indoor", "yes"
    ))));
  }

  @Test
  void testGridRank() throws GeometryException {
    var layerName = Poi.LAYER_NAME;
    Assertions.assertEquals(List.of(), profile.postProcessLayerFeatures(layerName, 13, List.of()));

    Assertions.assertEquals(List.of(pointFeature(
      layerName,
      Map.of("rank", 1),
      1
    )), profile.postProcessLayerFeatures(layerName, 14, List.of(pointFeature(
      layerName,
      Map.of(),
      1
    ))));

    Assertions.assertEquals(List.of(
      pointFeature(
        layerName,
        Map.of("rank", 1, "name", "a"),
        1
      ), pointFeature(
        layerName,
        Map.of("rank", 2, "name", "b"),
        1
      ), pointFeature(
        layerName,
        Map.of("rank", 1, "name", "c"),
        2
      )
    ), profile.postProcessLayerFeatures(layerName, 14, List.of(
      pointFeature(
        layerName,
        Map.of("name", "a"),
        1
      ),
      pointFeature(
        layerName,
        Map.of("name", "b"),
        1
      ),
      pointFeature(
        layerName,
        Map.of("name", "c"),
        2
      )
    )));
  }

  @Test
  void testEmbassy() {
    assertFeatures(7, List.of(Map.of(
      "_layer", "poi",
      "class", "office",
      "subclass", "diplomatic",
      "name", "The Embassy"
    )), process(pointFeature(Map.of(
      "office", "diplomatic",
      "name", "The Embassy"
    ))));
  }

  @Test
  void testLocksmith() {
    assertFeatures(7, List.of(Map.of(
      "_layer", "poi",
      "class", "shop",
      "subclass", "locksmith",
      "name", "The Locksmith"
    )), process(pointFeature(Map.of(
      "shop", "locksmith",
      "name", "The Locksmith"
    ))));
  }

  @Test
  void testAtm() {
    List<Map<String, Object>> expected = List.of(Map.of(
      "_layer", "poi",
      "class", "atm",
      "subclass", "atm",
      "name", "ATM name"
    ));
    // prefer name, otherwise fall back to operator, or else network
    assertFeatures(14, expected, process(pointFeature(Map.of(
      "amenity", "atm",
      "name", "ATM name"
    ))));
    assertFeatures(14, expected, process(pointFeature(Map.of(
      "amenity", "atm",
      "name", "ATM name",
      "operator", "ATM operator",
      "network", "ATM network"
    ))));
    assertFeatures(14, expected, process(pointFeature(Map.of(
      "amenity", "atm",
      "operator", "ATM name",
      "network", "ATM network"
    ))));
    assertFeatures(14, expected, process(pointFeature(Map.of(
      "amenity", "atm",
      "network", "ATM name"
    ))));
  }

  @Test
  void testParcelLocker() {
    List<Map<String, Object>> expected = List.of(Map.of(
      "_layer", "poi",
      "class", "post",
      "subclass", "parcel_locker",
      "name", "Parcel Locker name"
    ));
    assertFeatures(14, expected, process(pointFeature(Map.of(
      "amenity", "parcel_locker",
      "brand", "Parcel Locker name"
    ))));
    assertFeatures(14, expected, process(pointFeature(Map.of(
      "amenity", "parcel_locker",
      "operator", "Parcel Locker name"
    ))));
    assertFeatures(14, expected, process(pointFeature(Map.of(
      "amenity", "parcel_locker",
      "operator", "Parcel Locker",
      "ref", "name"
    ))));
  }

  @Test
  void testParcelLockerCornerCase() {
    List<Map<String, Object>> expected = List.of(Map.of(
      "_layer", "poi",
      "class", "post",
      "subclass", "parcel_locker",
      "name", "Corner Case"
    ));
    // no brand, no operator, just ref
    assertFeatures(14, expected, process(pointFeature(Map.of(
      "amenity", "parcel_locker",
      "ref", "Corner Case"
    ))));
  }

  @Test
  void testChargingStation() {
    List<Map<String, Object>> expected = List.of(Map.of(
      "_layer", "poi",
      "class", "fuel",
      "subclass", "charging_station",
      "name", "Some Charging Station Operator"
    ));
    assertFeatures(14, expected, process(pointFeature(Map.of(
      "amenity", "charging_station",
      "brand", "Some Charging Station Operator"
    ))));
    assertFeatures(14, expected, process(pointFeature(Map.of(
      "amenity", "charging_station",
      "operator", "Some Charging Station Operator"
    ))));
    assertFeatures(14, expected, process(pointFeature(Map.of(
      "amenity", "charging_station",
      "operator", "Some Charging Station",
      "ref", "Operator"
    ))));
  }

  private OpenMapTilesProfile treesProfile() {
    return new OpenMapTilesProfile(translations,
      PlanetilerConfig.from(Arguments.of(Map.of("poi_trees", "true"))), Stats.inMemory());
  }

  @Test
  void testTreesOffByDefault() {
    assertFeatures(14, List.of(), process(pointFeature(Map.of("natural", "tree"))));
  }

  @Test
  void testTree() {
    var feature = pointFeature(Map.of("natural", "tree", "leaf_type", "broadleaved", "level", "0"));
    var collector = featureCollectorFactory.get(feature);
    treesProfile().processFeature(feature, collector);
    assertFeatures(14, List.of(Map.of(
      "_layer", "poi",
      "class", "tree",
      "subclass", "<null>",
      "level", "<null>",
      "_minzoom", 14
    )), collector);
  }

  @Test
  void testPacksUnnamedTrees() throws GeometryException {
    var layer = Poi.LAYER_NAME;
    var result = treesProfile().postProcessLayerFeatures(layer, 14, List.of(
      treeAt(1, 1, Map.of("class", "tree")),
      treeAt(1, 1, Map.of("class", "tree")),
      treeAt(2, 3, Map.of("class", "tree")),
      treeAt(4, 4, Map.of("class", "tree", "name", "Chêne de la Lune")),
      treeAt(5, 5, Map.of("class", "toilets"))
    ));
    Assertions.assertEquals(3, result.size(), result::toString);
    var packed = result.stream().filter(f -> f.geometry().geomType() == GeometryType.POINT &&
      f.tags().equals(Map.of("class", "tree"))).toList();
    Assertions.assertEquals(1, packed.size(), result::toString);
    // the two trees at one spot draw as one
    Assertions.assertEquals(2, packed.getFirst().geometry().decode().getNumPoints());
    var named = result.stream().filter(f -> f.tags().containsKey("name")).findFirst().orElseThrow();
    Assertions.assertFalse(named.tags().containsKey("rank"), "a tree has no rank");
    var toilets = result.stream().filter(f -> "toilets".equals(f.tags().get("class"))).findFirst().orElseThrow();
    Assertions.assertEquals(1, toilets.tags().get("rank"));
  }

  @Test
  void testPacksUnnamedBarriers() throws GeometryException {
    var result = profile.postProcessLayerFeatures(Poi.LAYER_NAME, 14, List.of(
      treeAt(1, 1, Map.of("class", "gate")),
      treeAt(2, 2, Map.of("class", "gate")),
      treeAt(3, 3, Map.of("class", "bollard")),
      treeAt(4, 4, Map.of("class", "gate", "name", "Porte de France"))
    ));
    Assertions.assertEquals(3, result.size(), result::toString);
    var gates = result.stream().filter(f -> f.tags().equals(Map.of("class", "gate"))).findFirst().orElseThrow();
    Assertions.assertEquals(2, gates.geometry().decode().getNumPoints());
    Assertions.assertTrue(result.stream().anyMatch(f -> f.tags().equals(Map.of("class", "bollard"))));
  }

  private FeatureCollector processWith(String flag, Map<String, Object> tags) {
    var profile = new OpenMapTilesProfile(translations,
      PlanetilerConfig.from(Arguments.of(Map.of(flag, "true"))), Stats.inMemory());
    var feature = pointFeature(tags);
    var collector = featureCollectorFactory.get(feature);
    profile.processFeature(feature, collector);
    return collector;
  }

  @Test
  void testLandmarksOffByDefault() {
    assertFeatures(14, List.of(), process(pointFeature(Map.of("power", "tower"))));
    assertFeatures(14, List.of(), process(pointFeature(Map.of("historic", "wayside_cross"))));
    assertFeatures(14, List.of(), process(pointFeature(Map.of("tourism", "information", "information", "guidepost"))));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "aerialway, pylon, pylon",
    "historic, wayside_cross, wayside_cross",
    "historic, wayside_shrine, wayside_shrine",
    "man_made, mast, mast",
    "man_made, cross, cross",
    "man_made, cairn, cairn",
    "natural, stone, stone",
    "natural, rock, rock",
  })
  void testLandmark(String key, String value, String clazz) {
    assertFeatures(14, List.of(Map.of(
      "_layer", "poi",
      "class", clazz,
      "subclass", "<null>",
      "name", "Croix du Nivolet",
      "_minzoom", 14
    )), processWith("poi_landmarks", Map.of(key, value, "name", "Croix du Nivolet")));
  }

  @Test
  void testPowerTowerFromZ13() {
    assertFeatures(14, List.of(Map.of("class", "power_tower", "_minzoom", 13)),
      processWith("poi_landmarks", Map.of("power", "tower")));
  }

  @Test
  void testWindTurbineOnlyAmongGenerators() {
    assertFeatures(14, List.of(Map.of("class", "wind_turbine", "_minzoom", 13)),
      processWith("poi_landmarks", Map.of("power", "generator", "generator:source", "wind")));
    assertFeatures(14, List.of(),
      processWith("poi_landmarks", Map.of("power", "generator", "generator:source", "solar")));
  }

  @Test
  void testGuidepostLosesItsName() {
    assertFeatures(14, List.of(Map.of(
      "_layer", "poi",
      "class", "guidepost",
      "name", "<null>",
      "_minzoom", 14
    )), processWith("poi_guideposts",
      Map.of("tourism", "information", "information", "guidepost", "name", "Col de la Charmette")));
    // other information points are untouched by the flag
    assertFeatures(14, List.of(Map.of("class", "information", "subclass", "office")),
      processWith("poi_guideposts", Map.of("tourism", "information", "information", "office")));
  }

  @Test
  void testDefaultRanksAreOpenMapTiles() {
    assertFeatures(14, List.of(Map.of("class", "school", "_sortkey", 85)),
      process(pointFeature(Map.of("amenity", "school", "name", "École Jean Jaurès"))));
    assertFeatures(14, List.of(Map.of("class", "pharmacy", "_sortkey", 1_000)),
      process(pointFeature(Map.of("amenity", "pharmacy", "name", "Pharmacie du Col"))));
  }

  @Test
  void testCustomRanks() {
    var profile = new OpenMapTilesProfile(translations,
      PlanetilerConfig.from(Arguments.of(Map.of("poi_custom_ranks", "true"))), Stats.inMemory());
    for (var c : List.of(
      Map.<String, Object>of("amenity", "school", "name", "École Jean Jaurès", "_sortkey", 130),
      Map.<String, Object>of("amenity", "pharmacy", "name", "Pharmacie du Col", "_sortkey", 101)
    )) {
      var tags = new HashMap<String, Object>(c);
      int sortKey = (int) tags.remove("_sortkey");
      var feature = pointFeature(tags);
      var collector = featureCollectorFactory.get(feature);
      profile.processFeature(feature, collector);
      assertFeatures(14, List.of(Map.of("_sortkey", sortKey)), collector);
    }
  }

  private VectorTile.Feature treeAt(double x, double y, Map<String, Object> tags) {
    return new VectorTile.Feature(Poi.LAYER_NAME, 1, VectorTile.encodeGeometry(newPoint(x, y)), new HashMap<>(tags),
      1);
  }
}
