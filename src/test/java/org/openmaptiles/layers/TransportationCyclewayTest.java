package org.openmaptiles.layers;

import static com.onthegomap.planetiler.TestUtils.newLineString;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.stats.Stats;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openmaptiles.OpenMapTilesProfile;

/** Tests for the maxspeed fix and the opt-in cycleway_l / cycleway_r / shoulder attributes of the transportation layer. */
class TransportationCyclewayTest extends AbstractLayerTest {

  private FeatureCollector processWith(Map<String, String> options, Map<String, Object> tags) {
    var profile = new OpenMapTilesProfile(translations, PlanetilerConfig.from(Arguments.of(options)),
      Stats.inMemory());
    SourceFeature feature = lineFeature(tags);
    var collector = featureCollectorFactory.get(feature);
    profile.processFeature(feature, collector);
    return collector;
  }

  private static Object or(Object value) {
    return value == null ? "<null>" : value;
  }

  private void assertMaxspeed(String osmValue, Object expected) {
    assertFeatures(13, List.of(Map.of(
      "_layer", "transportation",
      "class", "primary",
      "maxspeed", or(expected)
    )), process(lineFeature(Map.of("highway", "primary", "maxspeed", osmValue))));
  }

  @ParameterizedTest
  @CsvSource(value = {
    "50,50",
    "50 km/h,50",
    "55 mph,89",
    "30mph,48",
    "25 mph,40",
    "50;70,50",
    "80;50 mph,80",
    "none,255",
    "10,10",
    "9,",
    "5,",
    "walk,",
    "signals,",
    "FR:urban,",
    "0,",
  })
  void testMaxspeed(String osmValue, Integer expected) {
    assertMaxspeed(osmValue, expected);
  }

  @Test
  void testMaxspeedAbsent() {
    assertFeatures(13, List.of(Map.of(
      "_layer", "transportation",
      "maxspeed", "<null>"
    )), process(lineFeature(Map.of("highway", "primary"))));
  }

  private void assertCycleway(Map<String, Object> extraTags, Object left, Object right) {
    Map<String, Object> tags = new HashMap<>(extraTags);
    tags.put("highway", "primary");
    assertFeatures(13, List.of(Map.of(
      "_layer", "transportation",
      "class", "primary",
      "cycleway_l", or(left),
      "cycleway_r", or(right)
    )), processWith(Map.of("transportation_cycleway", "true"), tags));
  }

  @Test
  void testCyclewayNone() {
    assertCycleway(Map.of(), null, null);
    assertCycleway(Map.of("cycleway", "no"), null, null);
    assertCycleway(Map.of("cycleway:both", "no"), null, null);
  }

  @Test
  void testCyclewayBoth() {
    assertCycleway(Map.of("cycleway:both", "lane"), 2, 2);
    assertCycleway(Map.of("cycleway:both", "track"), 3, 3);
    assertCycleway(Map.of("cycleway:both", "shared_lane"), 1, 1);
    assertCycleway(Map.of("cycleway:both", "separate"), 4, 4);
  }

  @Test
  void testCyclewayOneSide() {
    assertCycleway(Map.of("cycleway:right", "track"), null, 3);
    assertCycleway(Map.of("cycleway:left", "lane"), 2, null);
  }

  @Test
  void testCyclewayValues() {
    assertCycleway(Map.of("cycleway:right", "shared_lane"), null, 1);
    assertCycleway(Map.of("cycleway:right", "share_busway"), null, 1);
    assertCycleway(Map.of("cycleway:right", "shared"), null, 1);
    assertCycleway(Map.of("cycleway:right", "lane"), null, 2);
    assertCycleway(Map.of("cycleway:right", "buffered_lane"), null, 2);
    assertCycleway(Map.of("cycleway:right", "exclusive"), null, 2);
    assertCycleway(Map.of("cycleway:right", "advisory"), null, 2);
    assertCycleway(Map.of("cycleway:right", "track"), null, 3);
    assertCycleway(Map.of("cycleway:right", "separate"), null, 4);
    assertCycleway(Map.of("cycleway:right", "use_sidepath"), null, 4);
    assertCycleway(Map.of("cycleway:right", "something_unknown"), null, null);
  }

  @Test
  void testCyclewayPrecedence() {
    // side > both > cycleway
    assertCycleway(Map.of("cycleway:both", "track", "cycleway:left", "lane"), 2, 3);
    assertCycleway(Map.of("cycleway", "track", "cycleway:left", "lane"), 2, 3);
    assertCycleway(Map.of("cycleway", "lane", "cycleway:both", "track"), 3, 3);
    assertCycleway(Map.of("cycleway", "lane", "cycleway:both", "track", "cycleway:right", "shared_lane"), 3, 1);
    // a side with an explicit "no" stays none instead of falling back
    assertCycleway(Map.of("cycleway:both", "lane", "cycleway:left", "no"), null, 2);
  }

  @Test
  void testCyclewayOppositeIgnored() {
    assertCycleway(Map.of("cycleway", "opposite"), null, null);
    assertCycleway(Map.of("cycleway", "opposite_lane"), null, null);
    assertCycleway(Map.of("cycleway:right", "opposite_track"), null, null);
    assertCycleway(Map.of("cycleway:left", "opposite_lane"), null, null);
  }

  @Test
  void testCyclewayPhysicalSeparationUpgradesLane() {
    for (String separation : List.of("kerb", "bollard", "flex_post", "parking_lane", "parking")) {
      assertCycleway(Map.of("cycleway:right", "lane", "cycleway:right:separation", separation), null, 3);
    }
    assertCycleway(Map.of("cycleway:both", "lane", "cycleway:both:separation", "kerb"), 3, 3);
    assertCycleway(Map.of("cycleway:both", "lane", "cycleway:left:separation", "kerb"), 3, 2);
    // paint, or a separation that does not stop a car, stays a lane
    assertCycleway(Map.of("cycleway:right", "lane", "cycleway:right:separation", "no"), null, 2);
    assertCycleway(Map.of("cycleway:right", "lane", "cycleway:right:separation", "dashed_line"), null, 2);
    // separation alone says nothing about whether there is a lane
    assertCycleway(Map.of("cycleway:right:separation", "kerb"), null, null);
  }

  @Test
  void testCyclewayAttributesAreOptIn() {
    assertFeatures(13, List.of(Map.of(
      "_layer", "transportation",
      "cycleway_l", "<null>",
      "cycleway_r", "<null>",
      "shoulder", "<null>"
    )), process(lineFeature(Map.of(
      "highway", "primary",
      "cycleway:both", "track",
      "shoulder", "yes"
    ))));
  }

  @Test
  void testCyclewayMinzoom() {
    Map<String, Object> tags = Map.of("highway", "primary", "cycleway:both", "track");
    var enabled = Map.of("transportation_cycleway", "true");
    // default minzoom is 13
    assertFeatures(12, List.of(Map.of(
      "_layer", "transportation",
      "cycleway_l", "<null>",
      "cycleway_r", "<null>"
    )), processWith(enabled, tags));
    assertFeatures(13, List.of(Map.of(
      "_layer", "transportation",
      "cycleway_l", 3,
      "cycleway_r", 3
    )), processWith(enabled, tags));

    var custom = Map.of("transportation_cycleway", "true", "transportation_cycleway_minzoom", "14");
    assertFeatures(13, List.of(Map.of(
      "_layer", "transportation",
      "cycleway_l", "<null>",
      "cycleway_r", "<null>"
    )), processWith(custom, tags));
    assertFeatures(14, List.of(Map.of(
      "_layer", "transportation",
      "cycleway_l", 3,
      "cycleway_r", 3
    )), processWith(custom, tags));
  }

  private void assertShoulder(String value, Object expected) {
    assertFeatures(13, List.of(Map.of(
      "_layer", "transportation",
      "shoulder", or(expected)
    )), processWith(Map.of("transportation_cycleway", "true"), Map.of("highway", "primary", "shoulder", value)));
  }

  @Test
  void testShoulder() {
    assertShoulder("yes", 1);
    assertShoulder("both", 1);
    assertShoulder("left", 1);
    assertShoulder("right", 1);
    assertShoulder("no", null);
    assertShoulder("maybe", null);
  }

  @Test
  void testShoulderAbsent() {
    assertFeatures(13, List.of(Map.of(
      "_layer", "transportation",
      "shoulder", "<null>"
    )), processWith(Map.of("transportation_cycleway", "true"), Map.of("highway", "primary")));
  }

  @Test
  void testLinesWithCyclewaySidesAreNotMerged() throws GeometryException {
    // cycleway_l/cycleway_r are relative to the line direction and mergeLineStrings re-orients lines
    testDoesNotMergeLinestrings(Map.of("class", "primary", "cycleway_l", 2, "cycleway_r", 3), "transportation", 10,
      13);
    testDoesNotMergeLinestrings(Map.of("class", "primary", "cycleway_r", 3), "transportation", 10, 13);
    // lines without them still merge, and so do lines that only differ by shoulder
    testMergesLinestrings(Map.of("class", "primary"), "transportation", 10, 13);
    testMergesLinestrings(Map.of("class", "primary", "shoulder", 1), "transportation", 10, 13);
  }

  @Test
  void testMergeTagIsNotLeakedIntoTiles() throws GeometryException {
    var attrs = new HashMap<String, Object>(Map.of("class", "primary", "cycleway_l", 2));
    var line = new VectorTile.Feature("transportation", 1, VectorTile.encodeGeometry(newLineString(0, 0, 10, 0)),
      attrs, 0);
    var out = profile.postProcessLayerFeatures("transportation", 13, List.of(line));
    assertEquals(1, out.size());
    assertEquals(Map.of("class", "primary", "cycleway_l", 2), out.get(0).tags());
  }
}
