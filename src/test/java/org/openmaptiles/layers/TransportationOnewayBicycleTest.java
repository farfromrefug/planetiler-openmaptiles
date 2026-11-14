package org.openmaptiles.layers;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.stats.Stats;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openmaptiles.OpenMapTilesProfile;

/** Tests for oneway_bicycle (one-way ways that bikes may ride both ways), emitted with transportation_cycleway. */
class TransportationOnewayBicycleTest extends AbstractLayerTest {

  private FeatureCollector processWith(Map<String, String> options, Map<String, Object> tags) {
    var profile = new OpenMapTilesProfile(translations, PlanetilerConfig.from(Arguments.of(options)),
      Stats.inMemory());
    SourceFeature feature = lineFeature(tags);
    var collector = featureCollectorFactory.get(feature);
    profile.processFeature(feature, collector);
    return collector;
  }

  private void assertContraflow(Map<String, Object> extraTags, Object expected) {
    assertContraflow(Map.of("transportation_cycleway", "true"), extraTags, expected);
  }

  private void assertContraflow(Map<String, String> options, Map<String, Object> extraTags, Object expected) {
    Map<String, Object> tags = new HashMap<>(extraTags);
    tags.put("highway", "residential");
    assertFeatures(13, List.of(Map.of(
      "_layer", "transportation",
      "class", "minor",
      "oneway_bicycle", expected == null ? "<null>" : expected
    )), processWith(options, tags));
  }

  @Test
  void testOnewayBicycleNo() {
    assertContraflow(Map.of("oneway", "yes", "oneway:bicycle", "no"), "no");
  }

  @Test
  void testOppositeCycleway() {
    assertContraflow(Map.of("oneway", "yes", "cycleway", "opposite"), "no");
    assertContraflow(Map.of("oneway", "yes", "cycleway:left", "opposite_lane"), "no");
    assertContraflow(Map.of("oneway", "-1", "cycleway:right", "opposite_track"), "no");
    assertContraflow(Map.of("oneway", "yes", "cycleway:both", "opposite"), "no");
  }

  @Test
  void testPlainOnewayHasNone() {
    assertContraflow(Map.of("oneway", "yes"), null);
    assertContraflow(Map.of("oneway", "yes", "cycleway", "lane"), null);
  }

  @Test
  void testNotOnewayHasNone() {
    assertContraflow(Map.of("oneway:bicycle", "no"), null);
    assertContraflow(Map.of("cycleway", "opposite"), null);
  }

  @Test
  void testOffByDefault() {
    assertContraflow(Map.of(), Map.of("oneway", "yes", "oneway:bicycle", "no"), null);
  }
}
