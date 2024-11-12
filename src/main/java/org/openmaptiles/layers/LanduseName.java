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

import static org.openmaptiles.util.Utils.nullIfLong;

import com.onthegomap.planetiler.FeatureCollector;
import org.openmaptiles.generated.OpenMapTilesSchema;
import org.openmaptiles.generated.Tables;
import org.openmaptiles.util.OmtLanguageUtils;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.geo.GeoUtils;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.stats.Stats;
import com.onthegomap.planetiler.util.Translations;
import com.onthegomap.planetiler.util.ZoomFunction;

public class LanduseName implements
  OpenMapTilesSchema.LanduseName,
  Tables.OsmLandusePolygon.Handler,
  Tables.OsmLanduseUnderPolygon.Handler {

  /*
   * Generate building names from OSM data. 
   */

  private static final double WORLD_AREA_FOR_50K_SQUARE_METERS =
    Math.pow(GeoUtils.metersToPixelAtEquator(0, Math.sqrt(70_000)) / 256d, 2);
  private static final double LOG2 = Math.log(2);

  private final Translations translations;
  private final Stats stats;

  public LanduseName(Translations translations, PlanetilerConfig config, Stats stats) {
    this.translations = translations;
    this.stats = stats;
  }

  private int getMinZoomForArea(double area) {
    // sql filter:    area > 50000*2^(20-zoom_level)
    // simplifies to: zoom_level > 20 - log(area / 50000) / log(2)
    int minzoom = (int) Math.floor(20 - Math.log(area / WORLD_AREA_FOR_50K_SQUARE_METERS) / LOG2);
    minzoom = Math.min(14, Math.max(5, minzoom));
    return minzoom;
  }

  @Override
  public void process(Tables.OsmLandusePolygon element, FeatureCollector features) {
    try {
      String clazz = Landuse.getClass(element);
      if (clazz != null &&
        (clazz.equals("quarry") ||
          clazz.equals("military") ||
          clazz.equals("railway") ||
          clazz.equals("commercial") ||
          clazz.equals("industrial") ||
          clazz.equals("retail") ||
          clazz.equals("track") ||
          clazz.equals("playground") ||
          clazz.equals("dam")) &&
        element.source().hasTag("name")) {

        var names = OmtLanguageUtils.getNames(element.source().tags(), translations);
        Double area = element.source().area();

        features.pointOnSurface(LAYER_NAME).setBufferPixels(BUFFER_SIZE)
          .setAttr(Fields.CLASS, clazz)
          .setAttr("way_pixels",
            area != null ?
              (ZoomFunction<Long>) zoom -> nullIfLong(Math.round(area * Math.pow(256 * Math.pow(2, zoom - 1), 2)), 0) :
              null)
          .putAttrs(names)
          .setMinZoom(getMinZoomForArea(area));
      }
    } catch (GeometryException e) {
      e.log(stats, "omt_landuse_poly",
        "Unable to get area for OSM landuse polygon " + element.source().id());
    }
  }

  @Override
  public void process(Tables.OsmLanduseUnderPolygon element, FeatureCollector features) {
    try {
      String clazz = LanduseUnder.getClass(element);
      if (clazz != null &&
        (clazz.equals("quarry") ||
          clazz.equals("military") ||
          clazz.equals("railway") ||
          clazz.equals("commercial") ||
          clazz.equals("industrial") ||
          clazz.equals("retail") ||
          clazz.equals("track") ||
          clazz.equals("playground") ||
          clazz.equals("dam")) &&
        element.source().hasTag("name")) {

        var names = OmtLanguageUtils.getNames(element.source().tags(), translations);
        Double area = element.source().area();

        features.pointOnSurface(LAYER_NAME).setBufferPixels(BUFFER_SIZE)
          .setAttr(Fields.CLASS, clazz)
          .setAttr("way_pixels",
            area != null ?
              (ZoomFunction<Long>) zoom -> nullIfLong(Math.round(area * Math.pow(256 * Math.pow(2, zoom - 1), 2)), 0) :
              null)
          .putAttrs(names)
          .setMinZoom(getMinZoomForArea(area));
      }
    } catch (GeometryException e) {
      e.log(stats, "omt_landuse_poly",
        "Unable to get area for OSM landuse polygon " + element.source().id());
    }
  }
}
