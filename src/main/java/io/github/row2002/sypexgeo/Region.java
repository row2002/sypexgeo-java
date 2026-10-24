package io.github.row2002.sypexgeo;

import java.util.Map;

/**
 * Region record.
 *
 * @param iso    ISO 3166-2 code, e.g. {@code RU-MOW}
 * @param fields every field stored in the database, including extra City Max fields
 */
public record Region(int id, String iso, String nameRu, String nameEn, Map<String, Object> fields) {
}
