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

import com.onthegomap.planetiler.FeatureCollector;
import org.openmaptiles.generated.OpenMapTilesSchema;
import org.openmaptiles.generated.Tables;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.geo.SimplifyMethod;
import com.onthegomap.planetiler.stats.Stats;
import com.onthegomap.planetiler.util.Translations;

/**
 * Defines the logic for generating map elements in the {@code aeroway} layer from source features.
 * <p>
 * This class is ported to Java from
 * <a href="https://github.com/openmaptiles/openmaptiles/tree/master/layers/aeroway">OpenMapTiles aeroway sql files</a>.
 */
public class Aeroway implements
  OpenMapTilesSchema.Aeroway,
  Tables.OsmAerowayLinestring.Handler,
  Tables.OsmAerowayPolygon.Handler,
  Tables.OsmAerowayPoint.Handler {

  public Aeroway(Translations translations, PlanetilerConfig config, Stats stats) {}

  @Override
  public void process(Tables.OsmAerowayPolygon element, FeatureCollector features) {
    features.polygon(LAYER_NAME)
      .setMinZoom(10)
      .setSimplifyMethod(SimplifyMethod.VISVALINGAM_WHYATT)
      .setMinPixelSize(2)
      .setAttr(Fields.CLASS, element.aeroway())
      .setAttr(Fields.REF, element.ref());
  }

  @Override
  public void process(Tables.OsmAerowayLinestring element, FeatureCollector features) {
    features.line(LAYER_NAME)
      .setMinZoom(10)
      .setAttr(Fields.CLASS, element.aeroway())
      .setAttr(Fields.REF, element.ref());
  }

  @Override
  public void process(Tables.OsmAerowayPoint element, FeatureCollector features) {
    features.point(LAYER_NAME)
      .setMinZoom(14)
      .setAttr(Fields.CLASS, element.aeroway())
      .setAttr(Fields.REF, element.ref());
  }
}
