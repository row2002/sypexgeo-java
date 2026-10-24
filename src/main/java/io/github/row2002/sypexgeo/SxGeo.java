package io.github.row2002.sypexgeo;

import java.io.IOException;
import java.net.Inet4Address;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
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
 * Port of the official {@code SxGeo.php} 2.2.3.
 */
public final class SxGeo {

    private static final int HEADER_SIZE = 40;
    private static final int COUNTRY = 0, REGION = 1, CITY = 2;
    private static final Set<String> INTERNAL_KEYS = Set.of("region_seek", "country_seek", "country_id");

    private final byte[] data;
    private final DbInfo info;
    private final int rangeLen, bIdxLen, mIdxLen, dbItems, idLen, blockLen;
    private final int maxRegion, maxCity, maxCountry, countrySize;
    private final Charset charset;
    private final String[] pack;
    private final int bIdxStart, mIdxStart, dbStart, regionsStart, citiesStart;

    /** Opens a database file; it is read into memory once. */
    public SxGeo(Path path) throws IOException {
        this(Files.readAllBytes(path));
    }

    /**
     * Reads a database from bytes, e.g. a classpath resource.
     *
     * @throws IllegalArgumentException if the data is not a Sypex Geo 2.2 database or is truncated
     */
    public SxGeo(byte[] data) {
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
        int regionSize = (int) u32(24);
        int citySize = (int) u32(28);
        maxCountry = u16(32);
        countrySize = (int) u32(34);
        int packSize = u16(38);
        if (bIdxLen == 0 || mIdxLen == 0 || rangeLen == 0 || dbItems <= 0 || time == 0 || idLen == 0
                || regionSize < 0 || citySize < 0 || countrySize < 0) {
            throw new IllegalArgumentException("Corrupted Sypex Geo database header");
        }
        if (data.length < (long) HEADER_SIZE + packSize + (bIdxLen + mIdxLen) * 4L
                + (long) dbItems * (3 + idLen) + regionSize + citySize) {
            throw new IllegalArgumentException("Truncated Sypex Geo database");
        }

        charset = switch (u8(9)) {
            case 0 -> StandardCharsets.UTF_8;
            case 1 -> StandardCharsets.ISO_8859_1;
            case 2 -> Charset.forName("windows-1251");
            default -> throw new IllegalArgumentException("Unknown database charset " + u8(9));
        };
        pack = packSize > 0 ? new String(data, HEADER_SIZE, packSize, StandardCharsets.ISO_8859_1).split("\0", -1) : new String[0];
        blockLen = 3 + idLen;
        bIdxStart = HEADER_SIZE + packSize;
        mIdxStart = bIdxStart + bIdxLen * 4;
        dbStart = mIdxStart + mIdxLen * 4;
        regionsStart = dbStart + dbItems * blockLen;
        citiesStart = regionsStart + regionSize;
        if (maxCity != 0 && pack.length < 3) {
            throw new IllegalArgumentException("City database without pack formats");
        }

        info = new DbInfo("2.2", Instant.ofEpochSecond(time), type < TYPES.size() ? TYPES.get(type) : "unknown (" + type + ")",
                charset, maxCity > 0, dbItems);
    }

    /** Database header: build date, edition, charset. */
    public DbInfo info() {
        return info;
    }

    /** ISO 3166-1 alpha-2 country code for a dotted IPv4 address. Works with any database. */
    public Optional<String> country(String ip) {
        OptionalInt id = countryId(ip);
        return id.isPresent() && id.getAsInt() < ISO.size() ? Optional.of(ISO.get(id.getAsInt())) : Optional.empty();
    }

    /** Same as {@link #country(String)} for an {@link Inet4Address}. */
    public Optional<String> country(Inet4Address address) {
        return country(address.getHostAddress());
    }

    /** Sypex Geo country id for a dotted IPv4 address. Works with any database. */
    public OptionalInt countryId(String ip) {
        long ipn = parseIpv4(ip);
        int num = ipn < 0 ? 0 : locate(ipn);
        if (num == 0) return OptionalInt.empty();
        int id = maxCity == 0 ? num : countryIdAt(num);
        return id > 0 ? OptionalInt.of(id) : OptionalInt.empty();
    }

    /**
     * Full information for a dotted IPv4 address: country, region and city with names and coordinates.
     * Empty if the IP is unknown, reserved or malformed.
     * With the country-only database ({@code SxGeo.dat}) only {@link Country#id()} and {@link Country#iso()} are filled.
     */
    public Optional<GeoInfo> lookup(String ip) {
        long ipn = parseIpv4(ip);
        int num = ipn < 0 ? 0 : locate(ipn);
        if (num == 0) return Optional.empty();
        if (maxCity == 0) return Optional.of(new GeoInfo(new Country(num, iso(num)), null, null));

        if (num < countrySize) {
            Map<String, Object> c = unpack(COUNTRY, num, maxCountry);
            Country country = c == null ? null : toCountry(c);
            return country != null && country.id() > 0 ? Optional.of(new GeoInfo(country, null, null)) : Optional.empty();
        }
        Map<String, Object> city = unpack(CITY, num, maxCity);
        if (city == null) return Optional.empty();
        Map<String, Object> region = unpack(REGION, intOf(city, "region_seek"), maxRegion);
        Map<String, Object> country = region == null ? null : unpack(COUNTRY, intOf(region, "country_seek"), maxCountry);
        int countryId = intOf(city, "country_id");
        // the city's own country_id wins: a few regions point to another country (database errors)
        return Optional.of(new GeoInfo(
                country != null && intOf(country, "id") == countryId ? toCountry(country) : new Country(countryId, iso(countryId)),
                region != null ? toRegion(region) : null,
                toCity(city)));
    }

    /** Same as {@link #lookup(String)} for an {@link Inet4Address}. */
    public Optional<GeoInfo> lookup(Inet4Address address) {
        return lookup(address.getHostAddress());
    }

    private int countryIdAt(int seek) {
        Map<String, Object> m = seek < countrySize ? unpack(COUNTRY, seek, maxCountry) : unpack(CITY, seek, maxCity);
        return m == null ? 0 : intOf(m, seek < countrySize ? "id" : "country_id");
    }

    /** Returns the country id (country database) or the record offset (city database); 0 if not found. */
    private int locate(long ip) {
        int first = (int) (ip >>> 24);
        if (first == 0 || first == 10 || first == 127 || first >= bIdxLen) return 0;

        int bMin = (int) u32(bIdxStart + (first - 1) * 4);
        int bMax = (int) u32(bIdxStart + first * 4);
        int min, max;
        if (bMax - bMin > rangeLen) {
            int part = searchIdx(ip, bMin / rangeLen, bMax / rangeLen - 1);
            min = part > 0 ? part * rangeLen : 0;
            max = part > mIdxLen ? dbItems : (part + 1) * rangeLen;
            if (min < bMin) min = bMin;
            if (max > bMax) max = bMax;
        } else {
            min = bMin;
            max = bMax;
        }
        return searchDb(ip, min, max);
    }

    private int searchIdx(long ip, int min, int max) {
        while (max - min > 8) {
            int offset = (min + max) >>> 1;
            if (ip > u32(mIdxStart + offset * 4)) min = offset;
            else max = offset;
        }
        while (ip > u32(mIdxStart + min * 4) && min++ < max) {
            // linear tail
        }
        return min;
    }

    private int searchDb(long ip, int min, int max) {
        if (max - min > 1) {
            long low = ip & 0xFFFFFF; // blocks inside a first-byte range store only the 3 low bytes
            while (max - min > 8) {
                int offset = (min + max) >>> 1;
                if (low > u24(dbStart + offset * blockLen)) min = offset;
                else max = offset;
            }
            while (low >= u24(dbStart + min * blockLen) && ++min < max) {
                // linear tail
            }
        } else {
            min++;
        }
        int id = 0;
        int pos = dbStart + min * blockLen - idLen;
        for (int i = 0; i < idLen; i++) id = (id << 8) | u8(pos + i);
        return id;
    }

    /** Decodes a record using the database pack format. Offset 0 means "no record". */
    private Map<String, Object> unpack(int type, int seek, int maxLen) {
        if (seek == 0 || maxLen == 0) return null;
        int pos = (type == REGION ? regionsStart : citiesStart) + seek;
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : pack[type].split("/")) {
            int colon = field.indexOf(':');
            String code = field.substring(0, colon);
            String name = field.substring(colon + 1);
            Object value;
            switch (code.charAt(0)) {
                case 't' -> { value = (int) data[pos]; pos += 1; }
                case 'T' -> { value = u8(pos); pos += 1; }
                case 's' -> { value = (int) (short) le(pos, 2); pos += 2; }
                case 'S' -> { value = (int) le(pos, 2); pos += 2; }
                case 'm' -> { value = (int) le(pos, 3) << 8 >> 8; pos += 3; }
                case 'M' -> { value = (int) le(pos, 3); pos += 3; }
                case 'i' -> { value = (int) le(pos, 4); pos += 4; }
                case 'I' -> { value = le(pos, 4); pos += 4; }
                case 'f' -> { value = Float.intBitsToFloat((int) le(pos, 4)); pos += 4; }
                case 'd' -> { value = Double.longBitsToDouble(le(pos, 8)); pos += 8; }
                case 'n' -> { value = (short) le(pos, 2) / pow10(code); pos += 2; }
                case 'N' -> { value = (int) le(pos, 4) / pow10(code); pos += 4; }
                case 'c' -> {
                    int len = Integer.parseInt(code.substring(1));
                    value = new String(data, pos, len, charset).replaceFirst(" +$", "");
                    pos += len;
                }
                case 'b' -> {
                    int end = pos;
                    while (data[end] != 0) end++;
                    value = new String(data, pos, end - pos, charset);
                    pos = end + 1;
                }
                default -> throw new IllegalStateException("Unknown pack type '" + code + "' in database");
            }
            result.put(name, value);
        }
        return result;
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

    private static double pow10(String code) {
        return Math.pow(10, Integer.parseInt(code.substring(1)));
    }

    /** Returns the address as an unsigned 32-bit number, or -1 if it is not a dotted IPv4 address. */
    private static long parseIpv4(String ip) {
        if (ip == null) return -1;
        String[] parts = ip.trim().split("\\.", -1);
        if (parts.length != 4) return -1;
        long result = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return -1;
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < '0' || part.charAt(i) > '9') return -1;
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) return -1;
            result = (result << 8) | octet;
        }
        return result;
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
