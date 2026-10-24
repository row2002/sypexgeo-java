# sypexgeo-java

**Русский** | [English](README.en.md)

Библиотека на Java для чтения баз [Sypex Geo](https://sypexgeo.net/) 2.2 (`SxGeo.dat`, `SxGeoCity.dat`, `SxGeoMax.dat`) — определение страны, региона и города по IPv4-адресу.

- без зависимостей, один класс плюс records для результата;
- работает локально с файлом базы, без сетевых запросов и лимитов;
- база целиком загружается в память, поиск — несколько микросекунд;
- экземпляр неизменяемый и потокобезопасный — создайте один на всё приложение;
- Java 17+;
- поддерживаются UTF-8, CP1251 и Latin-1 версии баз.

Порт официального `SxGeo.php` 2.2.3 с исправленной ошибкой на границах диапазонов: IP, совпадающий с первым адресом диапазона, всегда относится к этому диапазону (`SxGeo.php` иногда возвращает для него соседний). Ответы проверены перебором: на настоящих базах — для начала каждого диапазона и адреса перед ним, на синтетических — для всех границ диапазонов и индексов. Версия для Kotlin (и для Java 8+) — [sypexgeo-kotlin](https://github.com/row2002/sypexgeo-kotlin).

## Установка

Библиотека публикуется через [JitPack](https://jitpack.io/#row2002/sypexgeo-java).

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

Версия 1.0.0 повторяла поведение SxGeo.php на границах диапазонов (IP, равный началу диапазона, мог попасть в соседний); с 1.1.0 это исправлено.

## Где взять базу

Скачайте базу на [sypexgeo.net/ru/download](https://sypexgeo.net/ru/download/) и распакуйте архив:

| Файл | Что внутри | Размер |
|---|---|---|
| `SxGeo.dat` ([SxGeoCountry.zip](https://sypexgeo.net/files/SxGeoCountry.zip)) | только страны | ~0,8 МБ |
| `SxGeoCity.dat` ([SxGeoCity_utf8.zip](https://sypexgeo.net/files/SxGeoCity_utf8.zip)) | страны, регионы, города | ~40 МБ |
| `SxGeoMax.dat` (платная City Max) | то же + дополнительные поля (часовые пояса и др.) | — |

Базы обновляются регулярно — перекачивайте их хотя бы раз в месяц.

## Использование

```java
import io.github.row2002.sypexgeo.GeoInfo;
import io.github.row2002.sypexgeo.SxGeo;
import java.nio.file.Path;

SxGeo geo = new SxGeo(Path.of("SxGeoCity.dat")); // один раз при старте приложения

geo.country("77.88.8.8");   // Optional[RU]
geo.countryId("77.88.8.8"); // OptionalInt[185]

geo.lookup("93.73.35.74").ifPresent(info -> {
    info.country().iso();     // "UA"
    info.country().nameRu();  // "Украина"
    info.region().nameEn();   // "Kyiv"   (region() может быть null)
    info.region().iso();      // "UA-30"
    info.city().nameRu();     // "Киев"   (city() может быть null)
    info.city().nameEn();     // "Kyiv"
    info.city().lat();        // 50.45466
    info.city().lon();        // 30.5238
});

String iso = geo.country(request.getRemoteAddr()).orElse("XX");
```

Если IP нет в базе, он зарезервирован (`10.*`, `127.*`, `0.*`), записан неверно или равен `null`, методы возвращают пустой `Optional` и не бросают исключений. Адрес принимается только в виде четырёх десятичных чисел 0–255 через точку, по 1–3 цифры ASCII и без ведущих нулей: `"08.8.8.8"` и `"010.0.0.1"` не подходят, как и в PHP `ip2long`. Пробелы, табуляции и переводы строк (`' '`, `'\t'`, `'\n'`, `'\r'`) в начале и в конце строки отбрасываются, любые другие символы делают адрес неверным. Если IP берётся из заголовка вроде `X-Forwarded-For`, сначала выделите из него один адрес (первый или последний доверенный): строка вида `"77.88.8.8, 10.0.0.1"` целиком вернёт пустой результат.

### Другие способы открыть базу

```java
new SxGeo(Path.of("/var/lib/geo/SxGeoCity.dat"));                     // файл
new SxGeo(getClass().getResourceAsStream("/SxGeo.dat").readAllBytes()); // ресурс или любой InputStream
```

Конструктор с `byte[]` копирует массив. Конструкторы бросают `IllegalArgumentException`, если данные не являются базой Sypex Geo 2.2, повреждены (неверный заголовок, индекс или формат записей) или обрезаны, и `IOException`, если файл не удалось прочитать.

### Spring

```java
@Bean
SxGeo sxGeo(@Value("${geo.db:SxGeoCity.dat}") Path db) throws IOException {
    return new SxGeo(db);
}
```

### Обновление базы без перезапуска

Экземпляр неизменяемый, так что достаточно подменить ссылку:

```java
class Geo {
    private static final Path DB = Path.of("SxGeoCity.dat");
    private volatile SxGeo geo;

    Geo() throws IOException {
        reload();
    }

    void reload() throws IOException {
        geo = new SxGeo(DB); // пока новая база читается, запросы обслуживает старая
    }

    Optional<String> country(String ip) {
        return geo.country(ip);
    }
}
```

## API

| Метод | Возвращает |
|---|---|
| `Optional<String> country(String ip)` | ISO-код страны (`"RU"`); работает с любой базой |
| `OptionalInt countryId(String ip)` | id страны в Sypex Geo |
| `Optional<GeoInfo> lookup(String ip)` | страна, регион и город |
| `country(Inet4Address)`, `lookup(Inet4Address)` | то же для `Inet4Address` |
| `DbInfo info()` | дата сборки базы, тип, кодировка, число диапазонов |

```java
record GeoInfo(Country country, Region region, City city) {}
record Country(int id, String iso, String nameRu, String nameEn, double lat, double lon, Map<String, Object> fields) {}
record Region(int id, String iso, String nameRu, String nameEn, Map<String, Object> fields) {}
record City(int id, String nameRu, String nameEn, double lat, double lon, Map<String, Object> fields) {}
```

Особенности:

- `country()` всегда заполнен, а `region()` и `city()` могут быть `null`.
- С базой стран (`SxGeo.dat`) `lookup` заполняет только `country().id()` и `country().iso()`, а `region()` и `city()` равны `null`.
- Если для диапазона IP известна только страна, `city()` и `region()` равны `null`, а в `country()` есть название и координаты.
- Страна в `lookup` — всегда страна города (её же возвращают `country` и `countryId`). Если у города нет региона (`region() == null`) или регион в базе ошибочно относится к другой стране, `country()` — полная запись этой страны из базы, с названиями и координатами. Если записи такой страны в базе нет, заполнены только `id` и `iso`.
- `fields()` содержит все поля записи, кроме внутренних ссылок (`region_seek`, `country_seek`, `country_id`), в виде неизменяемой `Map`. В City Max там есть и дополнительные поля, например `timezone`:

```java
Object tz = info.region().fields().get("timezone"); // только в City Max
```

### Исключения

- Конструкторы: `IllegalArgumentException` — данные не являются базой Sypex Geo 2.2, повреждены или обрезаны; `IOException` — файл не удалось прочитать.
- `country`, `countryId`, `lookup` не бросают исключений на неверный IP. Если же сама запись в базе повреждена (ссылается за пределы своего раздела или выходит за его конец), они бросают `IllegalStateException` с сообщением `Corrupted Sypex Geo database…`. С исправной базой этого не происходит.

### Сведения о базе

```java
geo.info().created();   // 2026-08-12T…Z
geo.info().type();      // "SxGeo City EN"
geo.info().hasCities(); // true
```

## Ограничения

- Только IPv4: формат Sypex Geo не содержит IPv6. Для IPv6-адреса методы вернут пустой `Optional`.
- Поддерживается только формат 2.2, то есть все актуальные базы.
- База занимает в памяти столько же, сколько файл (~40 МБ для City).

## Сборка и тесты

```bash
./gradlew test
```

Основные тесты строят синтетические базы в памяти и сверяют каждую границу диапазонов с перебором, файлы для них не нужны. Интеграционные тесты используют настоящие базы: положите `SxGeo.dat` и `SxGeoCity.dat` в каталог `testdata/`, иначе эти тесты будут пропущены. В CI базы скачиваются автоматически, не чаще раза в месяц.

## Лицензия

[MIT](LICENSE). Алгоритм основан на официальном `SxGeo.php` (© zapimir, BINOVATOR, лицензия BSD). Базы Sypex Geo распространяются на условиях, указанных на [sypexgeo.net](https://sypexgeo.net/).
