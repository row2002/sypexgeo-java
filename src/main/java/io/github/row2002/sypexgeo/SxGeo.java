package io.github.row2002.sypexgeo;

import java.io.IOException;
import java.net.Inet4Address;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Reader for Sypex Geo 2.2 databases ({@code SxGeo.dat}, {@code SxGeoCity.dat}, {@code SxGeoMax.dat}).
 *
 * <p>The whole file is kept in memory; instances are immutable and thread-safe.
 * Port of the official {@code SxGeo.php} 2.2.3, except that an IP equal to the first address of a range
 * resolves to that range (SxGeo.php sometimes answers with the previous one).
 *
 * <p>IP strings are dotted-decimal IPv4: leading and trailing ASCII whitespace ({@code ' '}, {@code '\t'},
 * {@code '\n'}, {@code '\r'}) is ignored, then exactly four decimal octets 0-255 of 1-3 ASCII digits without
 * leading zeros are required ({@code "08.8.8.8"} is rejected, like PHP {@code ip2long}). Anything else,
 * including {@code null}, IPv6 and reserved addresses ({@code 0.*}, {@code 10.*}, {@code 127.*}),
 * gives an empty result.
 *
 * <p>The constructors check the header, the indexes and the record formats and throw
 * {@link IllegalArgumentException} for data that is not a valid Sypex Geo 2.2 database.
 * Lookups never throw for a valid database; if a record turns out to be corrupted
 * (it points outside its section or runs past its end), they throw {@link IllegalStateException}.
 */
public final class SxGeo {

    private static final int HEADER_SIZE = 40;
    private static final int COUNTRY = 0, REGION = 1, CITY = 2;
    private static final String[] RECORD_NAMES = {"country", "region", "city"};
    private static final String PACK_CODES = "tTsSmMiIfdcnNb";
    private static final Set<String> INTERNAL_KEYS = Set.of("region_seek", "country_seek", "country_id");

    /** One field of a pack format, e.g. {@code N5:lat}; {@code size} is 0 for NUL-terminated strings. */
    private record Field(char code, int size, double scale, String name) {
    }

    private final byte[] data;
    private final DbInfo info;
    private final int rangeLen, bIdxLen, mIdxLen, dbItems, idLen, blockLen;
    private final int maxRegion, maxCity, maxCountry, regionSize, countrySize, citySize;
    private final Charset charset;
    private final List<List<Field>> packs;
    private final int bIdxStart, mIdxStart, dbStart, regionsStart, citiesStart;
    private final Map<Integer, Integer> countrySeeks;

    /**
     * Opens a database file; it is read into memory once.
     *
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the file is not a Sypex Geo 2.2 database, is corrupted or truncated
     */
    public SxGeo(Path path) throws IOException {
        this(Files.readAllBytes(path), 0);
    }

    /**
     * Reads a database from bytes, e.g. a classpath resource. The array is copied.
     *
     * @throws IllegalArgumentException if the data is not a Sypex Geo 2.2 database, is corrupted or truncated
     */
    public SxGeo(byte[] data) {
        this(data.clone(), 0);
    }

    /** Takes ownership of {@code data}. */
    private SxGeo(byte[] data, int owned) {
        this.data = data;
        if (data.length < HEADER_SIZE || !new String(data, 0, 3, StandardCharsets.ISO_8859_1).equals("SxG")) {
            throw new IllegalArgumentException("Not a Sypex Geo database");
        }
        int version = u8(3);
        if (version != 22) {
            throw new IllegalArgumentException("Unsupported Sypex Geo database version " + version / 10 + "." + version % 10 + ", need 2.2");
        }

        long time = u32(4);
        int type = u8(8);
        bIdxLen = u8(10);
        mIdxLen = u16(11);
        rangeLen = u16(13);
        dbItems = (int) u32(15);
        idLen = u8(19);
        maxRegion = u16(20);
        maxCity = u16(22);
        regionSize = (int) u32(24);
        citySize = (int) u32(28);
        maxCountry = u16(32);
        countrySize = (int) u32(34);
        int packSize = u16(38);
        if (bIdxLen == 0 || mIdxLen == 0 || rangeLen == 0 || dbItems <= 0 || time == 0 || idLen == 0 || idLen > 4
                || regionSize < 0 || citySize < 0 || countrySize < 0) {
            throw new IllegalArgumentException("Corrupted Sypex Geo database header");
        }
        // the cities section holds the country records followed by the city records
        if (data.length < (long) HEADER_SIZE + packSize + (bIdxLen + mIdxLen) * 4L
                + (long) dbItems * (3 + idLen) + regionSize + countrySize + citySize) {
            throw new IllegalArgumentException("Truncated Sypex Geo database");
        }

        charset = switch (u8(9)) {
            case 0 -> StandardCharsets.UTF_8;
            case 1 -> StandardCharsets.ISO_8859_1;
            case 2 -> Charset.forName("windows-1251");
            default -> throw new IllegalArgumentException("Unknown database charset " + u8(9));
        };
        blockLen = 3 + idLen;
        bIdxStart = HEADER_SIZE + packSize;
        mIdxStart = bIdxStart + bIdxLen * 4;
        dbStart = mIdxStart + mIdxLen * 4;
        regionsStart = dbStart + dbItems * blockLen;
        citiesStart = regionsStart + regionSize;
        for (int i = 0; i < bIdxLen; i++) {
            if (u32(bIdxStart + i * 4) > dbItems || i > 0 && u32(bIdxStart + i * 4) < u32(bIdxStart + (i - 1) * 4)) {
                throw new IllegalArgumentException("Corrupted Sypex Geo database index");
            }
        }

        String[] pack = packSize > 0 ? new String(data, HEADER_SIZE, packSize, StandardCharsets.ISO_8859_1).split("\0", -1) : new String[0];
        if (maxCity != 0 && pack.length < 3) {
            throw new IllegalArgumentException("City database without pack formats");
        }
        packs = maxCity == 0 ? List.of() : List.of(parsePack(pack[COUNTRY]), parsePack(pack[REGION]), parsePack(pack[CITY]));
        countrySeeks = maxCity == 0 || maxCountry == 0 ? Map.of() : countrySeeks();

        info = new DbInfo("2.2", Instant.ofEpochSecond(time), type < TYPES.size() ? TYPES.get(type) : "unknown (" + type + ")",
                charset, maxCity > 0, dbItems);
    }

    /** Database header: build date, edition, charset. */
    public DbInfo info() {
        return info;
    }

    /**
     * ISO 3166-1 alpha-2 country code for a dotted IPv4 address. Works with any database.
     *
     * @throws IllegalStateException if the database record for the IP is corrupted
     */
    public Optional<String> country(String ip) {
        OptionalInt id = countryId(ip);
        return id.isPresent() && id.getAsInt() < ISO.size() ? Optional.of(ISO.get(id.getAsInt())) : Optional.empty();
    }

    /** Same as {@link #country(String)} for an {@link Inet4Address}. */
    public Optional<String> country(Inet4Address address) {
        return country(address.getHostAddress());
    }

    /**
     * Sypex Geo country id for a dotted IPv4 address. Works with any database.
     *
     * @throws IllegalStateException if the database record for the IP is corrupted
     */
    public OptionalInt countryId(String ip) {
        int num = locate(ip);
        if (num == 0) return OptionalInt.empty();
        // a negative offset in a city database (4-byte id with the high bit set) is corrupted: unpack throws
        int id = maxCity == 0 ? num : countryIdAt(num);
        return id > 0 ? OptionalInt.of(id) : OptionalInt.empty();
    }

    /**
     * Full information for a dotted IPv4 address: country, region and city with names and coordinates.
     * Empty if the IP is unknown, reserved or malformed.
     * With the country-only database ({@code SxGeo.dat}) only {@link Country#id()} and {@link Country#iso()} are filled.
     *
     * <p>The country always matches {@link #countryId(String)}: it is the city's own country, taken from the
     * database by id when the city has no region or its region belongs to another country (database errors).
     *
     * @throws IllegalStateException if the database record for the IP is corrupted
     */
    public Optional<GeoInfo> lookup(String ip) {
        int num = locate(ip);
        if (num == 0) return Optional.empty();
        if (maxCity == 0) return num > 0 ? Optional.of(new GeoInfo(new Country(num, iso(num)), null, null)) : Optional.empty();

        if (num < countrySize) {
            Map<String, Object> c = unpack(COUNTRY, num);
            Country country = c == null ? null : toCountry(c);
            return country != null && country.id() > 0 ? Optional.of(new GeoInfo(country, null, null)) : Optional.empty();
        }
        Map<String, Object> city = unpack(CITY, num);
        if (city == null) return Optional.empty();
        Map<String, Object> region = unpack(REGION, intOf(city, "region_seek"));
        Map<String, Object> country = region == null ? null : unpack(COUNTRY, intOf(region, "country_seek"));
        int countryId = intOf(city, "country_id");
        if (country == null || intOf(country, "id") != countryId) {
            // no region, or the region points to another country: the city's own country_id wins
            Integer seek = countrySeeks.get(countryId);
            country = seek == null ? null : unpack(COUNTRY, seek);
        }
        return Optional.of(new GeoInfo(
                country != null ? toCountry(country) : new Country(countryId, iso(countryId)),
                region != null ? toRegion(region) : null,
                toCity(city)));
    }

    /** Same as {@link #lookup(String)} for an {@link Inet4Address}. */
    public Optional<GeoInfo> lookup(Inet4Address address) {
        return lookup(address.getHostAddress());
    }

    private int countryIdAt(int seek) {
        Map<String, Object> m = seek < countrySize ? unpack(COUNTRY, seek) : unpack(CITY, seek);
        return m == null ? 0 : intOf(m, seek < countrySize ? "id" : "country_id");
    }

    /** Returns the country id (country database) or the record offset (city database); 0 if not found. */
    private int locate(String ip) {
        long ipn = parseIpv4(ip);
        int first = (int) (ipn >>> 24);
        if (ipn < 0 || first == 0 || first == 10 || first == 127 || first >= bIdxLen) return 0;

        // blocks [bMin, bMax) start with the first byte of the IP; the constructor checked bMin <= bMax <= dbItems
        int bMin = (int) u32(bIdxStart + (first - 1) * 4);
        int bMax = (int) u32(bIdxStart + first * 4);
        int min = bMin, max = bMax;
        if (bMax - bMin > rangeLen) {
            // m_idx[i] is the first IP of block (i + 1) * rangeLen; part = number of those entries <= ip
            int last = Math.min(bMax / rangeLen, mIdxLen) - 1;
            int part = searchIdx(ipn, bMin / rangeLen, last);
            min = Math.max(part * rangeLen, bMin);
            max = part > last ? bMax : Math.min((part + 1) * rangeLen, bMax);
        }
        return searchDb(ipn, min, max);
    }

    private int searchIdx(long ip, int min, int max) {
        while (max - min > 8) {
            int offset = (min + max) >>> 1;
            if (ip >= u32(mIdxStart + offset * 4)) min = offset;
            else max = offset;
        }
        while (min <= max && ip >= u32(mIdxStart + min * 4)) min++;
        return min;
    }

    /** Id of the last block starting at or before ip: blocks cover the address space without gaps. */
    private int searchDb(long ip, int min, int max) {
        long low = ip & 0xFFFFFF; // blocks inside a first-byte range store only the 3 low bytes
        while (max - min > 8) {
            int offset = (min + max) >>> 1;
            if (low >= u24(dbStart + offset * blockLen)) min = offset;
            else max = offset;
        }
        while (min < max && low >= u24(dbStart + min * blockLen)) min++;
        if (min == 0) return 0;
        int id = 0;
        int pos = dbStart + min * blockLen - idLen;
        for (int i = 0; i < idLen; i++) id = (id << 8) | u8(pos + i);
        return id;
    }

    /** Decodes a record using the database pack format. Offset 0 means "no record". */
    private Map<String, Object> unpack(int type, int seek) {
        if (seek == 0 || (type == COUNTRY ? maxCountry : type == REGION ? maxRegion : maxCity) == 0) return null;
        int start = type == REGION ? regionsStart : citiesStart;
        int size = type == REGION ? regionSize : type == COUNTRY ? countrySize : countrySize + citySize;
        if (seek < 0 || seek >= size) throw corrupted(type, seek);
        Map<String, Object> result = new LinkedHashMap<>();
        decode(type, start + seek, start + size, result);
        return result;
    }

    /** Decodes the record at {@code pos} into {@code result}; returns the position after it. */
    private int decode(int type, int pos, int end, Map<String, Object> result) {
        for (Field field : packs.get(type)) {
            int size = field.size();
            if (field.code() == 'b') {
                int nul = pos;
                while (nul < end && data[nul] != 0) nul++;
                size = nul - pos + 1;
            }
            if (size > end - pos) throw corrupted(type, pos - (type == REGION ? regionsStart : citiesStart));
            Object value = switch (field.code()) {
                case 't' -> (int) data[pos];
                case 'T' -> u8(pos);
                case 's' -> (int) (short) le(pos, 2);
                case 'S' -> (int) le(pos, 2);
                case 'm' -> (int) le(pos, 3) << 8 >> 8;
                case 'M' -> (int) le(pos, 3);
                case 'i' -> (int) le(pos, 4);
                case 'I' -> le(pos, 4);
                case 'f' -> Float.intBitsToFloat((int) le(pos, 4));
                case 'd' -> Double.longBitsToDouble(le(pos, 8));
                case 'n' -> (short) le(pos, 2) / field.scale();
                case 'N' -> (int) le(pos, 4) / field.scale();
                case 'c' -> {
                    int to = pos + size;
                    while (to > pos && data[to - 1] == ' ') to--; // like PHP rtrim($s, ' ')
                    yield new String(data, pos, to - pos, charset);
                }
                default -> new String(data, pos, size - 1, charset); // 'b'
            };
            result.put(field.name(), value);
            pos += size;
        }
        return pos;
    }

    /** Country id -> offset of its record, from walking the country records at the start of the cities section. */
    private Map<Integer, Integer> countrySeeks() {
        Map<Integer, Integer> seeks = new HashMap<>();
        int end = citiesStart + countrySize;
        try {
            for (int pos = citiesStart + 1, next; pos < end; pos = next) {
                Map<String, Object> country = new HashMap<>();
                next = decode(COUNTRY, pos, end, country);
                if (next == pos) break;
                if (intOf(country, "id") > 0) seeks.putIfAbsent(intOf(country, "id"), pos - citiesStart);
            }
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException("Corrupted Sypex Geo database: invalid country records", e);
        }
        return Map.copyOf(seeks);
    }

    private static IllegalStateException corrupted(int type, int seek) {
        return new IllegalStateException("Corrupted Sypex Geo database: " + RECORD_NAMES[type] + " record at offset " + seek + " is out of bounds");
    }

    /** Parses a pack format such as {@code M:region_seek/T:country_id/N5:lat/b:name_ru}. */
    private static List<Field> parsePack(String pack) {
        List<Field> fields = new ArrayList<>();
        for (String token : pack.split("/")) {
            if (token.isEmpty()) continue;
            int colon = token.indexOf(':');
            char code = token.charAt(0);
            String digits = colon < 1 ? "" : token.substring(1, colon);
            boolean digitsOk = digits.chars().allMatch(c -> c >= '0' && c <= '9')
                    && (code == 'c' ? !digits.isEmpty() : code == 'n' || code == 'N' || digits.isEmpty());
            int n = 0;
            try {
                if (!digits.isEmpty()) n = Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                digitsOk = false;
            }
            if (colon < 1 || PACK_CODES.indexOf(code) < 0 || !digitsOk) {
                throw new IllegalArgumentException("Invalid pack format '" + token + "' in Sypex Geo database");
            }
            int size = switch (code) {
                case 't', 'T' -> 1;
                case 's', 'S', 'n' -> 2;
                case 'm', 'M' -> 3;
                case 'd' -> 8;
                case 'c' -> n;
                case 'b' -> 0;
                default -> 4;
            };
            fields.add(new Field(code, size, Math.pow(10, n), token.substring(colon + 1)));
        }
        return List.copyOf(fields);
    }

    private int u8(int pos) {
        return data[pos] & 0xFF;
    }

    private int u16(int pos) {
        return (u8(pos) << 8) | u8(pos + 1);
    }

    private long u24(int pos) {
        return (u8(pos) << 16) | (u8(pos + 1) << 8) | u8(pos + 2);
    }

    private long u32(int pos) {
        return ((long) u16(pos) << 16) | u16(pos + 2);
    }

    /** Little-endian unsigned integer of {@code len} bytes. */
    private long le(int pos, int len) {
        long v = 0;
        for (int i = len - 1; i >= 0; i--) v = (v << 8) | u8(pos + i);
        return v;
    }

    /** Returns the address as an unsigned 32-bit number, or -1 if it is not a dotted IPv4 address. */
    private static long parseIpv4(String ip) {
        if (ip == null) return -1;
        int pos = 0, end = ip.length();
        while (pos < end && isSpace(ip.charAt(pos))) pos++;
        while (end > pos && isSpace(ip.charAt(end - 1))) end--;
        long result = 0;
        for (int octet = 0; octet < 4; octet++) {
            if (octet > 0 && (pos >= end || ip.charAt(pos++) != '.')) return -1;
            int start = pos, value = 0;
            while (pos < end && pos - start < 4 && ip.charAt(pos) >= '0' && ip.charAt(pos) <= '9') {
                value = value * 10 + ip.charAt(pos++) - '0';
            }
            int len = pos - start;
            if (len == 0 || len > 3 || value > 255 || len > 1 && ip.charAt(start) == '0') return -1;
            result = (result << 8) | value;
        }
        return pos == end ? result : -1;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static String iso(int countryId) {
        return countryId >= 0 && countryId < ISO.size() ? ISO.get(countryId) : "";
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

    private static Map<String, Object> withoutInternal(Map<String, Object> m) {
        Map<String, Object> copy = new LinkedHashMap<>(m);
        copy.keySet().removeAll(INTERNAL_KEYS);
        return Collections.unmodifiableMap(copy);
    }

    private static Country toCountry(Map<String, Object> m) {
        return new Country(intOf(m, "id"), stringOf(m, "iso"), stringOf(m, "name_ru"), stringOf(m, "name_en"),
                doubleOf(m, "lat"), doubleOf(m, "lon"), withoutInternal(m));
    }

    private static Region toRegion(Map<String, Object> m) {
        return new Region(intOf(m, "id"), stringOf(m, "iso"), stringOf(m, "name_ru"), stringOf(m, "name_en"), withoutInternal(m));
    }

    private static City toCity(Map<String, Object> m) {
        return new City(intOf(m, "id"), stringOf(m, "name_ru"), stringOf(m, "name_en"),
                doubleOf(m, "lat"), doubleOf(m, "lon"), withoutInternal(m));
    }

    private static final List<String> TYPES = List.of(
            "n/a", "SxGeo Country", "SxGeo City RU", "SxGeo City EN", "SxGeo City",
            "SxGeo City Max RU", "SxGeo City Max EN", "SxGeo City Max");

    /** Sypex Geo country id -> ISO code, copied from SxGeo.php. */
    private static final List<String> ISO = List.of(
            "", "AP", "EU", "AD", "AE", "AF", "AG", "AI", "AL", "AM", "CW", "AO", "AQ", "AR", "AS", "AT", "AU",
            "AW", "AZ", "BA", "BB", "BD", "BE", "BF", "BG", "BH", "BI", "BJ", "BM", "BN", "BO", "BR", "BS",
            "BT", "BV", "BW", "BY", "BZ", "CA", "CC", "CD", "CF", "CG", "CH", "CI", "CK", "CL", "CM", "CN",
            "CO", "CR", "CU", "CV", "CX", "CY", "CZ", "DE", "DJ", "DK", "DM", "DO", "DZ", "EC", "EE", "EG",
            "EH", "ER", "ES", "ET", "FI", "FJ", "FK", "FM", "FO", "FR", "SX", "GA", "GB", "GD", "GE", "GF",
            "GH", "GI", "GL", "GM", "GN", "GP", "GQ", "GR", "GS", "GT", "GU", "GW", "GY", "HK", "HM", "HN",
            "HR", "HT", "HU", "ID", "IE", "IL", "IN", "IO", "IQ", "IR", "IS", "IT", "JM", "JO", "JP", "KE",
            "KG", "KH", "KI", "KM", "KN", "KP", "KR", "KW", "KY", "KZ", "LA", "LB", "LC", "LI", "LK", "LR",
            "LS", "LT", "LU", "LV", "LY", "MA", "MC", "MD", "MG", "MH", "MK", "ML", "MM", "MN", "MO", "MP",
            "MQ", "MR", "MS", "MT", "MU", "MV", "MW", "MX", "MY", "MZ", "NA", "NC", "NE", "NF", "NG", "NI",
            "NL", "NO", "NP", "NR", "NU", "NZ", "OM", "PA", "PE", "PF", "PG", "PH", "PK", "PL", "PM", "PN",
            "PR", "PS", "PT", "PW", "PY", "QA", "RE", "RO", "RU", "RW", "SA", "SB", "SC", "SD", "SE", "SG",
            "SH", "SI", "SJ", "SK", "SL", "SM", "SN", "SO", "SR", "ST", "SV", "SY", "SZ", "TC", "TD", "TF",
            "TG", "TH", "TJ", "TK", "TM", "TN", "TO", "TL", "TR", "TT", "TV", "TW", "TZ", "UA", "UG", "UM",
            "US", "UY", "UZ", "VA", "VC", "VE", "VG", "VI", "VN", "VU", "WF", "WS", "YE", "YT", "RS", "ZA",
            "ZM", "ME", "ZW", "A1", "XK", "O1", "AX", "GG", "IM", "JE", "BL", "MF", "BQ", "SS");
}
