package org.openmaptiles.addons;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.FeatureMerge;
import com.onthegomap.planetiler.ForwardingProfile;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.reader.SourceFeature;
import java.util.List;
import org.openmaptiles.Layer;
import org.openmaptiles.OpenMapTilesProfile;

/**
 * Overhead power lines, behind {@code --power_lines}: {@code power=line} from z14 in a {@code power} layer of their
 * own, since OpenMapTiles has nowhere to put them.
 * <p>
 * Only transmission lines: {@code minor_line} is the village distribution network, noise on a map read for the
 * landscape, and {@code power=cable} is nearly all underground. On rhone-alpes the 9,400 km of lines cost ~0.1% of
 * tile bytes once merged per tile.
 */
public class Power implements Layer, OpenMapTilesProfile.OsmAllProcessor, ForwardingProfile.LayerPostProcessor {

  public static final String LAYER_NAME = "power";
  private static final int BUFFER_SIZE = 4;
  private final PlanetilerConfig config;
  private final boolean enabled;

  public Power(PlanetilerConfig config) {
    this.config = config;
    this.enabled = config.arguments().getBoolean(
      "power_lines",
      "power layer: emit overhead power lines (power=line) from z14",
      false
    );
  }

  @Override
  public String name() {
    return LAYER_NAME;
  }

  @Override
  public void processAllOsm(SourceFeature feature, FeatureCollector features) {
    if (enabled && feature.canBeLine() && feature.hasTag("power", "line")) {
      features.line(LAYER_NAME).setBufferPixels(BUFFER_SIZE)
        .setAttr("class", "line")
        .setMinPixelSize(0) // merged per tile in postProcess
        .setMinZoom(14);
    }
  }

  @Override
  public List<VectorTile.Feature> postProcess(int zoom, List<VectorTile.Feature> items) throws GeometryException {
    return FeatureMerge.mergeLineStrings(items, config.minFeatureSize(zoom), config.tolerance(zoom), BUFFER_SIZE);
  }
}
