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
package org.openmaptiles.util;

import static com.onthegomap.planetiler.util.LanguageUtils.*;
import static org.openmaptiles.util.Utils.coalesce;

import com.onthegomap.planetiler.util.LanguageUtils;
import com.onthegomap.planetiler.util.Translations;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import com.onthegomap.planetiler.config.PlanetilerConfig;

/**
 * Utilities to extract common name fields (name, name_en, name_de, name:latin, name:nonlatin, name_int) that the
 * OpenMapTiles schema uses across any map element with a name.
 * <p>
 * Ported from
 * <a href="https://github.com/openmaptiles/openmaptiles-tools/blob/master/sql/zzz_language.sql">openmaptiles-tools</a>.
 */
public class OmtLanguageUtils {
  /**
   * When set, name_int is omitted whenever it would be an exact copy of name - otherwise every named
   * feature pays for a second tag pointing at the same string.
   * <p>
   * Static because getNames is static and called from every layer; there is no instance to hang it
   * on. {@link #configure(PlanetilerConfig)} is called once from the OpenMapTilesProfile constructor,
   * before any layer is built. Defaults to false, so output is unchanged unless asked for.
   */
  private static volatile boolean dropRedundantNameInt = false;

  /**
   * When set, a name tag is omitted whenever another name tag on the same feature already carries
   * the identical string.
   * <p>
   * With {@code --languages=fr,en} a French feature that also has {@code name:en} comes out as four
   * tags holding two distinct strings:
   *
   * <pre>
   * name     = Parc naturel régional de Chartreuse
   * name:fr  = Parc naturel régional de Chartreuse
   * name:en  = Chartreuse Regional Nature Park
   * name_int = Chartreuse Regional Nature Park
   * </pre>
   *
   * This drops {@code name:fr} and {@code name_int}, leaving both strings reachable exactly once.
   * <p>
   * <b>This is only safe for a client whose fallback reaches {@code name} before {@code name_int}</b>
   * - {@code name:<lang>} then {@code name} then {@code name_int}. Under the opposite order a
   * French reader whose {@code name:fr} was dropped as a duplicate lands on the English
   * {@code name_int} instead of the local name, which is worse than the duplication.
   * <p>
   * The saving is small: on a rhone-alpes basemap only about 2.7% of named features carry any
   * translation at all. This is for a coherent set of name tags, not for tile size.
   */
  private static volatile boolean dropDuplicateNames = false;

  /** Reads name behaviour from the run's arguments. Called once at profile construction. */
  public static void configure(PlanetilerConfig config) {
    dropRedundantNameInt = config.arguments().getBoolean("drop_redundant_name_int",
      "omit name_int when it is an exact copy of name", false);
    dropDuplicateNames = config.arguments().getBoolean("drop_duplicate_names",
      "omit a name:<lang> that copies name, and name_int when another name tag already carries it",
      false);
  }

  /**
   * Returns a map with default name attributes (name, name_en, name_de, name:latin, name:nonlatin, name_int) that every
   * element should have, derived from name, int_name, name:en, and name:de tags on the input element.
   *
   * <ul>
   * <li>name is the original name value from the element</li>
   * <li>name_en is the original name:en value from the element, or name if missing</li>
   * <li>name_de is the original name:de value from the element, or name/ name_en if missing</li>
   * <li>name:latin is the first of name, int_name, or any name: attribute that contains only latin characters</li>
   * <li>name:nonlatin is any nonlatin part of name if present</li>
   * <li>name_int is the first of int_name name:en name:latin name</li>
   * </ul>
   */
  public static Map<String, Object> getNamesWithoutTranslations(Map<String, Object> tags) {
    return getNames(tags, null);
  }

  /**
   * Returns a map with default name attributes that {@link #getNamesWithoutTranslations(Map)} adds, but also
   * translations for every language that {@code translations} is configured to handle.
   */
  public static Map<String, Object> getNames(Map<String, Object> tags, Translations translations) {
    Map<String, Object> result = new HashMap<>();

    String name = string(tags.get("name"));
    String intName = string(tags.get("int_name"));
    String nameEn = string(tags.get("name:en"));
    // String nameDe = string(tags.get("name:de"));

    boolean isLatin = containsOnlyLatinCharacters(name);
    String latin = isLatin ? name :
      Stream
        .concat(Stream.of(nameEn, intName), getAllNameTranslationsBesidesEnglishAndGerman(tags))
        .filter(LanguageUtils::containsOnlyLatinCharacters)
        .findFirst().orElse(null);
    if (latin == null && translations != null && translations.getShouldTransliterate()) {
      latin = transliteratedName(tags);
    }
    String nonLatin = isLatin ? null : removeLatinCharacters(name);
    if (coalesce(nonLatin, "").equals(latin)) {
      nonLatin = null;
    }

    putIfNotEmpty(result, "name", name);
    // putIfNotEmpty(result, "name_en", coalesce(nameEn, name));
    // putIfNotEmpty(result, "name_de", coalesce(nameDe, name, nameEn));
    // putIfNotEmpty(result, "name:latin", latin);
    // putIfNotEmpty(result, "name:nonlatin", nonLatin);
    String nameInt = coalesce(
      intName,
      nameEn,
      latin
    //   name
    );

    // translations first, and name_int last: whether name_int is redundant depends on which
    // name:<lang> tags actually survive, and dropping a translation can be what makes name_int
    // the only copy of that string left
    if (translations != null) {
      translations.addTranslations(result, tags);
    }
    if (dropDuplicateNames) {
      result.entrySet()
        .removeIf(entry -> entry.getKey().startsWith("name:") && Objects.equals(entry.getValue(), name));
    }

    boolean redundant = (dropRedundantNameInt && Objects.equals(nameInt, name)) ||
      (dropDuplicateNames && alreadyCarried(result, nameInt));
    if (!redundant) {
      putIfNotEmpty(result, "name_int", nameInt);
    }

    return result;
  }

  /** Whether some name tag already emitted for this feature holds {@code value}. */
  private static boolean alreadyCarried(Map<String, Object> names, String value) {
    return value != null && names.values().stream().anyMatch(held -> Objects.equals(held, value));
  }

  public static String string(Object obj) {
    return nullIfEmpty(obj == null ? null : obj.toString());
  }

  public static String transliteratedName(Map<String, Object> tags) {
    return Translations.transliterate(string(tags.get("name")));
  }

  private static Stream<String> getAllNameTranslationsBesidesEnglishAndGerman(Map<String, Object> tags) {
    return tags.entrySet().stream()
      .filter(e -> !EN_DE_NAME_KEYS.contains(e.getKey()) && isValidOsmNameTag(e.getKey()))
      .map(Map.Entry::getValue)
      .map(OmtLanguageUtils::string);
  }
}
