package io.github.row2002.sypexgeo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Needs testdata/SxGeo.dat and testdata/SxGeoCity.dat from https://sypexgeo.net/ru/download/ */
class SxGeoTest {
    private static SxGeo city;
    private static SxGeo country;

    @BeforeAll
    static void open() throws IOException {
        Path cityFile = Path.of("testdata/SxGeoCity.dat");
        Path countryFile = Path.of("testdata/SxGeo.dat");
        assumeTrue(Files.exists(cityFile) && Files.exists(countryFile), "testdata/*.dat not found, skipping");
        city = new SxGeo(cityFile);
        country = new SxGeo(countryFile);
    }

    @Test
    void countryDatabase() {
        assertFalse(country.info().hasCities());
        assertEquals(Optional.of("RU"), country.country("77.88.8.8"));
        assertEquals(Optional.of("US"), country.country("8.8.8.8"));
        GeoInfo info = country.lookup("77.88.8.8").orElseThrow();
        assertEquals("RU", info.country().iso());
        assertNull(info.city());
    }

    @Test
    void cityDatabase() {
        assertTrue(city.info().hasCities());
        GeoInfo info = city.lookup("93.73.35.74").orElseThrow();
        assertEquals("UA", info.country().iso());
        assertEquals("Ukraine", info.country().nameEn());
        assertEquals("Украина", info.country().nameRu());
        assertEquals("Kyiv", info.region().nameEn());
        assertEquals("UA-30", info.region().iso());
        assertEquals("Kyiv", info.city().nameEn());
        assertEquals("Киев", info.city().nameRu());
        assertEquals(50.45, info.city().lat(), 0.1);
        assertEquals(30.52, info.city().lon(), 0.1);
        assertFalse(info.city().fields().containsKey("region_seek"));

        assertEquals(Optional.of("RU"), city.country("77.88.8.8"));
        assertEquals(185, city.countryId("77.88.8.8").getAsInt());
        assertEquals("Moscow", city.lookup("77.88.8.8").orElseThrow().city().nameEn());
    }

    @Test
    void lookupAndCountryAgreeWhenRegionPointsToAnotherCountry() {
        // Asuncion (PY) whose region record belongs to GB in the current database
        for (String ip : new String[]{"77.88.8.8", "181.121.37.85", "177.250.87.182"}) {
            assertEquals(city.country(ip), city.lookup(ip).map(info -> info.country().iso()), ip);
        }
    }

    @Test
    void inet4AddressOverload() throws IOException {
        Inet4Address address = (Inet4Address) InetAddress.getByAddress(new byte[]{77, 88, 8, 8});
        assertEquals(Optional.of("RU"), city.country(address));
        assertEquals(city.lookup("77.88.8.8"), city.lookup(address));
    }

    @Test
    void reservedAndMalformedAddresses() {
        String[] ips = {"0.0.0.0", "10.1.2.3", "127.0.0.1", "255.255.255.255", "", "1.2.3", "1.2.3.4.5",
                "256.1.1.1", "-1.2.3.4", "+1.2.3.4", "1..2.3", "1.2.3.", "a.b.c.d", "::1", "2001:db8::1", "example.com", "77.88.8.8, 10.0.0.1", null};
        for (String ip : ips) {
            assertTrue(city.lookup(ip).isEmpty(), ip);
            assertTrue(city.country(ip).isEmpty(), ip);
            assertTrue(country.country(ip).isEmpty(), ip);
        }
        assertEquals(Optional.of("RU"), city.country(" 77.88.8.8 "));
    }

    @Test
    void randomAddressesNeverThrow() {
        Random random = new Random(42);
        for (int i = 0; i < 200_000; i++) {
            String ip = random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256);
            city.lookup(ip).ifPresent(info -> assertEquals(2, info.country().iso().length(), ip));
            country.country(ip);
        }
    }

    @Test
    void rejectsNonSypexGeoData() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> new SxGeo(new byte[100]));
        assertThrows(IllegalArgumentException.class, () -> new SxGeo("SxG".getBytes(StandardCharsets.US_ASCII)));
        byte[] full = Files.readAllBytes(Path.of("testdata/SxGeoCity.dat"));
        for (int size : new int[]{60, 1000, full.length / 2}) {
            assertThrows(IllegalArgumentException.class, () -> new SxGeo(Arrays.copyOf(full, size)), "size " + size);
        }
    }
}
