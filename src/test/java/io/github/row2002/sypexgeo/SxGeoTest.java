package io.github.row2002.sypexgeo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests on the real databases: put SxGeo.dat and SxGeoCity.dat from https://sypexgeo.net/ru/download/
 * into testdata/. A test is skipped when its file is missing. Only long-stable facts are asserted, so the tests
 * survive the monthly database updates.
 */
class SxGeoTest {
    private static final Map<String, SxGeo> DBS = new ConcurrentHashMap<>();

    private static Path file(String name) {
        Path file = Path.of("testdata", name);
        assumeTrue(Files.isRegularFile(file), file + " not found, skipping");
        return file;
    }

    private static SxGeo db(String name) {
        Path file = file(name);
        return DBS.computeIfAbsent(name, n -> {
            try {
                return new SxGeo(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static SxGeo city() {
        return db("SxGeoCity.dat");
    }

    private static SxGeo country() {
        return db("SxGeo.dat");
    }

    @Test
    void countryDatabase() {
        SxGeo country = country();
        assertFalse(country.info().hasCities());
        assertEquals("SxGeo Country", country.info().type());
        assertEquals(Optional.of("RU"), country.country("77.88.8.8"));
        assertEquals(Optional.of("US"), country.country("8.8.8.8"));
        GeoInfo info = country.lookup("77.88.8.8").orElseThrow();
        assertEquals(new Country(185, "RU", "", "", 0, 0, Map.of()), info.country());
        assertNull(info.region());
        assertNull(info.city());
    }

    @Test
    void cityDatabase() {
        SxGeo city = city();
        assertTrue(city.info().hasCities());
        GeoInfo info = city.lookup("93.73.35.74").orElseThrow();
        assertEquals("UA", info.country().iso());
        assertEquals("Ukraine", info.country().nameEn());
        assertEquals("Украина", info.country().nameRu());
        assertEquals("UA-30", info.region().iso());
        assertTrue(Set.of("Kyiv", "Kiev").contains(info.city().nameEn()), info.city().nameEn());
        assertEquals("Киев", info.city().nameRu());
        assertEquals(50.45, info.city().lat(), 0.3);
        assertEquals(30.52, info.city().lon(), 0.3);
        for (Map<String, Object> fields : Arrays.asList(info.country().fields(), info.region().fields(), info.city().fields())) {
            assertFalse(fields.containsKey("region_seek") || fields.containsKey("country_seek") || fields.containsKey("country_id"), fields.toString());
        }

        assertEquals(Optional.of("RU"), city.country("77.88.8.8"));
        assertEquals(185, city.countryId("77.88.8.8").getAsInt());
        assertEquals("Russia", city.lookup("77.88.8.8").orElseThrow().country().nameEn());
        assertEquals(Optional.of("US"), city.country("8.8.8.8"));
    }

    @Test
    void lookupAndCountryAgree() {
        SxGeo city = city();
        Random random = new Random(7);
        for (int i = 0; i < 50_000; i++) {
            String ip = (1 + random.nextInt(223)) + "." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256);
            Optional<GeoInfo> info = city.lookup(ip);
            assertEquals(city.country(ip), info.map(g -> g.country().iso()).filter(iso -> !iso.isEmpty()), ip);
            info.ifPresent(g -> assertEquals(city.countryId(ip).orElse(0), g.country().id(), ip));
        }
    }

    @Test
    void inet4AddressOverload() throws IOException {
        SxGeo city = city();
        Inet4Address address = (Inet4Address) InetAddress.getByAddress(new byte[]{77, 88, 8, 8});
        assertEquals(Optional.of("RU"), city.country(address));
        assertEquals(city.lookup("77.88.8.8"), city.lookup(address));
    }

    @Test
    void reservedAndMalformedAddresses() {
        SxGeo city = city(), country = country();
        String[] ips = {"0.0.0.0", "10.1.2.3", "127.0.0.1", "255.255.255.255", "", "1.2.3", "1.2.3.4.5", "256.1.1.1", "-1.2.3.4",
                "+1.2.3.4", "1..2.3", "1.2.3.", "a.b.c.d", "::1", "2001:db8::1", "example.com", "77.88.8.8, 10.0.0.1", "077.88.8.8", null};
        for (String ip : ips) {
            assertTrue(city.lookup(ip).isEmpty(), ip);
            assertTrue(city.country(ip).isEmpty(), ip);
            assertTrue(country.country(ip).isEmpty(), ip);
        }
        assertEquals(Optional.of("RU"), city.country(" 77.88.8.8 "));
    }

    @Test
    void randomAddressesNeverThrow() {
        SxGeo city = city(), country = country();
        Random random = new Random(42);
        for (int i = 0; i < 200_000; i++) {
            String ip = random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256);
            city.lookup(ip).ifPresent(info -> assertEquals(2, info.country().iso().length(), ip));
            country.country(ip);
        }
    }

    @Test
    void rejectsTruncatedRealDatabase() throws IOException {
        byte[] full = Files.readAllBytes(file("SxGeoCity.dat"));
        for (int size : new int[]{60, 1000, full.length / 2, full.length - 1}) {
            assertThrows(IllegalArgumentException.class, () -> new SxGeo(Arrays.copyOf(full, size)), "size " + size);
        }
    }
}
