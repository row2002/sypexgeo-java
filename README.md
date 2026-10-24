# sypexgeo-java

[![CI](https://github.com/row2002/sypexgeo-java/actions/workflows/ci.yml/badge.svg)](https://github.com/row2002/sypexgeo-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.row2002/sypexgeo-java)](https://central.sonatype.com/artifact/io.github.row2002/sypexgeo-java)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**Русский** | [English](README.en.md)

Библиотека на Java для чтения баз [Sypex Geo](https://sypexgeo.net/) 2.2 (`SxGeo.dat`, `SxGeoCity.dat`, `SxGeoMax.dat`) — определение страны, региона и города по IPv4-адресу. Работает локально, без зависимостей во время выполнения, Java 17+.

## Быстрый старт

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
        SxGeo geo = new SxGeo(Path.of("SxGeoCity.dat"));    // один раз на приложение, потокобезопасен
        System.out.println(geo.country("77.88.8.8").orElse("XX")); // RU
        Optional<GeoInfo> info = geo.lookup("77.88.8.8");
        info.map(GeoInfo::city).ifPresent(city -> System.out.println(city.nameRu())); // Москва
    }
}
```

## Содержание

- [Какую библиотеку выбрать?](#какую-библиотеку-выбрать)
- [Возможности](#возможности)
- [Установка](#установка)
- [Где взять базу](#где-взять-базу)
- [Использование](#использование)
- [API](#api)
- [Рецепты](#рецепты): [Spring Boot](#spring-boot), [IP клиента за прокси](#ip-клиента-за-прокси), [servlet-фильтр](#servlet-фильтр), [обновление базы](#обновление-базы)
- [FAQ](#faq)
- [Sypex Geo или MaxMind GeoLite2](#sypex-geo-или-maxmind-geolite2)
- [Производительность](#производительность)
- [Сборка и тесты](#сборка-и-тесты)
- [Лицензия и данные](#лицензия-и-данные)

## Какую библиотеку выбрать?

| Ваш проект | Библиотека |
|---|---|
| Java 17+ | [sypexgeo-java](https://github.com/row2002/sypexgeo-java): `Optional`, records, без зависимостей |
| Kotlin, Android, Java 8–16 | [sypexgeo-kotlin](https://github.com/row2002/sypexgeo-kotlin): nullable-типы, data-классы, JVM 8+, зависит только от `kotlin-stdlib` |
| Kotlin на Java 17+ | любая; у sypexgeo-java есть аннотации JSpecify, и Kotlin правильно видит, где может быть `null` |

Никогда не подключайте обе: у них одинаковые пакет и имена классов (`io.github.row2002.sypexgeo.SxGeo`). Поведение у них одинаковое, включая разбор IP и тексты ошибок.

## Возможности

- без зависимостей во время выполнения: один класс плюс records для результата;
- работает локально с файлом базы, без сетевых запросов и лимитов;
- база целиком загружается в память, поиск занимает 0,2–2 мкс (см. [Производительность](#производительность));
- экземпляр неизменяемый и потокобезопасный — создайте один на всё приложение;
- поддерживаются UTF-8, CP1251 и Latin-1 версии баз;
- понятные ошибки: если вместо `.dat` передан ZIP-архив, HTML-страница с ошибкой или файл MaxMind, об этом так и будет сказано.

Порт официального `SxGeo.php` 2.2.3 с исправленной ошибкой на границах диапазонов: IP, совпадающий с первым адресом диапазона, всегда относится к этому диапазону (`SxGeo.php` иногда возвращает для него соседний). Каждая граница диапазонов проверяется в тестах на синтетических базах; тесты на настоящих базах проверяют случайные адреса.

## Установка

Библиотека публикуется в [Maven Central](https://central.sonatype.com/artifact/io.github.row2002/sypexgeo-java).

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

Старая версия 1.0.0 опубликована только в JitPack: `com.github.row2002:sypexgeo-java:1.0.0`. Она повторяла поведение SxGeo.php на границах диапазонов (IP, равный началу диапазона, мог попасть в соседний); с 1.1.0 это исправлено. Все изменения — в [CHANGELOG.md](CHANGELOG.md).

## Где взять базу

Скачайте базу на [sypexgeo.net/ru/download](https://sypexgeo.net/ru/download/) и распакуйте архив:

| Файл | Что внутри | Размер |
|---|---|---|
| `SxGeo.dat` ([SxGeoCountry.zip](https://sypexgeo.net/files/SxGeoCountry.zip)) | только страны | ~1 МБ |
| `SxGeoCity.dat` ([SxGeoCity_utf8.zip](https://sypexgeo.net/files/SxGeoCity_utf8.zip)) | страны, регионы, города | несколько десятков МБ |
| `SxGeoMax.dat` (платная City Max) | то же + дополнительные поля (часовые пояса и др.) | — |

```bash
curl -fLO https://sypexgeo.net/files/SxGeoCountry.zip && unzip SxGeoCountry.zip SxGeo.dat
curl -fLO https://sypexgeo.net/files/SxGeoCity_utf8.zip && unzip SxGeoCity_utf8.zip SxGeoCity.dat
```

Базы регулярно обновляются: дату сборки показывают страница загрузки и `info().created()`, см. [Обновление базы](#обновление-базы). Сама библиотека данных не содержит, см. [Лицензия и данные](#лицензия-и-данные).

## Использование

```java
import io.github.row2002.sypexgeo.GeoInfo;
import io.github.row2002.sypexgeo.SxGeo;
import java.nio.file.Path;
import java.util.Locale;

SxGeo geo = new SxGeo(Path.of("SxGeoCity.dat")); // один раз при старте приложения

geo.country("77.88.8.8");   // Optional[RU]
geo.countryId("77.88.8.8"); // OptionalInt[185]

geo.lookup("93.73.35.74").ifPresent(info -> {
    info.country().iso();     // "UA"
    info.country().nameRu();  // "Украина"
    info.region().nameEn();   // "Kyiv"   (region() может быть null)
    info.region().iso();      // "UA-30"
    info.city().nameRu();     // "Киев"   (city() может быть null)
    info.city().nameEn();     // "Kiev"
    info.city().name(Locale.forLanguageTag("ru-RU")); // "Киев"
    info.city().lat();        // 50.45466
    info.city().lon();        // 30.5238
});

String iso = geo.country(request.getRemoteAddr()).orElse("XX");
```

### Другие способы открыть базу

```java
new SxGeo(Path.of("/var/lib/geo/SxGeoCity.dat")); // файл
new SxGeo(new File("SxGeoCity.dat"));               // файл
try (InputStream in = getClass().getResourceAsStream("/SxGeo.dat")) {
    geo = new SxGeo(in);                             // ресурс или любой InputStream
}
new SxGeo(bytes);                                    // byte[], массив копируется
```

Конструктор с `InputStream` читает поток до конца и не закрывает его. База в любом случае целиком хранится в памяти.

### Kotlin

sypexgeo-java можно использовать из Kotlin: аннотации JSpecify сообщают компилятору, что может быть `null`.

```kotlin
val geo = SxGeo(Path.of("SxGeoCity.dat"))
val city: String? = geo.lookup(ip).orElse(null)?.city()?.nameEn() // ip: String? тоже подходит
```

Для Android и Java 8–16 берите [sypexgeo-kotlin](https://github.com/row2002/sypexgeo-kotlin) — там тот же API, но с nullable-типами вместо `Optional`.

## API

| Конструктор / метод | Описание |
|---|---|
| `new SxGeo(Path)`, `new SxGeo(File)` | читает файл в память |
| `new SxGeo(InputStream)` | читает поток до конца; поток не закрывается |
| `new SxGeo(byte[])` | копирует массив |
| `Optional<String> country(String ip)` | код страны ISO 3166-1 alpha-2 (`"RU"`); работает с любой базой |
| `OptionalInt countryId(String ip)` | id страны в Sypex Geo |
| `Optional<GeoInfo> lookup(String ip)` | страна, регион и город |
| `country(InetAddress)`, `countryId(InetAddress)`, `lookup(InetAddress)` | то же для `InetAddress`, по его байтам; для `Inet6Address` результат пустой |
| `DbInfo info()` | дата сборки базы, тип, кодировка, число диапазонов |
| `name(Locale)` у `Country`, `Region`, `City` | `nameRu` для русской локали, иначе `nameEn` (если одно из названий пустое — другое) |

Параметры `String ip` принимают `null` (результат пустой). Перегрузки `country(Inet4Address)` и `lookup(Inet4Address)` из 1.0 оставлены для двоичной совместимости.

```java
record GeoInfo(Country country, @Nullable Region region, @Nullable City city) {}
record Country(int id, String iso, String nameRu, String nameEn, double lat, double lon, Map<String, Object> fields) {}
record Region(int id, String iso, String nameRu, String nameEn, Map<String, Object> fields) {}
record City(int id, String nameRu, String nameEn, double lat, double lon, Map<String, Object> fields) {}
record DbInfo(String version, Instant created, String type, Charset charset, boolean hasCities, int ipBlocks) {}
```

Особенности:

- `country()` всегда заполнен, а `region()` и `city()` могут быть `null`.
- С базой стран (`SxGeo.dat`) `lookup` заполняет только `country().id()` и `country().iso()`: названия пустые, `region()` и `city()` равны `null`. Как получить название страны — в [FAQ](#faq).
- Если для диапазона IP известна только страна, `city()` и `region()` равны `null`, а в `country()` есть название и координаты.
- Страна в `lookup` — всегда страна города (её же возвращают `country` и `countryId`). Если у города нет региона (`region() == null`) или регион в базе ошибочно относится к другой стране, `country()` — полная запись этой страны из базы, с названиями и координатами. Если записи такой страны в базе нет, заполнены только `id` и `iso`.
- `name(Locale)` смотрит только на язык: `nameRu`, если `locale.getLanguage()` равен `"ru"` и `nameRu` не пустое, иначе `nameEn`, если оно не пустое, иначе `nameRu`. Локаль по умолчанию неявно не используется.
- `fields()` содержит все поля записи, кроме внутренних ссылок (`region_seek`, `country_seek`, `country_id`), в виде неизменяемой `Map`. В City Max там есть и дополнительные поля, например `timezone`:

```java
Object tz = info.region().fields().get("timezone"); // только в City Max
```

### IP-адреса

- IPv4 в виде четырёх десятичных чисел 0–255 через точку, по 1–3 цифры ASCII и без ведущих нулей (`"08.8.8.8"` и `"010.0.0.1"` не подходят, как и в PHP `ip2long`).
- Пробелы, табуляции и переводы строк (`' '`, `'\t'`, `'\n'`, `'\r'`) в начале и в конце строки отбрасываются, любые другие символы делают адрес неверным.
- Принимается IPv4-mapped IPv6 вида `::ffff:1.2.3.4` (префикс в любом регистре): так некоторые серверы показывают IPv4-клиентов.
- `host:port`, списки вроде `"77.88.8.8, 10.0.0.1"` и другие IPv6-адреса дают пустой результат, как и зарезервированные адреса (`0.*`, `10.*`, `127.*`) и `null`.
- Перегрузки с `InetAddress` берут байты адреса, без преобразования в строку. Для `Inet6Address` результат пустой; `::ffff:a.b.c.d` JDK и так возвращает как `Inet4Address`.

### Исключения

- Конструкторы: `IOException` — файл или поток не удалось прочитать; `IllegalArgumentException` — данные не являются базой Sypex Geo 2.2, повреждены (неверный заголовок, индекс или формат записей) или обрезаны. У конструкторов с `Path` и `File` сообщение начинается с пути к файлу, например `/srv/geo/SxGeoCity.zip: Not a Sypex Geo database: this is a ZIP archive - unzip it and pass the .dat file`. Расшифровка — в [FAQ](#что-означают-ошибки-конструктора).
- `country`, `countryId` и `lookup` не бросают исключений на неверный IP. Если же сама запись в базе повреждена (ссылается за пределы своего раздела или выходит за его конец), они бросают `IllegalStateException` с сообщением `Corrupted Sypex Geo database…`. С исправной базой этого не происходит.
- `country()` и `countryId()` разбирают из записи только id страны, поэтому повреждённую запись обнаружит `lookup()` (`IllegalStateException`), а `country()` может и не обнаружить.

### Сведения о базе

```java
geo.info().created();   // 2026-08-12T…Z
geo.info().type();      // "SxGeo City EN"
geo.info().hasCities(); // true
```

## Рецепты

### Spring Boot

Один экземпляр на всё приложение, путь — из `application.properties` (`sypexgeo.path=/var/lib/geo/SxGeoCity.dat`):

```java
@Configuration
class GeoConfig {
    @Bean
    SxGeo sxGeo(@Value("${sypexgeo.path}") Path path) throws IOException {
        return new SxGeo(path); // с битым файлом приложение не запустится
    }
}
```

Сервис, который подхватывает новый файл без перезапуска. Испорченный файл (недокопированный, HTML-страница с ошибкой) не пройдёт проверку в конструкторе, и продолжит работать старый экземпляр:

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

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES) // нужен @EnableScheduling
    public void reloadIfChanged() {
        try {
            FileTime modified = Files.getLastModifiedTime(db);
            if (modified.equals(loaded)) return;
            geo.set(new SxGeo(db)); // на испорченном файле бросит исключение, старый экземпляр останется
            loaded = modified;
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "Sypex Geo database not reloaded, keeping the old one", e);
        }
    }
}
```

### IP клиента за прокси

Не разбирайте `X-Forwarded-For` сами: этот заголовок может прислать любой клиент, и его первое значение — то, что написал клиент, так что кто угодно сможет выбрать себе страну. Пусть сервер подставит адрес клиента из заголовков только ваших **доверенных** прокси, а вы берите `request.getRemoteAddr()` (в Ktor — `call.request.origin.remoteAddress`):

- Spring Boot на Tomcat: `server.forward-headers-strategy=native`. Это `RemoteIpValve`: он доверяет только известным прокси (по умолчанию адресам частных сетей, другие задаются в `server.tomcat.remoteip.internal-proxies`) и читает `X-Forwarded-For` справа налево. Не используйте `framework` (`ForwardedHeaderFilter`) и `ForwardedRequestCustomizer` в Jetty, если ваш прокси не перезаписывает `X-Forwarded-For`: они берут его первое значение, то есть присланное клиентом.
- Отдельный Tomcat: `<Valve className="org.apache.catalina.valves.RemoteIpValve" />` в `server.xml`.
- Ktor: плагин `XForwardedHeaders` (зависимость `io.ktor:ktor-server-forwarded-header`), настроенный под вашу цепочку прокси: `install(XForwardedHeaders) { useLastProxy() }` для ровно одного прокси, который дописывает адрес в `X-Forwarded-For`, и `skipLastProxies(N - 1)` для N таких прокси.

Включайте это, только если приложение доступно исключительно через ваши прокси, иначе клиенты смогут подменить свой адрес.

### Servlet-фильтр

```java
import io.github.row2002.sypexgeo.SxGeo;
import jakarta.servlet.*; // javax.servlet.* для Servlet 4 / Spring Boot 2
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

В Spring Boot достаточно объявить фильтр бином: метод `@Bean`, который возвращает `new GeoFilter(geo)`, подключит его ко всем запросам. Чтобы задать URL-шаблоны или порядок, верните вместо него `FilterRegistrationBean<GeoFilter>`.

### Обновление базы

Sypex Geo регулярно обновляет базы; дату сборки показывают страница загрузки и `geo.info().created()`. Скачайте базу во временный файл рядом с текущим, проверьте её, открыв, затем переименуйте поверх старого файла (на той же файловой системе это атомарно) и подмените экземпляр; до этого момента запросы обслуживает старый:

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
        SxGeo fresh = new SxGeo(tmp); // испорченная загрузка: IllegalArgumentException
        Files.move(tmp, db, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        geo.set(fresh);
    } finally {
        Files.deleteIfExists(tmp);
    }
}
```

Или из cron, а файл подхватит `GeoService` выше:

```bash
curl -fsSL -o /tmp/SxGeoCity.zip https://sypexgeo.net/files/SxGeoCity_utf8.zip \
  && unzip -p /tmp/SxGeoCity.zip SxGeoCity.dat > /srv/geo/SxGeoCity.dat.tmp \
  && mv /srv/geo/SxGeoCity.dat.tmp /srv/geo/SxGeoCity.dat
```

## FAQ

### Поддерживается ли IPv6?

Нет. Формат Sypex Geo содержит только диапазоны IPv4; для IPv6-адресов результат пустой (IPv4-mapped `::ffff:a.b.c.d` обрабатывается как IPv4). Если нужен IPv6, используйте MaxMind GeoLite2.

### Какую базу выбрать?

`SxGeo.dat`, если нужна только страна: ~1 МБ вместо нескольких десятков МБ, и любой вызов (`country()`, `countryId()`, `lookup()`) занимает ~0,2 мкс. `SxGeoCity.dat` — для регионов и городов с названиями на русском и английском: `lookup()` на ней занимает ~2 мкс, `country()` и `countryId()` — ~0,4 мкс (см. [Производительность](#производительность)). `SxGeoMax.dat` (платная) добавляет поля вроде часовых поясов.

### Как часто обновлять базу?

Sypex Geo обновляет базы регулярно: дату последней сборки смотрите на [странице загрузки](https://sypexgeo.net/ru/download/), а дату загруженного файла — в `info().created()`. Новую базу можно подхватить без перезапуска, см. [Обновление базы](#обновление-базы).

### Что такое `AP`, `EU`, `A1`, `O1`?

Специальные коды в таблице стран Sypex Geo, унаследованные из старого списка стран GeoIP: `AP` — Азиатско-Тихоокеанский регион и `EU` — Европа, без конкретной страны; `A1` — анонимный прокси; `O1` — другая страна. Это не коды стран ISO 3166-1: обработайте их сами, прежде чем искать название страны или показывать флаг. `XK` (Косово) — код из пользовательского диапазона ISO 3166-1, JDK его знает.

### Как получить названия стран с `SxGeo.dat`?

В базе стран есть только ISO-коды, названия можно взять из JDK:

```java
String iso = geo.country(ip).orElse("");
Locale country = new Locale("", iso); // на JDK 19+: Locale.of("", iso)
String en = country.getDisplayCountry(Locale.ENGLISH);              // "Russia"
String ru = country.getDisplayCountry(Locale.forLanguageTag("ru")); // "Россия"
```

Для `AP`, `A1` и `O1` метод `getDisplayCountry` возвращает сам код, а для `EU` — «Европейский союз» (`"European Union"`), хотя Sypex Geo имеет в виду Европу: эти четыре кода обработайте сами. На JDK 19+ создавайте локаль через `Locale.of("", iso)`: конструктор `new Locale("", iso)` (в Kotlin — `Locale("", iso)`) работает на JDK 8+, но с JDK 19 устарел.

### Что означают ошибки конструктора?

| Сообщение | Значение |
|---|---|
| `Not a Sypex Geo database: this is a ZIP archive - unzip it and pass the .dat file` | передан скачанный `.zip` |
| `Not a Sypex Geo database: looks like an HTML page - the download probably failed` | сервер вернул страницу с ошибкой вместо файла |
| `Not a Sypex Geo database: this is a MaxMind .mmdb file` | база MaxMind; эта библиотека читает только Sypex Geo |
| `Not a Sypex Geo database: file is empty or too short (N bytes)` | пустой или оборванный файл |
| `Not a Sypex Geo database` | какой-то другой файл |
| `Unsupported Sypex Geo database version …` | формат, отличный от 2.2 |
| `Truncated Sypex Geo database` | файл короче, чем указано в заголовке: загрузка или копирование прервались |
| `Corrupted Sypex Geo database …`, `Invalid pack format …`, `Unknown database charset …` | файл повреждён |

Конструкторы с `Path` и `File` добавляют в начало сообщения путь к файлу.

## Sypex Geo или MaxMind GeoLite2

| | Sypex Geo | MaxMind GeoLite2 |
|---|---|---|
| Как получить базу | прямая ссылка, без регистрации | бесплатный аккаунт, лицензионный ключ, GeoLite2 EULA |
| IPv6 | нет | да |
| Другие данные | City Max: часовые пояса и др. | база ASN, часовые пояса, почтовые индексы |
| Сильные стороны | Россия и СНГ, русские названия всех стран, регионов и городов | весь мир, много языков |
| Библиотека для Java/Kotlin | sypexgeo-java (без зависимостей) или sypexgeo-kotlin (только `kotlin-stdlib`) | `com.maxmind.geoip2:geoip2` (с Jackson) |

## Производительность

Измерено простым циклом с замером времени (не JMH): один поток, 4 млн вызовов каждого метода по 65 536 случайным IPv4-строкам (первый октет 1–223) после 500 000 прогревочных вызовов, OpenJDK 21, Intel Xeon 2,8 ГГц (облачная виртуальная машина). `SxGeo.dat` от января 2025 года и `SxGeoCity.dat` от декабря 2014 года (1,7 млн диапазонов):

| База | `country()` | `lookup()` |
|---|---|---|
| `SxGeo.dat` | ~0,2 мкс | ~0,2 мкс |
| `SxGeoCity.dat` | ~0,45 мкс | ~2 мкс |

Это миллионы запросов в секунду на ядро. `country()` и `countryId()` на базе городов читают из записи только id страны (прежние версии разбирали всю запись: ~0,9 мкс). Памяти занято столько же, сколько весит файл.

## Ограничения

- Только IPv4: формат Sypex Geo не содержит IPv6.
- Поддерживается только формат 2.2, то есть все актуальные базы.
- База целиком хранится в памяти (для City — несколько десятков МБ).

## Сборка и тесты

```bash
./gradlew build
```

Основные тесты строят синтетические базы в памяти и сверяют каждую границу диапазонов с перебором, файлы для них не нужны. Интеграционные тесты используют настоящие базы: положите `SxGeo.dat` и `SxGeoCity.dat` в каталог `testdata/`, иначе эти тесты будут пропущены. В CI базы скачиваются автоматически, не чаще раза в месяц. Выпуск релизов описан в [RELEASING.md](RELEASING.md).

## Лицензия и данные

Библиотека распространяется по лицензии [MIT](LICENSE). Алгоритм основан на официальном `SxGeo.php` (© zapimir, BINOVATOR, лицензия BSD).

Библиотека не содержит геоданных. Базы Sypex Geo — © BINOVATOR, [sypexgeo.net](https://sypexgeo.net/), они распространяются на условиях, опубликованных там: проверьте их, прежде чем распространять `.dat`-файлы (например, внутри приложения, APK или Docker-образа).
