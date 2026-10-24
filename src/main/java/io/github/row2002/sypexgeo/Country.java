package io.github.row2002.sypexgeo;

import java.util.Map;

/**
 * Country record.
 *
 * <p>With the country-only database ({@code SxGeo.dat}) only {@code id} and {@code iso} are filled: names are
 * empty, coordinates are 0 and {@code fields} is empty. The same holds when a city database has no record
 * for the city's country.
 *
 * @param iso    ISO 3166-1 alpha-2 code, e.g. {@code RU}
 * @param fields all fields of the record except internal links ({@code region_seek}, {@code country_seek},
 *               {@code country_id}), including extra City Max fields; unmodifiable
 */
public record Country(int id, String iso, String nameRu, String nameEn, double lat, double lon, Map<String, Object> fields) {

    Country(int id, String iso) {
        this(id, iso, "", "", 0, 0, Map.of());
    }
}
