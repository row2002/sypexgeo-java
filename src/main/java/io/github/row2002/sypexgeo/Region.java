package io.github.row2002.sypexgeo;

import java.util.Locale;
import java.util.Map;

/**
 * Region record.
 *
 * @param iso    ISO 3166-2 code, e.g. {@code RU-MOW}
 * @param fields all fields of the record except internal links ({@code region_seek}, {@code country_seek},
 *               {@code country_id}), including extra City Max fields; unmodifiable
 */
public record Region(int id, String iso, String nameRu, String nameEn, Map<String, Object> fields) {

    /**
     * Name for the locale: {@code nameRu} for Russian ({@code locale.getLanguage()} is {@code "ru"}) if it is not
     * empty, otherwise {@code nameEn}, or {@code nameRu} if {@code nameEn} is empty.
     */
    public String name(Locale locale) {
        return SxGeo.name(locale, nameRu, nameEn);
    }
}
