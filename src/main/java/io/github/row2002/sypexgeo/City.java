package io.github.row2002.sypexgeo;

import java.util.Map;

/**
 * City record.
 *
 * @param fields every field stored in the database, including extra City Max fields
 */
public record City(int id, String nameRu, String nameEn, double lat, double lon, Map<String, Object> fields) {
}
