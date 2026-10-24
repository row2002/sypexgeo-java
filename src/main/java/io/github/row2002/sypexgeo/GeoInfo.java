package io.github.row2002.sypexgeo;

import org.jspecify.annotations.Nullable;

/**
 * Lookup result.
 *
 * @param country always present; with a city database it is the city's own country (the one
 *                {@link SxGeo#countryId(String)} returns), even when the region record points to another one
 * @param region  {@code null} when the database (or the IP range) has no region
 * @param city    {@code null} when the database (or the IP range) has no city
 */
public record GeoInfo(Country country, @Nullable Region region, @Nullable City city) {
}
