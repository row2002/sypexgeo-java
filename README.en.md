# sypexgeo-java

[![CI](https://github.com/row2002/sypexgeo-java/actions/workflows/ci.yml/badge.svg)](https://github.com/row2002/sypexgeo-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.row2002/sypexgeo-java)](https://central.sonatype.com/artifact/io.github.row2002/sypexgeo-java)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

[Русский](README.md) | **English**

Java library for reading [Sypex Geo](https://sypexgeo.net/) 2.2 databases (`SxGeo.dat`, `SxGeoCity.dat`, `SxGeoMax.dat`): country, region and city by IPv4 address. Offline, no runtime dependencies, Java 17+.

## Quick start

```kotlin
// build.gradle.kts
repositories {
    mavenCentral()
}
dependencies {
    implementation("io.github.row2002:sypexgeo-java:1.1.0")
}
```

```bash
curl -fLO https://sypexgeo.net/files/SxGeoCity_utf8.zip && unzip SxGeoCity_utf8.zip SxGeoCity.dat
```

```java
import io.github.row2002.sypexgeo.GeoInfo;
import io.github.row2002.sypexgeo.SxGeo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class Main {
    public static void main(String[] args) throws IOException {
        SxGeo geo = new SxGeo(Path.of("SxGeoCity.dat"));    // once per application, thread-safe
        System.out.println(geo.country("77.88.8.8").orElse("XX")); // RU
        Optional<GeoInfo> info = geo.lookup("77.88.8.8");
        info.map(GeoInfo::city).ifPresent(city -> System.out.println(city.nameEn())); // Moscow
    }
}
```

## Contents

- [Which library?](#which-library)
- [Features](#features)
- [Installation](#installation)
- [Getting a database](#getting-a-database)
- [Usage](#usage)
- [API](#api)
- [Recipes](#recipes): [Spring Boot](#spring-boot), [client IP behind a proxy](#client-ip-behind-a-proxy), [servlet filter](#servlet-filter), [updating the database](#updating-the-database)
- [FAQ](#faq)
- [Sypex Geo vs MaxMind GeoLite2](#sypex-geo-vs-maxmind-geolite2)
- [Performance](#performance)
- [Building and testing](#building-and-testing)
- [License and data](#license-and-data)

## Which library?

| Your project | Library |
|---|---|
| Java 17+ | [sypexgeo-java](https://github.com/row2002/sypexgeo-java): `Optional`, records, no dependencies |
| Kotlin, Android, Java 8–16 | [sypexgeo-kotlin](https://github.com/row2002/sypexgeo-kotlin): nullable types, data classes, JVM 8+, depends only on `kotlin-stdlib` |
| Kotlin on Java 17+ | either; sypexgeo-java has JSpecify annotations, so Kotlin sees its nullability correctly |

Never add both: they have the same package and class names (`io.github.row2002.sypexgeo.SxGeo`). The behaviour is the same, including IP parsing and error messages.

## Features

- No runtime dependencies: one class plus records for the results.
- Works offline with a local database file: no network calls, no request limits.
- The database is loaded into memory once; a lookup takes 0.2–2 µs (see [Performance](#performance)).
- Instances are immutable and thread-safe, so create one per application.
- UTF-8, CP1251 and Latin-1 database editions are supported.
- Clear errors: a ZIP archive, an HTML error page or a MaxMind file passed instead of the `.dat` file is reported as such.

This is a port of the official `SxGeo.php` 2.2.3 with its range-boundary bug fixed: an IP equal to the first address of a range always resolves to that range (`SxGeo.php` sometimes returns the neighbouring one). Every range boundary is checked on synthetic databases in the tests; tests on the real databases check random addresses.

## Installation

Published to [Maven Central](https://central.sonatype.com/artifact/io.github.row2002/sypexgeo-java).

**Gradle (Kotlin DSL)**

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.row2002:sypexgeo-java:1.1.0")
}
```

**Gradle (Groovy DSL)**

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'io.github.row2002:sypexgeo-java:1.1.0'
}
```

**Version catalog** (`gradle/libs.versions.toml`)

```toml
[versions]
sypexgeo = "1.1.0"

[libraries]
sypexgeo = { module = "io.github.row2002:sypexgeo-java", version.ref = "sypexgeo" }
```

```kotlin
dependencies {
    implementation(libs.sypexgeo)
}
```

**Maven**

```xml
<dependency>
    <groupId>io.github.row2002</groupId>
    <artifactId>sypexgeo-java</artifactId>
    <version>1.1.0</version>
</dependency>
```

The older version 1.0.0 is published only on JitPack, as `com.github.row2002:sypexgeo-java:1.0.0`. It kept the SxGeo.php behaviour at range boundaries (an IP equal to the first address of a range could resolve to the neighbouring range); this is fixed since 1.1.0. See [CHANGELOG.md](CHANGELOG.md).

## Getting a database

Download a database from [sypexgeo.net/ru/download](https://sypexgeo.net/ru/download/) and unzip it:

| File | Contents | Size |
|---|---|---|
| `SxGeo.dat` ([SxGeoCountry.zip](https://sypexgeo.net/files/SxGeoCountry.zip)) | countries only | ~1 MB |
| `SxGeoCity.dat` ([SxGeoCity_utf8.zip](https://sypexgeo.net/files/SxGeoCity_utf8.zip)) | countries, regions, cities | a few tens of MB |
| `SxGeoMax.dat` (paid City Max) | the same, plus extra fields (time zones, etc.) | — |

```bash
curl -fLO https://sypexgeo.net/files/SxGeoCountry.zip && unzip SxGeoCountry.zip SxGeo.dat
curl -fLO https://sypexgeo.net/files/SxGeoCity_utf8.zip && unzip SxGeoCity_utf8.zip SxGeoCity.dat
```

The databases are updated regularly: the download page and `info().created()` show the build date; see [Updating the database](#updating-the-database). The library ships no data, see [License and data](#license-and-data).

## Usage

```java
import io.github.row2002.sypexgeo.GeoInfo;
import io.github.row2002.sypexgeo.SxGeo;
import java.nio.file.Path;
import java.util.Locale;

SxGeo geo = new SxGeo(Path.of("SxGeoCity.dat")); // once, at application start

geo.country("77.88.8.8");   // Optional[RU]
geo.countryId("77.88.8.8"); // OptionalInt[185]

geo.lookup("93.73.35.74").ifPresent(info -> {
    info.country().iso();     // "UA"
    info.country().nameEn();  // "Ukraine"
    info.region().nameEn();   // "Kyiv"   (region() may be null)
    info.region().iso();      // "UA-30"
    info.city().nameEn();     // "Kiev"   (city() may be null)
    info.city().nameRu();     // "Киев"
    info.city().name(Locale.forLanguageTag("ru-RU")); // "Киев"
    info.city().lat();        // 50.45466
    info.city().lon();        // 30.5238
});

String iso = geo.country(request.getRemoteAddr()).orElse("XX");
```

### Other ways to open a database

```java
new SxGeo(Path.of("/var/lib/geo/SxGeoCity.dat")); // file
new SxGeo(new File("SxGeoCity.dat"));               // file
try (InputStream in = getClass().getResourceAsStream("/SxGeo.dat")) {
    geo = new SxGeo(in);                             // classpath resource or any InputStream
}
new SxGeo(bytes);                                    // byte[], copied
```

The `InputStream` constructor reads the stream to the end and does not close it. The whole database is kept in memory in every case.

### Kotlin

sypexgeo-java can be used from Kotlin: the JSpecify annotations tell the compiler what may be `null`.

```kotlin
val geo = SxGeo(Path.of("SxGeoCity.dat"))
val city: String? = geo.lookup(ip).orElse(null)?.city()?.nameEn() // ip: String? is fine
```

For Android or Java 8–16 use [sypexgeo-kotlin](https://github.com/row2002/sypexgeo-kotlin), which has the same API with nullable types instead of `Optional`.

## API

| Constructor / method | Description |
|---|---|
| `new SxGeo(Path)`, `new SxGeo(File)` | reads the file into memory |
| `new SxGeo(InputStream)` | reads the stream to the end; the stream is not closed |
| `new SxGeo(byte[])` | copies the array |
| `Optional<String> country(String ip)` | ISO 3166-1 alpha-2 country code (`"RU"`); works with any database |
| `OptionalInt countryId(String ip)` | Sypex Geo country id |
| `Optional<GeoInfo> lookup(String ip)` | country, region and city |
| `country(InetAddress)`, `countryId(InetAddress)`, `lookup(InetAddress)` | the same for an `InetAddress`, from its bytes; an `Inet6Address` gives an empty result |
| `DbInfo info()` | build date, edition, charset, number of IP ranges |
| `name(Locale)` on `Country`, `Region`, `City` | `nameRu` for a Russian locale, `nameEn` otherwise (falls back to the other name if one is empty) |

`String ip` parameters accept `null` (empty result). The `country(Inet4Address)` and `lookup(Inet4Address)` overloads of 1.0 remain for binary compatibility.

```java
record GeoInfo(Country country, @Nullable Region region, @Nullable City city) {}
record Country(int id, String iso, String nameRu, String nameEn, double lat, double lon, Map<String, Object> fields) {}
record Region(int id, String iso, String nameRu, String nameEn, Map<String, Object> fields) {}
record City(int id, String nameRu, String nameEn, double lat, double lon, Map<String, Object> fields) {}
record DbInfo(String version, Instant created, String type, Charset charset, boolean hasCities, int ipBlocks) {}
```

Notes:

- `country()` is always set; `region()` and `city()` may be `null`.
- With the country database (`SxGeo.dat`), `lookup` fills only `country().id()` and `country().iso()`; names are empty, `region()` and `city()` are `null`. For country names see the [FAQ](#faq).
- When only the country is known for an IP range, `city()` and `region()` are `null`, and `country()` has its name and coordinates.
- The country in `lookup` is always the city's country (the one `country` and `countryId` return). When a city has no region (`region()` is `null`) or the database wrongly links its region to another country, `country()` is the full record of the city's country from the database, with names and coordinates. Only `id` and `iso` are filled if the database has no record for that country.
- `name(Locale)` takes the language only: `nameRu` when `locale.getLanguage()` is `"ru"` and `nameRu` is not empty, otherwise `nameEn` if it is not empty, otherwise `nameRu`. There is no implicit default locale.
- `fields()` holds all fields of the record except internal links (`region_seek`, `country_seek`, `country_id`), as an unmodifiable `Map`. For City Max that includes extra fields such as `timezone`:

```java
Object tz = info.region().fields().get("timezone"); // City Max only
```

### IP addresses

- Dotted-decimal IPv4: four numbers 0-255 of 1-3 ASCII digits without leading zeros (`"08.8.8.8"` and `"010.0.0.1"` are rejected, as by PHP `ip2long`).
- Spaces, tabs and line breaks (`' '`, `'\t'`, `'\n'`, `'\r'`) at the start and the end are ignored; any other character makes the address malformed.
- The IPv4-mapped IPv6 form `::ffff:1.2.3.4` (prefix in any case) is accepted: some servers report IPv4 clients like that.
- `host:port`, lists such as `"77.88.8.8, 10.0.0.1"` and other IPv6 addresses give an empty result, as do reserved addresses (`0.*`, `10.*`, `127.*`) and `null`.
- `InetAddress` overloads use the address bytes, without a string round-trip. An `Inet6Address` gives an empty result; the JDK already returns `::ffff:a.b.c.d` as an `Inet4Address`.

### Exceptions

- Constructors: `IOException` if the file or stream cannot be read; `IllegalArgumentException` if the data is not a Sypex Geo 2.2 database, is corrupted (invalid header, index or record format) or truncated. For the `Path` and `File` constructors the message starts with the path, e.g. `/srv/geo/SxGeoCity.zip: Not a Sypex Geo database: this is a ZIP archive - unzip it and pass the .dat file`. See the [FAQ](#what-do-the-constructor-errors-mean).
- `country`, `countryId` and `lookup` never throw for a malformed IP. If a record in the database itself is corrupted (it points outside its section or runs past its end), they throw `IllegalStateException` with the message `Corrupted Sypex Geo database…`. This does not happen with an intact database.
- `country()` and `countryId()` decode only the country id of the record, so a corrupted record is reported by `lookup()` (`IllegalStateException`) but may not be by `country()`.

### Database info

```java
geo.info().created();   // 2026-08-12T…Z
geo.info().type();      // "SxGeo City EN"
geo.info().hasCities(); // true
```

## Recipes

### Spring Boot

One instance for the whole application, the path from `application.properties` (`sypexgeo.path=/var/lib/geo/SxGeoCity.dat`):

```java
@Configuration
class GeoConfig {
    @Bean
    SxGeo sxGeo(@Value("${sypexgeo.path}") Path path) throws IOException {
        return new SxGeo(path); // a broken file stops the startup
    }
}
```

A service that picks up a new file without a restart. A broken file (half-copied, an HTML error page) fails the constructor, and the old instance keeps working:

```java
@Service
public class GeoService {
    private static final System.Logger LOG = System.getLogger(GeoService.class.getName());
    private final Path db;
    private final AtomicReference<SxGeo> geo;
    private volatile FileTime loaded;

    public GeoService(@Value("${sypexgeo.path}") Path db) throws IOException {
        this.db = db;
        this.loaded = Files.getLastModifiedTime(db);
        this.geo = new AtomicReference<>(new SxGeo(db));
    }

    public Optional<String> country(String ip) {
        return geo.get().country(ip);
    }

    public Optional<GeoInfo> lookup(String ip) {
        return geo.get().lookup(ip);
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES) // needs @EnableScheduling
    public void reloadIfChanged() {
        try {
            FileTime modified = Files.getLastModifiedTime(db);
            if (modified.equals(loaded)) return;
            geo.set(new SxGeo(db)); // throws for a broken file: the old instance stays
            loaded = modified;
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "Sypex Geo database not reloaded, keeping the old one", e);
        }
    }
}
```

### Client IP behind a proxy

Do not parse `X-Forwarded-For` yourself: any client can send that header, and its first value is whatever the client wrote, so taking it lets anyone pick their country. Let the server replace the remote address with the client address from the headers of your **trusted** proxies only, and use `request.getRemoteAddr()` (in Ktor, `call.request.origin.remoteAddress`):

- Spring Boot on Tomcat: `server.forward-headers-strategy=native`. This is Tomcat's `RemoteIpValve`: it trusts only known proxies (private network addresses by default, set `server.tomcat.remoteip.internal-proxies` for others) and reads `X-Forwarded-For` from right to left. Avoid `framework` (`ForwardedHeaderFilter`) and Jetty's `ForwardedRequestCustomizer` unless your proxy overwrites `X-Forwarded-For`: they take its first, client-supplied value.
- Standalone Tomcat: `<Valve className="org.apache.catalina.valves.RemoteIpValve" />` in `server.xml`.
- Ktor: the `XForwardedHeaders` plugin (dependency `io.ktor:ktor-server-forwarded-header`), configured for your proxy chain: `install(XForwardedHeaders) { useLastProxy() }` for exactly one proxy that appends to `X-Forwarded-For`, `skipLastProxies(N - 1)` for N such proxies.

Only enable this when the application is reachable through your proxies alone; otherwise clients can spoof their address.

### Servlet filter

```java
import io.github.row2002.sypexgeo.SxGeo;
import jakarta.servlet.*; // javax.servlet.* for Servlet 4 / Spring Boot 2
import java.io.IOException;

public class GeoFilter implements Filter {
    private final SxGeo geo;

    public GeoFilter(SxGeo geo) {
        this.geo = geo;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        request.setAttribute("country", geo.country(request.getRemoteAddr()).orElse("XX"));
        chain.doFilter(request, response);
    }
}
```

In Spring Boot, declaring the filter as a bean is enough: a `@Bean` method returning `new GeoFilter(geo)` applies it to all requests. To set URL patterns or the order, return a `FilterRegistrationBean<GeoFilter>` instead.

### Updating the database

Sypex Geo updates its databases regularly; the download page and `geo.info().created()` show the build date. Download into a temporary file next to the current one, check it by opening it, then rename it over the old file (an atomic rename on the same file system) and swap the instance; requests keep using the old instance until then:

```java
static void update(URI zipUrl, Path db, AtomicReference<SxGeo> geo) throws IOException, InterruptedException {
    Path tmp = Files.createTempFile(db.toAbsolutePath().getParent(), "sxgeo", ".tmp");
    try {
        HttpResponse<InputStream> response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(HttpRequest.newBuilder(zipUrl).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (ZipInputStream zip = new ZipInputStream(response.body())) {
            if (response.statusCode() != 200) throw new IOException(zipUrl + ": HTTP " + response.statusCode());
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.getName().endsWith(".dat")) {
                    Files.copy(zip, tmp, StandardCopyOption.REPLACE_EXISTING);
                    break;
                }
            }
        }
        SxGeo fresh = new SxGeo(tmp); // throws IllegalArgumentException for a broken download
        Files.move(tmp, db, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        geo.set(fresh);
    } finally {
        Files.deleteIfExists(tmp);
    }
}
```

Or from cron, with the `GeoService` above picking up the file:

```bash
curl -fsSL -o /tmp/SxGeoCity.zip https://sypexgeo.net/files/SxGeoCity_utf8.zip \
  && unzip -p /tmp/SxGeoCity.zip SxGeoCity.dat > /srv/geo/SxGeoCity.dat.tmp \
  && mv /srv/geo/SxGeoCity.dat.tmp /srv/geo/SxGeoCity.dat
```

## FAQ

### Does it support IPv6?

No. The Sypex Geo format contains IPv4 ranges only; IPv6 addresses give an empty result (IPv4-mapped `::ffff:a.b.c.d` is handled as IPv4). If you need IPv6, use MaxMind GeoLite2.

### Which database should I choose?

`SxGeo.dat` if you need the country only: it is ~1 MB instead of a few tens of MB, and every call (`country()`, `countryId()`, `lookup()`) takes ~0.2 µs. `SxGeoCity.dat` for regions and cities with Russian and English names: `lookup()` on it takes ~2 µs, `country()` and `countryId()` ~0.4 µs (see [Performance](#performance)). `SxGeoMax.dat` (paid) adds fields such as time zones.

### How often should I update the database?

Sypex Geo updates its databases regularly: check the date of the latest build on the [download page](https://sypexgeo.net/ru/download/), and the date of the loaded file with `info().created()`. A new database can be picked up without a restart, see [Updating the database](#updating-the-database).

### What are `AP`, `EU`, `A1`, `O1`?

Special codes in the Sypex Geo country table, inherited from the legacy GeoIP country list: `AP` the Asia/Pacific region and `EU` Europe, without a specific country; `A1` an anonymous proxy; `O1` other country. They are not ISO 3166-1 country codes: handle them yourself before looking up a country name or showing a flag. `XK` (Kosovo) is a user-assigned ISO 3166-1 code, and the JDK knows it.

### How do I get country names with `SxGeo.dat`?

The country database has ISO codes only; take the names from the JDK:

```java
String iso = geo.country(ip).orElse("");
Locale country = new Locale("", iso); // on JDK 19+: Locale.of("", iso)
String en = country.getDisplayCountry(Locale.ENGLISH);              // "Russia"
String ru = country.getDisplayCountry(Locale.forLanguageTag("ru")); // "Россия"
```

For `AP`, `A1` and `O1` `getDisplayCountry` returns the code itself, and for `EU` it returns `"European Union"`, which is not what Sypex Geo means (Europe): handle these four codes yourself. On JDK 19+ create the locale with `Locale.of("", iso)`: the `new Locale("", iso)` constructor (`Locale("", iso)` in Kotlin) works on JDK 8+ but is deprecated since JDK 19.

### What do the constructor errors mean?

| Message | Meaning |
|---|---|
| `Not a Sypex Geo database: this is a ZIP archive - unzip it and pass the .dat file` | you passed the downloaded `.zip` |
| `Not a Sypex Geo database: looks like an HTML page - the download probably failed` | the server returned an error page instead of the file |
| `Not a Sypex Geo database: this is a MaxMind .mmdb file` | a MaxMind database; this library reads Sypex Geo only |
| `Not a Sypex Geo database: file is empty or too short (N bytes)` | an empty or cut-off download |
| `Not a Sypex Geo database` | some other file |
| `Unsupported Sypex Geo database version …` | a format other than 2.2 |
| `Truncated Sypex Geo database` | the file is shorter than its header says: the download or copy was interrupted |
| `Corrupted Sypex Geo database …`, `Invalid pack format …`, `Unknown database charset …` | the file is damaged |

The `Path` and `File` constructors prefix the message with the path.

## Sypex Geo vs MaxMind GeoLite2

| | Sypex Geo | MaxMind GeoLite2 |
|---|---|---|
| Getting the database | direct download, no account | free account, licence key, GeoLite2 EULA |
| IPv6 | no | yes |
| Other data | City Max: time zones and more | ASN database, time zones, postal codes |
| Strengths | Russia and CIS, Russian names of all countries, regions and cities | worldwide coverage, many languages |
| Java/Kotlin library | sypexgeo-java (no dependencies) or sypexgeo-kotlin (`kotlin-stdlib` only) | `com.maxmind.geoip2:geoip2` (with Jackson) |

## Performance

Measured with a simple timing loop (not JMH): one thread, 4 million calls over 65,536 random IPv4 strings (first octet 1–223) per method after 500,000 warm-up calls, OpenJDK 21, Intel Xeon 2.8 GHz (cloud VM). `SxGeo.dat` from January 2025 and a December 2014 `SxGeoCity.dat` (1.7 million ranges):

| Database | `country()` | `lookup()` |
|---|---|---|
| `SxGeo.dat` | ~0.2 µs | ~0.2 µs |
| `SxGeoCity.dat` | ~0.45 µs | ~2 µs |

That is millions of lookups per second per core. `country()` and `countryId()` on a city database read only the country id of the record (earlier versions decoded the whole record: ~0.9 µs). Memory use equals the file size.

## Limitations

- IPv4 only, because the Sypex Geo format has no IPv6 data.
- Only format 2.2 is supported, which covers all current databases.
- The whole database is kept in memory (a few tens of MB for City).

## Building and testing

```bash
./gradlew build
```

Most tests build synthetic databases in memory and check every range boundary against brute force, so they need no files. The integration tests use the real databases: put `SxGeo.dat` and `SxGeoCity.dat` into `testdata/`, otherwise those tests are skipped. CI downloads the databases automatically, at most once a month. Releases: [RELEASING.md](RELEASING.md).

## License and data

The library is under the [MIT](LICENSE) license. The algorithm is based on the official `SxGeo.php` (© zapimir, BINOVATOR, BSD license).

The library ships no geolocation data. The Sypex Geo databases are © BINOVATOR, [sypexgeo.net](https://sypexgeo.net/), and are distributed under the terms published there: check them before you redistribute `.dat` files (for example inside an application, an APK or a Docker image).
