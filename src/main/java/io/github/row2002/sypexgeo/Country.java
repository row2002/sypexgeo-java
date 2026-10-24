package io.github.row2002.sypexgeo;

import java.util.Map;

/**
 * Country record.
 *
 * @param iso    ISO 3166-1 alpha-2 code, e.g. {@code RU}
 * @param fields every field stored in the database, including extra City Max fields
 */
public record Country(int id, String iso, String nameRu, String nameEn, double lat, double lon, Map<String, Object> fields) {

    Country(int id, String iso) {
        this(id, iso, "", "", 0, 0, Map.of());
    }
}
