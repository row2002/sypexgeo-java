package io.github.row2002.sypexgeo;

/**
 * Lookup result.
 *
 * @param country always present
 * @param region  {@code null} when the database (or the IP range) has no region
 * @param city    {@code null} when the database (or the IP range) has no city
 */
public record GeoInfo(Country country, Region region, City city) {
}
