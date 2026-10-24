package io.github.row2002.sypexgeo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.stream.Stream;

import static io.github.row2002.sypexgeo.SyntheticDb.ip;
import static org.junit.jupiter.api.Assertions.*;

/** Hermetic tests on synthetic databases: no downloaded files needed. */
class SyntheticDbTest {

    record Config(String name, boolean city, long seed, int ranges, int rangeLen, String split, int charset, int idLen,
                  boolean sentinel, boolean maxPack) {
        SyntheticDb db() {
            SyntheticDb db = new SyntheticDb(city);
            db.rangeLen = rangeLen;
            db.split = split;
            db.charset = charset;
            db.idLen = idLen;
            db.sentinel = sentinel;
            return db.generate(seed, ranges, maxPack);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Config> configs() {
        return Stream.of(
                new Config("country, empty_only, latin1", false, 1, 300, 16, "empty_only", 1, 1, false, false),
                new Config("country, split always", false, 2, 300, 7, "always", 1, 1, false, false),
                new Config("country, empty buckets", false, 3, 300, 16, "never", 1, 1, false, false),
                new Config("country, id_len 2, utf8", false, 4, 400, 9, "empty_only", 0, 2, false, false),
                new Config("country, id_len 3, sentinel", false, 11, 500, 5, "never", 1, 3, true, false),
                new Config("city, utf8", true, 5, 300, 16, "always", 0, 3, true, false),
                new Config("city, cp1251, no sentinel", true, 6, 300, 9, "always", 2, 3, false, false),
                new Config("city, latin1, every pack code", true, 7, 350, 12, "always", 1, 3, true, true),
                new Config("city, empty buckets", true, 8, 300, 16, "never", 0, 3, true, false),
                new Config("city, range_len 4, cp1251, every pack code", true, 9, 400, 4, "never", 2, 3, false, true),
                new Config("city, id_len 2", true, 10, 300, 7, "empty_only", 0, 2, true, false));
    }

    private static void assertMatches(SyntheticDb spec, SxGeo db, List<Long> probes) {
        for (long ip : probes) {
            String s = SyntheticDb.ip(ip);
            SyntheticDb.Expected e = spec.expected(ip);
            assertEquals(e.countryId(), db.countryId(s), s);
            assertEquals(e.country(), db.country(s), s);
            assertEquals(e.lookup(), db.lookup(s), s);
        }
    }

    @ParameterizedTest
    @MethodSource("configs")
    void everyBoundaryMatchesBruteForce(Config config) {
        SyntheticDb spec = config.db();
        SxGeo db = new SxGeo(spec.build());
        boolean bigBucket = false, emptyBucket = false;
        for (int f = 1; f < spec.bIdxLen; f++) {
            bigBucket |= spec.bIdx[f] - spec.bIdx[f - 1] > spec.rangeLen;
            emptyBucket |= spec.bIdx[f] == spec.bIdx[f - 1];
        }
        assertTrue(bigBucket, "a first octet with more than range_len blocks");
        assertEquals(config.split().equals("never"), emptyBucket, "empty first-octet buckets");
        assertEquals(config.city(), db.info().hasCities());
        assertEquals(spec.starts.length, db.info().ipBlocks());
        assertEquals(spec.cs(), db.info().charset());
        assertMatches(spec, db, spec.probes());
    }

    @Test
    void rangeStartsAndIndexEntriesResolveToTheirOwnRange() {
        SyntheticDb spec = configs().filter(c -> c.name().equals("city, utf8")).findFirst().orElseThrow().db();
        SxGeo db = new SxGeo(spec.build());
        int checked = 0;
        for (long m : spec.mIdx) {
            if (m == 0xFFFFFFFFL) continue;
            assertEquals(spec.expected(m).lookup(), db.lookup(ip(m)), ip(m));
            checked++;
        }
        for (SyntheticDb.Range r : spec.ranges) {
            assertEquals(spec.expected(r.from()).lookup(), db.lookup(ip(r.from())), ip(r.from()));
        }
        assertTrue(checked > 10);
    }

    @Test
    void mainIndexWithoutSentinelIsNotOverrun() {
        // 8 blocks, range_len 4: one m_idx entry, while the only bucket spans m_idx[0..1]
        SyntheticDb spec = new SyntheticDb(false);
        spec.bIdxLen = 2;
        spec.rangeLen = 4;
        spec.split = "never";
        for (int i = 0; i < 8; i++) {
            spec.ranges.add(new SyntheticDb.Range((1L << 24) + i * 0x200000L, (1L << 24) + (i + 1) * 0x200000L - 1, String.valueOf(i + 1)));
        }
        byte[] bytes = spec.build();
        assertEquals(1, spec.mIdx.length);
        SxGeo db = new SxGeo(bytes);
        assertMatches(spec, db, spec.probes());
        assertEquals(OptionalInt.of(8), db.countryId("1.255.255.255"));
        assertEquals(OptionalInt.of(5), db.countryId("1.128.0.0"));
        assertEquals(OptionalInt.empty(), db.countryId("2.0.0.0"));
    }

    @Test
    void addressesBeforeTheFirstBlockAreNotFound() {
        SyntheticDb spec = new SyntheticDb(false);
        spec.fillGaps = false;
        spec.split = "never";
        spec.ranges.add(new SyntheticDb.Range(ip("1.0.0.5"), ip("1.0.0.9"), "7"));
        spec.ranges.add(new SyntheticDb.Range(ip("3.0.1.0"), ip("3.0.1.255"), "9"));
        SxGeo db = new SxGeo(spec.build());
        assertEquals(OptionalInt.empty(), db.countryId("1.0.0.4"));
        assertEquals(OptionalInt.of(7), db.countryId("1.0.0.5"));
        // blocks cover the address space up to the next block: 1.0.0.5 .. 3.0.0.255 is one block
        assertEquals(OptionalInt.of(7), db.countryId("2.0.0.0"));
        assertEquals(OptionalInt.of(7), db.countryId("3.0.0.255"));
        assertEquals(OptionalInt.of(9), db.countryId("3.0.1.0"));
        assertEquals(OptionalInt.of(9), db.countryId("223.255.255.255"));
    }

    // ---------------------------------------------------------------- small hand-made city database

    /** RU and UA records, Kyiv in its region, a city without region, a country-only range. */
    private static SyntheticDb small() {
        SyntheticDb spec = new SyntheticDb(true);
        spec.pack = new String[]{"T:id/c2:iso/n2:lat/n2:lon/b:name_ru/b:name_en/c6:tz", SyntheticDb.CITY_PACK[1],
                "M:region_seek/T:country_id/M:id/N5:lat/N5:lon/b:name_ru/b:name_en/c4:code"};
        spec.countries.put(185, new LinkedHashMap<>(Map.of("id", 185, "iso", "RU", "lat", 60.0, "lon", 100.0,
                "name_ru", "Россия", "name_en", "Russia", "tz", "ab   \n")));
        spec.countries.put(222, new LinkedHashMap<>(Map.of("id", 222, "iso", "UA", "lat", 49.0, "lon", 32.0,
                "name_ru", "Украина", "name_en", "Ukraine", "tz", "  x")));
        spec.regions.put("kyiv", new LinkedHashMap<>(Map.of("@country", 222, "id", 703447, "iso", "UA-30", "name_ru", "Киев", "name_en", "Kyiv")));
        spec.cities.put("kyiv", new LinkedHashMap<>(Map.of("@region", "kyiv", "country_id", 222, "id", 703448, "lat", 50.45466,
                "lon", 30.5238, "name_ru", "Киев", "name_en", "Kyiv", "code", "K \t")));
        spec.cities.put("moscow", new LinkedHashMap<>(Map.of("country_id", 185, "id", 524901, "lat", 55.75222,
                "lon", 37.61556, "name_ru", "Москва", "name_en", "Moscow")));
        spec.ranges.add(new SyntheticDb.Range(ip("1.0.0.0"), ip("1.0.0.255"), "country:185"));
        spec.ranges.add(new SyntheticDb.Range(ip("2.0.0.0"), ip("2.0.0.255"), "city:moscow"));
        spec.ranges.add(new SyntheticDb.Range(ip("93.73.0.0"), ip("93.73.255.255"), "city:kyiv"));
        return spec;
    }

    @Test
    void smallCityDatabase() throws Exception {
        SyntheticDb spec = small();
        SxGeo db = new SxGeo(spec.build());
        assertMatches(spec, db, spec.probes());
        GeoInfo kyiv = db.lookup("93.73.35.74").orElseThrow();
        assertEquals("Ukraine", kyiv.country().nameEn());
        assertEquals("  x", kyiv.country().fields().get("tz"));
        assertEquals("UA-30", kyiv.region().iso());
        assertEquals("Kyiv", kyiv.city().nameEn());
        assertEquals("K \t", kyiv.city().fields().get("code"));
        assertEquals(50.45466, kyiv.city().lat(), 1e-9);
        assertEquals(Map.of("id", 703448, "lat", 50.45466, "lon", 30.5238, "name_ru", "Киев", "name_en", "Kyiv", "code", "K \t"),
                kyiv.city().fields());
        assertThrows(UnsupportedOperationException.class, () -> kyiv.city().fields().put("x", 1));

        // city without region: the full country record, not just id + iso
        GeoInfo moscow = db.lookup("2.0.0.7").orElseThrow();
        assertNull(moscow.region());
        assertEquals(new Country(185, "RU", "Россия", "Russia", 60.0, 100.0,
                Map.of("id", 185, "iso", "RU", "lat", 60.0, "lon", 100.0, "name_ru", "Россия", "name_en", "Russia", "tz", "ab   \n")),
                moscow.country());
        assertEquals("", moscow.city().fields().get("code"));

        GeoInfo countryOnly = db.lookup("1.0.0.0").orElseThrow();
        assertEquals("RU", countryOnly.country().iso());
        assertNull(countryOnly.city());
        assertEquals(Optional.of("RU"), db.country("1.0.0.255"));
        assertEquals(Optional.empty(), db.country("1.0.1.0"));

        Inet4Address address = (Inet4Address) InetAddress.getByAddress(new byte[]{93, 73, 35, 74});
        assertEquals(db.lookup("93.73.35.74"), db.lookup(address));
        assertEquals(Optional.of("UA"), db.country(address));
        assertEquals("SxGeo City", db.info().type());
    }

    @Test
    void regionOfAnotherCountryOrMissingCountryRecord() {
        SyntheticDb spec = small();
        spec.cities.get("kyiv").put("country_id", 185); // region record belongs to UA, the city to RU
        spec.cities.put("nowhere", new LinkedHashMap<>(Map.of("@region", "kyiv", "country_id", 225, "id", 5, "name_en", "X")));
        spec.ranges.add(new SyntheticDb.Range(ip("3.0.0.0"), ip("3.0.0.255"), "city:nowhere"));
        SxGeo db = new SxGeo(spec.build());
        GeoInfo kyiv = db.lookup("93.73.35.74").orElseThrow();
        assertEquals("Russia", kyiv.country().nameEn());
        assertEquals("UA-30", kyiv.region().iso());
        assertEquals(Optional.of("RU"), db.country("93.73.35.74"));
        GeoInfo nowhere = db.lookup("3.0.0.1").orElseThrow();
        assertEquals(new Country(225, "US", "", "", 0, 0, Map.of()), nowhere.country());
        assertEquals(OptionalInt.of(225), db.countryId("3.0.0.1"));
        assertMatches(spec, db, spec.probes());
    }

    // ---------------------------------------------------------------- IP parsing

    @Test
    void ipParsing() {
        SyntheticDb spec = new SyntheticDb(false);
        spec.split = "always";
        spec.ranges.add(new SyntheticDb.Range(ip("1.0.0.0"), ip("223.255.255.255"), "185"));
        SxGeo db = new SxGeo(spec.build());
        for (String ip : new String[]{"8.8.8.8", " 8.8.8.8", "8.8.8.8 ", "\t8.8.8.8\r\n", " \n 1.2.3.4\t", "1.0.0.0", "223.255.255.255",
                "100.200.0.9", "9.9.9.9"}) {
            assertEquals(Optional.of("RU"), db.country(ip), ip);
            assertTrue(db.lookup(ip).isPresent(), ip);
        }
        for (String ip : new String[]{"08.8.8.8", "8.08.8.8", "8.8.8.08", "010.0.0.1", "00.1.2.3", "1.2.3.00", "1.2.3.004",
                "0.0.0.0", "10.1.2.3", "127.0.0.1", "224.0.0.1", "255.255.255.255", "", " ", "1.2.3", "1.2.3.4.5", "256.1.1.1",
                "1.2.3.256", "1000.1.1.1", "-1.2.3.4", "+1.2.3.4", "1..2.3", "1.2.3.", ".1.2.3", "a.b.c.d", "::1", "2001:db8::1",
                "::ffff:1.2.3.4", "example.com", "8.8.8.8, 10.0.0.1", "8.8.8.8/32", "8.8.8.8:80", "8. 8.8.8", "8.8.8.8\0",
                "\u000b8.8.8.8", "\f8.8.8.8", "\u00a08.8.8.8", "8.8.8.8\u2003", "８.8.8.8", "٨.8.8.8", "0x8.8.8.8", "8.8.8.8 x", null}) {
            assertEquals(Optional.empty(), db.country(ip), String.valueOf(ip));
            assertEquals(OptionalInt.empty(), db.countryId(ip), String.valueOf(ip));
            assertEquals(Optional.empty(), db.lookup(ip), String.valueOf(ip));
        }
    }

    // ---------------------------------------------------------------- constructor validation

    @Test
    void rejectsNonSypexGeoData() {
        assertThrows(IllegalArgumentException.class, () -> new SxGeo(new byte[100]));
        assertThrows(IllegalArgumentException.class, () -> new SxGeo("SxG".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class, () -> new SxGeo(new byte[0]));
        byte[] good = small().build();
        new SxGeo(good);
        assertThrows(IllegalArgumentException.class, () -> new SxGeo(patch(good, 3, 21)), "version 2.1");
        assertThrows(IllegalArgumentException.class, () -> new SxGeo(patch(good, 9, 3)), "charset");
        assertThrows(IllegalArgumentException.class, () -> new SxGeo(patch(good, 10, 0)), "b_idx_len");
        assertThrows(IllegalArgumentException.class, () -> new SxGeo(patch(good, 19, 0)), "id_len 0");
        // id_len 5 with enough padding that the file is not truncated: only the header check can reject it
        int dbItems = ByteBuffer.wrap(good, 15, 4).getInt();
        byte[] idLen5 = Arrays.copyOf(patch(good, 19, 5), good.length + dbItems * 2);
        assertEquals("Corrupted Sypex Geo database header",
                assertThrows(IllegalArgumentException.class, () -> new SxGeo(idLen5), "id_len 5").getMessage());
        assertThrows(IllegalArgumentException.class, () -> new SxGeo(patch(good, 4, 0, 0, 0, 0)), "time");
    }

    private static byte[] patch(byte[] data, int pos, int... bytes) {
        byte[] copy = data.clone();
        for (int i = 0; i < bytes.length; i++) copy[pos + i] = (byte) bytes[i];
        return copy;
    }

    private static byte[] patchInt(byte[] data, int pos, long value) {
        return patch(data, pos, (int) (value >> 24), (int) (value >> 16), (int) (value >> 8), (int) value);
    }

    @Test
    void rejectsTruncatedData() {
        for (SyntheticDb spec : List.of(small(), configs().toList().get(7).db(), configs().toList().get(0).db())) {
            byte[] full = spec.build();
            new SxGeo(full);
            // the last bytes are city records: the header counts them in country_size + city_size
            for (int size : new int[]{39, 40, 60, full.length / 2, full.length - spec.countrySize - 1, full.length - 1}) {
                if (size >= 0) assertThrows(IllegalArgumentException.class, () -> new SxGeo(Arrays.copyOf(full, size)), "size " + size);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"X:id", "c:iso", "T2:id", "Tid", ":id", "n2x:lat", "N99999999999:lat", "c99999999999:x", "b1:name",
            "c-1:x", "T :id", "t:a/q:b", "M:region_seek/T:country_id/M:id/N5:lat/N5:lon/b:name_ru/b:name_en/c:code"})
    void rejectsInvalidPackFormats(String cityPack) {
        byte[] good = small().build();
        assertThrows(IllegalArgumentException.class, () -> new SxGeo(withCityPack(good, cityPack)));
    }

    @Test
    void acceptsEmptyPackTokensAndDecimalsWithoutDigits() {
        SyntheticDb spec = small();
        spec.pack[2] = "/M:region_seek//T:country_id/M:id/N5:lat/N:lon/b:name_ru/b:name_en/c4:code/";
        SxGeo db = new SxGeo(spec.build());
        assertMatches(spec, db, spec.probes());
        assertEquals(31.0, db.lookup("93.73.35.74").orElseThrow().city().lon(), 1e-9); // stored as integer 31 (30.5238 rounded)
    }

    /** Replaces the city pack format; the rest of the file moves with it. */
    private static byte[] withCityPack(byte[] data, String cityPack) {
        int packSize = (data[38] & 0xFF) << 8 | data[39] & 0xFF;
        String[] packs = new String(data, 40, packSize, StandardCharsets.ISO_8859_1).split("\0", -1);
        byte[] pack = (packs[0] + "\0" + packs[1] + (cityPack == null ? "" : "\0" + cityPack)).getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer out = ByteBuffer.allocate(data.length - packSize + pack.length);
        out.put(data, 0, 38).putShort((short) pack.length).put(pack).put(data, 40 + packSize, data.length - 40 - packSize);
        return out.array();
    }

    @Test
    void rejectsCityDatabaseWithoutPackFormats() {
        assertThrows(IllegalArgumentException.class, () -> new SxGeo(withCityPack(small().build(), null)));
    }

    @Test
    void rejectsCorruptedFirstByteIndex() {
        SyntheticDb spec = configs().toList().get(0).db();
        byte[] good = spec.build();
        int bIdx = 40 + spec.packSize;
        new SxGeo(good);
        assertThrows(IllegalArgumentException.class, () -> new SxGeo(patchInt(good, bIdx + 5 * 4, spec.bIdx[4] - 1)), "decreasing");
        assertThrows(IllegalArgumentException.class, () -> new SxGeo(patchInt(good, bIdx + 223 * 4, spec.starts.length + 1)), "past db_items");
        assertThrows(IllegalArgumentException.class, () -> new SxGeo(patchInt(good, bIdx + 100 * 4, 0xFFFFFFFFL)), "huge");
    }

    @Test
    void rejectsCorruptedCountryRecords() {
        SyntheticDb spec = small();
        byte[] good = spec.build();
        // the last country record now runs past country_size
        byte[] bad = patchInt(patchInt(good, 34, spec.countrySize - 1), 28, spec.citySize + 1);
        assertThrows(IllegalArgumentException.class, () -> new SxGeo(bad));
    }

    // ---------------------------------------------------------------- corrupted records: IllegalStateException

    @Test
    void corruptedRecordsThrowIllegalStateException() {
        SyntheticDb spec = small();
        byte[] good = spec.build();

        // block id (city seek) past the cities section
        int block = Arrays.binarySearch(spec.starts, ip("93.73.0.0"));
        int seekPos = spec.dbStart + block * 6 + 3;
        byte[] badSeek = patch(good, seekPos, 0x7F, 0xFF, 0xFF);
        assertCorrupted(new SxGeo(badSeek), "93.73.35.74", true);

        // region seek past the regions section: past the end of the file
        int city = spec.citiesStart + spec.citySeek.get("kyiv");
        assertCorrupted(new SxGeo(patch(good, city, 0xFF, 0xFF, 0x00)), "93.73.35.74", false);

        // region seek inside the file, just past the regions section, on Kyiv's city record: decoded as a region,
        // its country_seek would be a valid country record and nothing would fail by itself
        SyntheticDb in = small();
        in.cities.put("inside", new LinkedHashMap<>(Map.of("@region_seek", 0, "country_id", 222, "id", 703449,
                "name_ru", "Внутри", "name_en", "Inside")));
        in.ranges.add(new SyntheticDb.Range(ip("93.74.0.0"), ip("93.74.255.255"), "city:inside"));
        in.build();
        int kyivSeek = in.citySeek.get("kyiv"), regionSize = in.regionSize;
        in.cities.get("inside").put("@region_seek", regionSize + kyivSeek);
        SxGeo inside = new SxGeo(in.build());
        assertEquals(kyivSeek, in.citySeek.get("kyiv"));
        assertEquals(regionSize, in.regionSize);
        assertEquals(in.citiesStart, in.regionsStart + regionSize);
        assertTrue(kyivSeek >= in.countrySize);
        assertTrue(regionSize + kyivSeek < regionSize + in.countrySize + in.citySize);
        assertEquals("Kyiv", inside.lookup("93.73.35.74").orElseThrow().city().nameEn());
        assertCorrupted(inside, "93.74.0.1", false);
        assertEquals(Optional.of("UA"), inside.country("93.74.0.1"));
        assertEquals(OptionalInt.of(222), inside.countryId("93.74.0.1"));

        // the last city record (Moscow) ends with a 'c' field / a 'b' string: cut its last byte off the section
        for (String pack : new String[]{"M:region_seek/T:country_id/M:id/b:name_en/c40:code", "M:region_seek/T:country_id/M:id/b:name_en"}) {
            SyntheticDb c = small();
            c.pack[2] = pack;
            byte[] full = c.build();
            assertTrue(c.citySeek.get("moscow") > c.citySeek.get("kyiv"));
            SxGeo db = new SxGeo(Arrays.copyOf(patchInt(full, 28, c.citySize - 1), full.length - 1));
            assertCorrupted(db, "2.0.0.1", true);
            assertEquals("Kyiv", db.lookup("93.73.35.74").orElseThrow().city().nameEn());
        }

        // region pointing past the country records: far out, and to a city record (inside the cities section)
        SyntheticDb r = small();
        r.regions.get("kyiv").put("@country_seek", 5000);
        assertCorrupted(new SxGeo(r.build()), "93.73.35.74", false);
        r.regions.get("kyiv").put("@country_seek", r.citySeek.get("kyiv"));
        SxGeo intoCities = new SxGeo(r.build());
        assertTrue(r.citySeek.get("kyiv") >= r.countrySize);
        assertCorrupted(intoCities, "93.73.35.74", false);
        assertEquals(Optional.of("UA"), intoCities.country("93.73.35.74"));
        assertEquals(OptionalInt.of(222), intoCities.countryId("93.73.35.74"));
    }

    @Test
    void negativeBlockIdIsCorruptedInCityDatabase() {
        // id_len 4 with the high bit set: a negative city offset is corrupted, not "not found"
        SyntheticDb spec = small();
        spec.idLen = 4;
        byte[] good = spec.build();
        assertEquals("Kyiv", new SxGeo(good).lookup("93.73.35.74").orElseThrow().city().nameEn());
        int block = Arrays.binarySearch(spec.starts, ip("93.73.0.0"));
        byte[] bad = good.clone();
        bad[spec.dbStart + block * 7 + 3] |= (byte) 0x80;
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new SxGeo(bad).lookup("93.73.35.74"));
        assertTrue(e.getMessage().startsWith("Corrupted Sypex Geo database"), e.getMessage());
        assertCorrupted(new SxGeo(bad), "93.73.35.74", true);
        assertEquals("Moscow", new SxGeo(bad).lookup("2.0.0.1").orElseThrow().city().nameEn());

        // in the country database the block id is the country id: a negative one is just unknown
        SyntheticDb country = new SyntheticDb(false);
        country.idLen = 4;
        country.ranges.add(new SyntheticDb.Range(ip("93.73.0.0"), ip("93.73.255.255"), "222"));
        byte[] cGood = country.build();
        assertEquals(Optional.of("UA"), new SxGeo(cGood).country("93.73.35.74"));
        int cBlock = Arrays.binarySearch(country.starts, ip("93.73.0.0"));
        byte[] cBad = cGood.clone();
        cBad[country.dbStart + cBlock * 7 + 3] |= (byte) 0x80;
        SxGeo cDb = new SxGeo(cBad);
        assertEquals(Optional.empty(), cDb.country("93.73.35.74"));
        assertEquals(OptionalInt.empty(), cDb.countryId("93.73.35.74"));
        assertEquals(Optional.empty(), cDb.lookup("93.73.35.74"));
    }

    private static void assertCorrupted(SxGeo db, String ip, boolean countryToo) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> db.lookup(ip));
        assertTrue(e.getMessage().startsWith("Corrupted Sypex Geo database"), e.getMessage());
        if (countryToo) {
            assertThrows(IllegalStateException.class, () -> db.country(ip));
            assertThrows(IllegalStateException.class, () -> db.countryId(ip));
        } else {
            db.country(ip);
        }
    }

    @Test
    void randomCorruptionNeverThrowsAnythingElse() {
        SyntheticDb spec = configs().toList().get(9).db();
        byte[] good = spec.build();
        List<String> probes = spec.probes().stream().filter(x -> x % 7 == 0).map(SyntheticDb::ip).toList();
        Random random = new Random(1);
        for (int round = 0; round < 300; round++) {
            byte[] bad = good.clone();
            for (int i = 0; i < 1 + random.nextInt(8); i++) bad[40 + random.nextInt(bad.length - 40)] = (byte) random.nextInt(256);
            if (round % 3 == 0) bad[11 + random.nextInt(29)] = (byte) random.nextInt(256); // header fields
            SxGeo db;
            try {
                db = new SxGeo(bad);
            } catch (IllegalArgumentException e) {
                continue;
            }
            for (String ip : probes) {
                try {
                    db.lookup(ip);
                    db.country(ip);
                } catch (IllegalStateException e) {
                    assertTrue(e.getMessage().startsWith("Corrupted Sypex Geo database"), e.getMessage());
                }
            }
        }
    }

    // ---------------------------------------------------------------- immutability

    @Test
    void opensFiles(@TempDir Path dir) throws IOException {
        byte[] data = small().build();
        Path file = Files.write(dir.resolve("SxGeoCity.dat"), data);
        Optional<GeoInfo> fromFile = new SxGeo(file).lookup("93.73.35.74");
        assertEquals(new SxGeo(data).lookup("93.73.35.74"), fromFile);
        assertEquals("Kyiv", fromFile.orElseThrow().city().nameEn());
        assertThrows(NoSuchFileException.class, () -> new SxGeo(dir.resolve("missing.dat")));
    }

    @Test
    void byteArrayIsCopied() {
        SyntheticDb spec = small();
        byte[] data = spec.build();
        SxGeo db = new SxGeo(data);
        Optional<GeoInfo> before = db.lookup("93.73.35.74");
        Arrays.fill(data, (byte) 0);
        assertEquals(before, db.lookup("93.73.35.74"));
        assertEquals("Kyiv", before.orElseThrow().city().nameEn());
    }
}
