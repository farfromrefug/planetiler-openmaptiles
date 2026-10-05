package org.openmaptiles.layers;

import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.stats.Stats;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openmaptiles.OpenMapTilesProfile;

class PowerTest extends AbstractLayerTest {

  private final OpenMapTilesProfile withPowerLines = new OpenMapTilesProfile(translations,
    PlanetilerConfig.from(Arguments.of(Map.of("power_lines", "true"))), Stats.inMemory());

  @Test
  void testOffByDefault() {
    assertFeatures(14, List.of(), process(lineFeature(Map.of("power", "line"))));
  }

  @Test
  void testPowerLineFromZ14() {
    var feature = lineFeature(Map.of("power", "line", "voltage", "225000", "name", "Ligne Grande-Île"));
    var collector = featureCollectorFactory.get(feature);
    withPowerLines.processFeature(feature, collector);
    assertFeatures(14, List.of(Map.of(
      "_layer", "power",
      "class", "line",
      "name", "<null>",
      "_minzoom", 14
    )), collector);
  }

  @Test
  void testNoMinorLinesOrCables() {
    for (var tags : List.<Map<String, Object>>of(Map.of("power", "minor_line"), Map.of("power", "cable"))) {
      var feature = lineFeature(tags);
      var collector = featureCollectorFactory.get(feature);
      withPowerLines.processFeature(feature, collector);
      assertFeatures(14, List.of(), collector);
    }
  }
}
