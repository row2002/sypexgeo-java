package io.github.row2002.sypexgeo;

import java.nio.charset.Charset;
import java.time.Instant;

/**
 * Database header: build date, edition and charset.
 *
 * @param hasCities {@code false} for the country-only database {@code SxGeo.dat}
 */
public record DbInfo(String version, Instant created, String type, Charset charset, boolean hasCities, int ipBlocks) {
}
