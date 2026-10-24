# sypexgeo-java

[Русский](README.md) | **English**

Java library for reading [Sypex Geo](https://sypexgeo.net/) 2.2 databases (`SxGeo.dat`, `SxGeoCity.dat`, `SxGeoMax.dat`): country, region and city by IPv4 address.

- No dependencies: one class, plus records for the results.
- Works offline with a local database file: no network calls, no request limits.
- The database is loaded into memory once; a lookup takes a few microseconds.
- Instances are immutable and thread-safe, so create one per application.
- Java 17+.
- UTF-8, CP1251 and Latin-1 database editions are supported.

This is a port of the official `SxGeo.php` 2.2.3 with its range-boundary bug fixed: an IP equal to the first address of a range always resolves to that range (`SxGeo.php` sometimes returns the neighbouring one). Answers were checked by brute force: on real databases for the start of every range and the address before it, on synthetic ones for every range and index boundary. A Kotlin version, which also runs on Java 8+, is available as [sypexgeo-kotlin](https://github.com/row2002/sypexgeo-kotlin).

## Installation

Published via [JitPack](https://jitpack.io/#row2002/sypexgeo-java).

**Maven**

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.row2002</groupId>
    <artifactId>sypexgeo-java</artifactId>
    <version>1.1.0</version>
</dependency>
```

**Gradle**

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.row2002:sypexgeo-java:1.1.0")
}
```

Version 1.0.0 kept the SxGeo.php behaviour at range boundaries (an IP equal to the first address of a range could resolve to the neighbouring range); this is fixed since 1.1.0.

## Getting a database

Download a database from [sypexgeo.net/ru/download](https://sypexgeo.net/ru/download/) and unzip it:

| File | Contents | Size |
|---|---|---|
| `SxGeo.dat` ([SxGeoCountry.zip](https://sypexgeo.net/files/SxGeoCountry.zip)) | countries only | ~0.8 MB |
| `SxGeoCity.dat` ([SxGeoCity_utf8.zip](https://sypexgeo.net/files/SxGeoCity_utf8.zip)) | countries, regions, cities | ~40 MB |
| `SxGeoMax.dat` (paid City Max) | the same, plus extra fields (time zones, etc.) | — |

The databases are updated regularly. Re-download them at least once a month.

## Usage

```java
import io.github.row2002.sypexgeo.GeoInfo;
import io.github.row2002.sypexgeo.SxGeo;
import java.nio.file.Path;

SxGeo geo = new SxGeo(Path.of("SxGeoCity.dat")); // once, at application start

geo.country("77.88.8.8");   // Optional[RU]
geo.countryId("77.88.8.8"); // OptionalInt[185]

geo.lookup("93.73.35.74").ifPresent(info -> {
    info.country().iso();     // "UA"
    info.country().nameEn();  // "Ukraine"
    info.region().nameEn();   // "Kyiv"   (region() may be null)
    info.region().iso();      // "UA-30"
    info.city().nameEn();     // "Kyiv"   (city() may be null)
    info.city().nameRu();     // "Киев"
    info.city().lat();        // 50.45466
    info.city().lon();        // 30.5238
});

String iso = geo.country(request.getRemoteAddr()).orElse("XX");
```

When an IP is missing from the database, reserved (`10.*`, `127.*`, `0.*`), malformed or `null`, the methods return an empty `Optional` instead of throwing. An address must be four decimal numbers 0-255 separated by dots, each of 1-3 ASCII digits without leading zeros: `"08.8.8.8"` and `"010.0.0.1"` are rejected, as by PHP `ip2long`. Spaces, tabs and line breaks (`' '`, `'\t'`, `'\n'`, `'\r'`) at the start and the end are ignored; any other character makes the address malformed. If the IP comes from a header such as `X-Forwarded-For`, extract a single address first (the first or the last trusted hop): a value like `"77.88.8.8, 10.0.0.1"` as a whole returns nothing.

### Other ways to open a database

```java
new SxGeo(Path.of("/var/lib/geo/SxGeoCity.dat"));                     // file
new SxGeo(getClass().getResourceAsStream("/SxGeo.dat").readAllBytes()); // classpath resource or any InputStream
```

The `byte[]` constructor copies the array. The constructors throw `IllegalArgumentException` if the data is not a Sypex Geo 2.2 database, is corrupted (invalid header, index or record format) or is truncated, and `IOException` if the file cannot be read.

### Spring

```java
@Bean
SxGeo sxGeo(@Value("${geo.db:SxGeoCity.dat}") Path db) throws IOException {
    return new SxGeo(db);
}
```

### Reloading the database without a restart

Instances are immutable, so swapping the reference is enough:

```java
class Geo {
    private static final Path DB = Path.of("SxGeoCity.dat");
    private volatile SxGeo geo;

    Geo() throws IOException {
        reload();
    }

    void reload() throws IOException {
        geo = new SxGeo(DB); // the old instance serves requests while the new one is loading
    }

    Optional<String> country(String ip) {
        return geo.country(ip);
    }
}
```

## API

| Method | Returns |
|---|---|
| `Optional<String> country(String ip)` | ISO country code (`"RU"`); works with any database |
| `OptionalInt countryId(String ip)` | Sypex Geo country id |
| `Optional<GeoInfo> lookup(String ip)` | country, region and city |
| `country(Inet4Address)`, `lookup(Inet4Address)` | the same for `Inet4Address` |
| `DbInfo info()` | build date, edition, charset, number of IP ranges |

```java
record GeoInfo(Country country, Region region, City city) {}
record Country(int id, String iso, String nameRu, String nameEn, double lat, double lon, Map<String, Object> fields) {}
record Region(int id, String iso, String nameRu, String nameEn, Map<String, Object> fields) {}
record City(int id, String nameRu, String nameEn, double lat, double lon, Map<String, Object> fields) {}
```

Notes:

- `country()` is always set; `region()` and `city()` may be `null`.
- With the country database (`SxGeo.dat`), `lookup` fills only `country().id()` and `country().iso()`; `region()` and `city()` are `null`.
- When only the country is known for an IP range, `city()` and `region()` are `null`, and `country()` has its name and coordinates.
- The country in `lookup` is always the city's country (the one `country` and `countryId` return). When a city has no region (`region()` is `null`) or the database wrongly links its region to another country, `country()` is the full record of the city's country from the database, with names and coordinates. Only `id` and `iso` are filled if the database has no record for that country.
- `fields()` holds all fields of the record except internal links (`region_seek`, `country_seek`, `country_id`), as an unmodifiable `Map`. For City Max that includes extra fields such as `timezone`:

```java
Object tz = info.region().fields().get("timezone"); // City Max only
```

### Exceptions

- Constructors: `IllegalArgumentException` if the data is not a Sypex Geo 2.2 database, is corrupted or truncated; `IOException` if the file cannot be read.
- `country`, `countryId` and `lookup` never throw for a malformed IP. If a record in the database itself is corrupted (it points outside its section or runs past its end), they throw `IllegalStateException` with the message `Corrupted Sypex Geo database…`. This does not happen with an intact database.

### Database info

```java
geo.info().created();   // 2026-08-12T…Z
geo.info().type();      // "SxGeo City EN"
geo.info().hasCities(); // true
```

## Limitations

- IPv4 only, because the Sypex Geo format has no IPv6 data. IPv6 input returns an empty `Optional`.
- Only format 2.2 is supported, which covers all current databases.
- Memory use equals the file size (~40 MB for City).

## Building and testing

```bash
./gradlew test
```

Most tests build synthetic databases in memory and check every range boundary against brute force, so they need no files. The integration tests use the real databases: put `SxGeo.dat` and `SxGeoCity.dat` into `testdata/`, otherwise those tests are skipped. CI downloads the databases automatically, at most once a month.

## License

[MIT](LICENSE). The algorithm is based on the official `SxGeo.php` (© zapimir, BINOVATOR, BSD license). Sypex Geo databases are distributed under the terms published at [sypexgeo.net](https://sypexgeo.net/).
