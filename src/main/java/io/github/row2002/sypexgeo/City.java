package io.github.row2002.sypexgeo;

import java.util.Map;

/**
 * City record.
 *
 * @param fields all fields of the record except internal links ({@code region_seek}, {@code country_seek},
 *               {@code country_id}), including extra City Max fields; unmodifiable
 */
public record City(int id, String nameRu, String nameEn, double lat, double lon, Map<String, Object> fields) {
}
