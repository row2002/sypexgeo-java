package io.github.row2002.sypexgeo;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds synthetic Sypex Geo 2.2 databases in memory and knows the correct answer for every IP
 * (brute force over the source ranges). Layout as in the real files: see the verification harness {@code mkdb.py}.
 */
final class SyntheticDb {

    static final List<String> ISO = List.of((" AP EU AD AE AF AG AI AL AM CW AO AQ AR AS AT AU AW AZ BA BB BD BE BF BG BH BI BJ BM"
            + " BN BO BR BS BT BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER"
            + " ES ET FI FJ FK FM FO FR SX GA GB GD GE GF GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IN IO"
            + " IQ IR IS IT JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD MG MH MK ML MM MN"
            + " MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW"
            + " PY QA RE RO RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR ST SV SY SZ TC TD TF TG TH TJ TK TM TN TO TL TR TT"
            + " TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT RS ZA ZM ME ZW A1 XK O1 AX GG IM JE BL MF BQ SS").split(" "));

    /** Pack formats of the real SxGeoCity.dat. */
    static final String[] CITY_PACK = {
            "T:id/c2:iso/n2:lat/n2:lon/b:name_ru/b:name_en",
            "S:country_seek/M:id/b:name_ru/b:name_en/b:iso",
            "M:region_seek/T:country_id/M:id/N5:lat/N5:lon/b:name_ru/b:name_en"};
    /** Every pack code SxGeo.php knows, plus an empty token and {@code n} without decimals. */
    static final String[] MAX_PACK = {
            "T:id/c2:iso/n2:lat/n2:lon/b:name_ru/b:name_en/c6:timezone/I:population/t:t8/s:s16/d:area/",
            "S:country_seek/M:id/b:name_ru/b:name_en/b:iso/c10:okato/m:m24/i:i32",
            "M:region_seek/T:country_id/M:id/N5:lat/N5:lon/b:name_ru/b:name_en/f:f32/n1:alt/S:u16/I:u32/n:level//"};
    static final Set<String> INTERNAL = Set.of("region_seek", "country_seek", "country_id");

    record Range(long from, long to, String target) {
    }

    // ---------------------------------------------------------------- spec
    boolean city;
    int charset, type, rangeLen = 16, bIdxLen = 224, idLen;
    String split = "always"; // always (every first octet starts at X.0.0.0) | empty_only | never
    boolean sentinel, fillGaps = true;
    String[] pack = CITY_PACK;
    /**
     * country id -> fields; region key -> fields + "@country" (country id) or a raw "@country_seek";
     * city key -> fields + "@region" (key) or a raw "@region_seek"
     */
    final Map<Integer, Map<String, Object>> countries = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> regions = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> cities = new LinkedHashMap<>();
    /** country DB: target = country id; city DB: "city:KEY", "country:ID" or "raw:SEEK" */
    final List<Range> ranges = new ArrayList<>();

    // ---------------------------------------------------------------- built
    final Map<Integer, Integer> countrySeek = new LinkedHashMap<>();
    final Map<String, Integer> regionSeek = new LinkedHashMap<>(), citySeek = new LinkedHashMap<>();
    long[] starts, mIdx;
    int[] ids, bIdx;
    int packSize, regionSize, countrySize, citySize, dbStart, regionsStart, citiesStart;
    private List<Range> sorted;

    SyntheticDb(boolean city) {
        this.city = city;
        charset = city ? 0 : 1;
        type = city ? 4 : 1;
        idLen = city ? 3 : 1;
        sentinel = city;
        if (!city) split = "empty_only";
    }

    Charset cs() {
        return switch (charset) {
            case 0 -> StandardCharsets.UTF_8;
            case 1 -> StandardCharsets.ISO_8859_1;
            default -> Charset.forName("windows-1251");
        };
    }

    // ---------------------------------------------------------------- records
    static List<String[]> fields(String pack) {
        List<String[]> out = new ArrayList<>();
        for (String token : pack.split("/")) {
            if (!token.isEmpty()) out.add(token.split(":", 2));
        }
        return out;
    }

    private static double scale(String code) {
        return Math.pow(10, code.length() > 1 ? Integer.parseInt(code.substring(1)) : 0);
    }

    private static Object valueOf(Map<String, Object> rec, String code, String name) {
        Object v = rec.get(name);
        return v != null ? v : code.charAt(0) == 'c' || code.charAt(0) == 'b' ? "" : 0;
    }

    private byte[] encode(String code, Object v) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        Number n = v instanceof Number num ? num : 0;
        int size = switch (code.charAt(0)) {
            case 't', 'T' -> 1;
            case 's', 'S', 'n' -> 2;
            case 'm', 'M' -> 3;
            case 'd' -> 8;
            default -> 4;
        };
        switch (code.charAt(0)) {
            case 't', 'T' -> b.put((byte) n.intValue());
            case 's', 'S' -> b.putShort((short) n.intValue());
            case 'n' -> b.putShort((short) Math.round(n.doubleValue() * scale(code)));
            case 'm', 'M', 'i', 'I' -> b.putInt((int) n.longValue());
            case 'N' -> b.putInt((int) Math.round(n.doubleValue() * scale(code)));
            case 'f' -> b.putFloat(n.floatValue());
            case 'd' -> b.putDouble(n.doubleValue());
            case 'c' -> {
                byte[] s = Arrays.copyOf(((String) v).getBytes(cs()), Integer.parseInt(code.substring(1)));
                for (int i = ((String) v).getBytes(cs()).length; i < s.length; i++) s[i] = ' ';
                return s;
            }
            default -> { // 'b'
                byte[] s = ((String) v).getBytes(cs());
                return Arrays.copyOf(s, s.length + 1);
            }
        }
        return Arrays.copyOf(b.array(), size);
    }

    /** What a correct reader returns for a value written by {@link #encode}. */
    private Object decoded(String code, Object v) {
        Number n = v instanceof Number num ? num : 0;
        return switch (code.charAt(0)) {
            case 'I' -> n.longValue() & 0xFFFFFFFFL;
            case 'f' -> n.floatValue();
            case 'd' -> n.doubleValue();
            case 'n' -> (short) Math.round(n.doubleValue() * scale(code)) / scale(code);
            case 'N' -> (int) Math.round(n.doubleValue() * scale(code)) / scale(code);
            case 'c' -> rtrim((String) v);
            case 'b' -> v;
            default -> n.intValue();
        };
    }

    /** Strips trailing spaces only, like PHP {@code rtrim($s, ' ')}. */
    static String rtrim(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') end--;
        return s.substring(0, end);
    }

    private byte[] record(int kind, Map<String, Object> rec, Map<String, Object> links) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String[] f : fields(pack[kind])) {
            out.writeBytes(encode(f[0], links.containsKey(f[1]) ? links.get(f[1]) : valueOf(rec, f[0], f[1])));
        }
        return out.toByteArray();
    }

    /** Decoded fields without internal links, as {@code Country/Region/City.fields()}. */
    Map<String, Object> publicFields(int kind, Map<String, Object> rec) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String[] f : fields(pack[kind])) {
            if (!INTERNAL.contains(f[1])) out.put(f[1], decoded(f[0], valueOf(rec, f[0], f[1])));
        }
        return out;
    }

    // ---------------------------------------------------------------- build
    byte[] build() {
        sorted = new ArrayList<>(ranges);
        sorted.sort((a, b) -> Long.compare(a.from, b.from));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).from <= sorted.get(i - 1).to) throw new IllegalArgumentException("overlapping ranges");
        }
        ByteArrayOutputStream regionBytes = new ByteArrayOutputStream(), cityBytes = new ByteArrayOutputStream();
        int maxCountry = 0, maxRegion = 0, maxCity = 0;
        if (city) {
            cityBytes.write(0);
            for (var e : countries.entrySet()) {
                countrySeek.put(e.getKey(), cityBytes.size());
                byte[] r = record(0, e.getValue(), Map.of());
                cityBytes.writeBytes(r);
                maxCountry = Math.max(maxCountry, r.length);
            }
            countrySize = cityBytes.size();
            regionBytes.write(0);
            for (var e : regions.entrySet()) {
                regionSeek.put(e.getKey(), regionBytes.size());
                Object override = e.getValue().get("@country_seek");
                int seek = override != null ? (int) override : countrySeek.getOrDefault((Integer) e.getValue().get("@country"), 0);
                byte[] r = record(1, e.getValue(), Map.of("country_seek", seek));
                regionBytes.writeBytes(r);
                maxRegion = Math.max(maxRegion, r.length);
            }
            regionSize = regionBytes.size();
            for (var e : cities.entrySet()) {
                citySeek.put(e.getKey(), cityBytes.size());
                Object region = e.getValue().get("@region"), override = e.getValue().get("@region_seek");
                int seek = override != null ? (int) override : region == null ? 0 : regionSeek.get((String) region);
                byte[] r = record(2, e.getValue(), Map.of("region_seek", seek));
                cityBytes.writeBytes(r);
                maxCity = Math.max(maxCity, r.length);
            }
            citySize = cityBytes.size() - countrySize; // like the real files: country records are not counted
        }
        blocks();

        byte[] packBytes = city ? String.join("\0", pack).getBytes(StandardCharsets.ISO_8859_1) : new byte[0];
        packSize = packBytes.length;
        int n = starts.length, blockLen = 3 + idLen;
        ByteBuffer h = ByteBuffer.allocate(40);
        h.put("SxG".getBytes(StandardCharsets.US_ASCII)).put((byte) 22).putInt(1_700_000_000).put((byte) type).put((byte) charset)
                .put((byte) bIdxLen).putShort((short) mIdx.length).putShort((short) rangeLen).putInt(n).put((byte) idLen)
                .putShort((short) maxRegion).putShort((short) maxCity).putInt(regionSize).putInt(citySize)
                .putShort((short) maxCountry).putInt(countrySize).putShort((short) packSize);
        ByteBuffer out = ByteBuffer.allocate(40 + packSize + (bIdxLen + mIdx.length) * 4 + n * blockLen + regionSize + cityBytes.size());
        out.put(h.array()).put(packBytes);
        for (int b : bIdx) out.putInt(b);
        for (long m : mIdx) out.putInt((int) m);
        dbStart = out.position();
        for (int i = 0; i < n; i++) {
            out.put((byte) (starts[i] >> 16)).put((byte) (starts[i] >> 8)).put((byte) starts[i]);
            for (int k = idLen - 1; k >= 0; k--) out.put((byte) (ids[i] >> (8 * k)));
        }
        regionsStart = out.position();
        out.put(regionBytes.toByteArray());
        citiesStart = out.position();
        out.put(cityBytes.toByteArray());
        return out.array();
    }

    private int targetId(String target) {
        if (!city) return Integer.parseInt(target);
        String key = target.substring(target.indexOf(':') + 1);
        return switch (target.substring(0, target.indexOf(':'))) {
            case "city" -> citySeek.get(key);
            case "country" -> countrySeek.get(Integer.parseInt(key));
            default -> Integer.parseInt(key);
        };
    }

    private void blocks() {
        long lo = 1L << 24, hi = ((long) bIdxLen << 24) - 1; // first octets 1 .. bIdxLen-1 are addressable
        List<long[]> segs = new ArrayList<>(); // start, end, id
        long pos = lo;
        for (Range r : sorted) {
            long a = Math.max(r.from, lo), b = Math.min(r.to, hi);
            if (a > b) continue;
            if (a > pos && fillGaps) segs.add(new long[]{pos, a - 1, 0});
            segs.add(new long[]{a, b, targetId(r.target)});
            pos = b + 1;
        }
        if (pos <= hi && fillGaps) segs.add(new long[]{pos, hi, 0});
        List<long[]> merged = new ArrayList<>(); // adjacent segments with the same id become one
        for (long[] s : segs) {
            long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last[2] == s[2] && last[1] + 1 == s[0]) last[1] = s[1];
            else merged.add(s);
        }
        segs = merged;
        List<long[]> blocks = new ArrayList<>(); // start, id
        for (long[] s : segs) {
            if (split.equals("always")) {
                for (long cur = s[0]; cur <= s[1]; cur = ((cur >> 24) + 1) << 24) blocks.add(new long[]{cur, s[2]});
            } else {
                blocks.add(new long[]{s[0], s[2]});
            }
        }
        if (split.equals("empty_only")) { // octets that would have no block get one at X.0.0.0
            List<long[]> filled = new ArrayList<>();
            for (int k = 0; k < blocks.size(); k++) {
                long next = k + 1 < blocks.size() ? blocks.get(k + 1)[0] : hi + 1;
                filled.add(blocks.get(k));
                for (long o = (blocks.get(k)[0] >> 24) + 1; o < Math.min(next >> 24, (hi >> 24) + 1); o++) {
                    filled.add(new long[]{o << 24, blocks.get(k)[1]});
                }
            }
            blocks = filled;
        }
        int n = blocks.size();
        if (blocks.stream().anyMatch(b -> b[1] >= 1L << (8 * idLen))) throw new IllegalArgumentException("id does not fit id_len");
        starts = blocks.stream().mapToLong(b -> b[0]).toArray();
        ids = blocks.stream().mapToInt(b -> (int) b[1]).toArray();
        bIdx = new int[bIdxLen];
        for (int f = 0, k = 0; f < bIdxLen; f++) {
            while (k < n && (starts[k] >> 24) <= f) k++;
            bIdx[f] = k;
        }
        // m_idx[i] = first IP of block (i + 1) * rangeLen
        long[] m = new long[(n - 1) / rangeLen];
        for (int i = 0; i < m.length; i++) m[i] = starts[(i + 1) * rangeLen];
        mIdx = sentinel || m.length == 0 ? Arrays.copyOf(m, m.length + 1) : m;
        if (mIdx.length > m.length) mIdx[m.length] = 0xFFFFFFFFL;
    }

    // ---------------------------------------------------------------- expectations
    record Expected(OptionalInt countryId, Optional<String> country, Optional<GeoInfo> lookup) {
    }

    private static final Expected NONE = new Expected(OptionalInt.empty(), Optional.empty(), Optional.empty());

    static String ip(long ip) {
        return (ip >> 24 & 255) + "." + (ip >> 16 & 255) + "." + (ip >> 8 & 255) + "." + (ip & 255);
    }

    private static Optional<String> iso(int id) {
        return id > 0 && id < ISO.size() ? Optional.of(ISO.get(id)) : Optional.empty();
    }

    private static int intOf(Map<String, Object> m, String key) {
        return m.get(key) instanceof Number n ? n.intValue() : 0;
    }

    private static double doubleOf(Map<String, Object> m, String key) {
        return m.get(key) instanceof Number n ? n.doubleValue() : 0;
    }

    private static String stringOf(Map<String, Object> m, String key) {
        return m.get(key) instanceof String s ? s : "";
    }

    Country country(int id) {
        Map<String, Object> f = publicFields(0, countries.get(id));
        return new Country(intOf(f, "id"), stringOf(f, "iso"), stringOf(f, "name_ru"), stringOf(f, "name_en"),
                doubleOf(f, "lat"), doubleOf(f, "lon"), f);
    }

    /** Correct answer: the source range containing the IP. */
    Expected expected(long ip) {
        int first = (int) (ip >> 24);
        if (first == 0 || first == 10 || first == 127 || first >= bIdxLen) return NONE;
        Range range = null;
        for (Range r : sorted) {
            if (r.from <= ip && ip <= r.to) range = r;
        }
        if (range == null || targetId(range.target) == 0) return NONE;
        if (!city) {
            int id = targetId(range.target);
            Country c = new Country(id, iso(id).orElse(""), "", "", 0, 0, Map.of());
            return new Expected(OptionalInt.of(id), iso(id), Optional.of(new GeoInfo(c, null, null)));
        }
        String key = range.target.substring(range.target.indexOf(':') + 1);
        if (range.target.startsWith("country:")) {
            Country c = country(Integer.parseInt(key));
            if (c.id() <= 0) return NONE;
            return new Expected(OptionalInt.of(c.id()), iso(c.id()), Optional.of(new GeoInfo(c, null, null)));
        }
        Map<String, Object> rec = cities.get(key);
        if (rec.get("@region_seek") != null) throw new IllegalStateException("no expectation for region_seek overrides");
        int countryId = intOf(rec, "country_id");
        Map<String, Object> region = rec.get("@region") == null ? null : regions.get((String) rec.get("@region"));
        Country country = null;
        Region r = null;
        if (region != null) {
            Object regionCountry = region.get("@country");
            if (region.get("@country_seek") != null) throw new IllegalStateException("no expectation for country_seek overrides");
            if (regionCountry != null && (int) regionCountry == countryId && countries.containsKey(countryId)) country = country(countryId);
            Map<String, Object> f = publicFields(1, region);
            r = new Region(intOf(f, "id"), stringOf(f, "iso"), stringOf(f, "name_ru"), stringOf(f, "name_en"), f);
        }
        if (country == null) { // no region / region of another country: the city's country, full record if present
            country = countries.containsKey(countryId) ? country(countryId) : new Country(countryId, iso(countryId).orElse(""), "", "", 0, 0, Map.of());
        }
        Map<String, Object> f = publicFields(2, rec);
        City c = new City(intOf(f, "id"), stringOf(f, "name_ru"), stringOf(f, "name_en"), doubleOf(f, "lat"), doubleOf(f, "lon"), f);
        return new Expected(countryId > 0 ? OptionalInt.of(countryId) : OptionalInt.empty(), iso(countryId),
                Optional.of(new GeoInfo(country, r, c)));
    }

    /** start-1/start/start+1/end/end+1 of every range, every block start and m_idx entry +-1, first-octet edges. */
    List<Long> probes() {
        TreeSet<Long> pts = new TreeSet<>();
        for (Range r : ranges) {
            for (long d = -1; d <= 1; d++) {
                pts.add(r.from + d);
                pts.add(r.to + d);
            }
        }
        for (long s : starts) for (long d = -1; d <= 1; d++) pts.add(s + d);
        for (long m : mIdx) for (long d = -1; d <= 1; d++) pts.add(m + d);
        for (long o = 0; o < 256; o++) {
            for (long x : new long[]{(o << 24) - 1, o << 24, (o << 24) + 1, (o << 24) | 0x7FFFFF}) pts.add(x);
        }
        pts.removeIf(x -> x < 0 || x > 0xFFFFFFFFL);
        return List.copyOf(pts);
    }

    // ---------------------------------------------------------------- random databases
    private static final String[] RU = {"Москва", "Санкт-Петербург", "Новосибирск", "Екатеринбург", "Казань", "Самара", "Омск", "Уфа"};
    private static final String[] EN = {"Moscow", "Saint Petersburg", "Novosibirsk", "Yekaterinburg", "Kazan", "Samara", "Omsk", "Ufa"};
    private static final String[] LATIN = {"Zürich", "Genève", "Málaga", "Köln", "Ålesund", "São Paulo", "Montréal", "Kraków"};

    private String name(int i, boolean ru) {
        return (charset == 1 ? LATIN : ru ? RU : EN)[i % 8] + " " + i;
    }

    static long ip(String s) {
        long r = 0;
        for (String p : s.split("\\.")) r = r << 8 | Integer.parseInt(p);
        return r;
    }

    /**
     * Random ranges (and records for a city DB) in several first octets, octet 5 with far more than
     * rangeLen blocks, plus handcrafted edge ranges. Call after setting charset and idLen; returns this.
     */
    SyntheticDb generate(long seed, int count, boolean maxPack) {
        Random rnd = new Random(seed);
        if (maxPack) {
            pack = MAX_PACK;
            type = 7;
        }
        List<long[]> rs = new ArrayList<>();
        String[][] hand = {{"1.0.0.0", "1.0.0.255"}, {"6.0.0.0", "6.255.255.255"}, {"11.200.0.0", "12.0.15.255"},
                {"7.0.0.0", "8.255.255.255"}, {"223.255.255.0", "223.255.255.255"}, {"224.0.0.0", "224.0.0.255"},
                {"10.1.2.0", "10.1.2.255"}, {"127.0.0.0", "127.0.0.255"}, {"9.1.0.0", "9.1.0.0"}};
        for (String[] h : hand) rs.add(new long[]{ip(h[0]), ip(h[1])});
        int[] octets = {1, 2, 3, 5, 6, 7, 9, 10, 11, 12, 13, 50, 77, 126, 127, 128, 200, 222, 223};
        int rest = count - hand.length;
        for (int o : octets) {
            double share = o == 5 ? 0.30 : o == 77 ? 0.12 : 0.58 / (octets.length - 2);
            int cnt = Math.max(2, (int) (rest * share));
            long base = (long) o << 24, loOff = o == 11 || o == 12 ? 0x010000 : 0;
            TreeSet<Long> pts = new TreeSet<>();
            while (pts.size() < 2 * cnt) pts.add(base + loOff + 0x100 + rnd.nextInt((int) (0xFFFF00 - loOff - 0x100)));
            Long[] p = pts.toArray(new Long[0]);
            for (int i = 0; i + 1 < p.length; i += 2) rs.add(new long[]{p[i], rnd.nextDouble() < 0.15 ? p[i] : p[i + 1]});
        }
        rs.sort((a, b) -> Long.compare(a[0], b[0]));
        List<long[]> clean = new ArrayList<>();
        for (long[] r : rs) {
            if (clean.isEmpty() || r[0] > clean.get(clean.size() - 1)[1]) clean.add(r);
        }
        for (int i = 0; i + 1 < clean.size(); i++) { // some adjacent ranges (end + 1 == next start)
            if (rnd.nextDouble() < 0.25 && clean.get(i)[1] + 1 < clean.get(i + 1)[0]) clean.get(i)[1] = clean.get(i + 1)[0] - 1;
        }
        if (!city) {
            int maxId = Math.min(ISO.size() + 2, (1 << (8 * idLen)) - 1); // a few ids beyond the ISO table
            for (long[] r : clean) ranges.add(new Range(r[0], r[1], String.valueOf(1 + rnd.nextInt(maxId))));
            return this;
        }
        List<Integer> countryIds = new ArrayList<>();
        while (countryIds.size() < 12) {
            int id = 1 + rnd.nextInt(ISO.size() - 1);
            if (!countryIds.contains(id)) countryIds.add(id);
        }
        for (int k = 0; k < countryIds.size(); k++) {
            int id = countryIds.get(k);
            countries.put(id, new LinkedHashMap<>(Map.of("id", id, "iso", ISO.get(id), "lat", rnd.nextInt(17800) / 100.0 - 89,
                    "lon", rnd.nextInt(35800) / 100.0 - 179, "name_ru", name(k, true), "name_en", name(k, false),
                    "timezone", k == 0 ? "ab   \n" : "Eu/X" + k, "population", rnd.nextLong() & 0xFFFFFFFFL,
                    "t8", rnd.nextInt(256) - 128, "s16", rnd.nextInt(65536) - 32768)));
            countries.get(id).put("area", rnd.nextDouble() * 2e6 - 1e6);
        }
        for (int k = 0; k < 25; k++) {
            int c = countryIds.get(rnd.nextInt(countryIds.size()));
            regions.put("r" + k, new LinkedHashMap<>(Map.of("@country", c, "id", 1 + rnd.nextInt((1 << 24) - 1),
                    "iso", String.format("%s-%02d", ISO.get(c), k), "name_ru", name(k + 3, true), "name_en", name(k + 3, false),
                    "okato", k == 0 ? " lead\t" : String.valueOf(rnd.nextInt(1_000_000_000)),
                    "m24", rnd.nextInt(1 << 24) - (1 << 23), "i32", rnd.nextInt())));
        }
        int absent = 1;
        while (countryIds.contains(absent)) absent++;
        List<String> regionKeys = List.copyOf(regions.keySet());
        for (int k = 0; k < 60; k++) {
            String region = regionKeys.get(rnd.nextInt(regionKeys.size()));
            int countryId = (int) regions.get(region).get("@country");
            if (k == 1) countryId = countryIds.get(0).equals(countryId) ? countryIds.get(1) : countryIds.get(0); // region of another country
            if (k == 2) countryId = absent; // no record for the city's country
            Map<String, Object> c = new LinkedHashMap<>(Map.of("country_id", countryId, "id", 1 + rnd.nextInt((1 << 24) - 1),
                    "lat", Math.round((rnd.nextDouble() * 178 - 89) * 1e5) / 1e5, "lon", Math.round((rnd.nextDouble() * 358 - 179) * 1e5) / 1e5,
                    "name_ru", name(k + 7, true), "name_en", name(k + 7, false), "f32", (float) (rnd.nextDouble() * 2000 - 1000),
                    "alt", Math.round(rnd.nextDouble() * 60000 - 30000) / 10.0, "u16", rnd.nextInt(65536), "u32", rnd.nextLong() & 0xFFFFFFFFL));
            c.put("level", rnd.nextInt(30000));
            if (k != 0) c.put("@region", region); // city without region
            cities.put("c" + k, c);
        }
        for (int i = 0; i < clean.size(); i++) {
            String target = rnd.nextDouble() < 0.2 ? "country:" + countryIds.get(rnd.nextInt(countryIds.size()))
                    : "city:c" + rnd.nextInt(cities.size());
            if (i >= 1 && i <= 3) target = "city:c" + (i - 1); // the special cities are always used
            ranges.add(new Range(clean.get(i)[0], clean.get(i)[1], target));
        }
        return this;
    }
}
