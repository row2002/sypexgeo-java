# sypexgeo-java

**Русский** | [English](README.en.md)

Библиотека на Java для чтения баз [Sypex Geo](https://sypexgeo.net/) 2.2 (`SxGeo.dat`, `SxGeoCity.dat`, `SxGeoMax.dat`) — определение страны, региона и города по IPv4-адресу.

- без зависимостей, один класс плюс records для результата;
- работает локально с файлом базы, без сетевых запросов и лимитов;
- база целиком загружается в память, поиск — несколько микросекунд;
- экземпляр неизменяемый и потокобезопасный — создайте один на всё приложение;
- Java 17+;
- поддерживаются UTF-8, CP1251 и Latin-1 версии баз.

Порт официального `SxGeo.php` 2.2.3. Результаты сверены на 30 000 случайных IP с [pysyge](https://github.com/idlesign/pysyge) — независимым портом `SxGeo.php`. Версия для Kotlin (и для Java 8+) — [sypexgeo-kotlin](https://github.com/row2002/sypexgeo-kotlin).

## Установка

Библиотека опубликована в [Maven Central](https://central.sonatype.com/artifact/io.github.row2002/sypexgeo-java).

**Maven**

```xml
<dependency>
    <groupId>io.github.row2002</groupId>
    <artifactId>sypexgeo-java</artifactId>
    <version>1.0.1</version>
</dependency>
```

**Gradle**

```kotlin
dependencies {
    implementation("io.github.row2002:sypexgeo-java:1.0.1")
}
```

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

Если IP нет в базе, он зарезервирован (`10.*`, `127.*`, `0.*`), записан неверно или равен `null`, методы возвращают пустой `Optional` и не бросают исключений. Если IP берётся из заголовка вроде `X-Forwarded-For`, сначала выделите из него один адрес (первый или последний доверенный): строка вида `"77.88.8.8, 10.0.0.1"` целиком вернёт пустой результат.

### Другие способы открыть базу

```java
new SxGeo(Path.of("/var/lib/geo/SxGeoCity.dat"));                     // файл
new SxGeo(getClass().getResourceAsStream("/SxGeo.dat").readAllBytes()); // ресурс или любой InputStream
```

Конструктор бросает `IllegalArgumentException`, если данные не являются базой Sypex Geo 2.2 или обрезаны, и `IOException`, если файл не удалось прочитать.

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
private volatile SxGeo geo = new SxGeo(DB);

void reload() throws IOException {
    geo = new SxGeo(DB);
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
record GeoInfo(Country country, Region region, City city)
record Country(int id, String iso, String nameRu, String nameEn, double lat, double lon, Map<String, Object> fields)
record Region(int id, String iso, String nameRu, String nameEn, Map<String, Object> fields)
record City(int id, String nameRu, String nameEn, double lat, double lon, Map<String, Object> fields)
```

Особенности:

- `country()` всегда заполнен, а `region()` и `city()` могут быть `null`.
- С базой стран (`SxGeo.dat`) `lookup` заполняет только `country().id()` и `country().iso()`, а `region()` и `city()` равны `null`.
- Если для диапазона IP известна только страна, `city()` и `region()` равны `null`, а в `country()` есть название и координаты.
- Если у города нет региона, `region() == null`, а в `country()` заполнены только `id` и `iso`. То же с `country()` бывает, когда регион в базе ошибочно относится к другой стране: страна всегда берётся из записи города, поэтому `lookup` и `country` не расходятся.
- `fields()` содержит все поля записи из базы (неизменяемая `Map`). В City Max там есть и дополнительные поля, например `timezone`:

```java
Object tz = info.region().fields().get("timezone"); // только в City Max
```

### Сведения о базе

```java
geo.info().created();   // 2026-08-12T…Z
geo.info().type();      // "SxGeo City EN"
geo.info().hasCities(); // true
```

## Ограничения

- Только IPv4: формат Sypex Geo не содержит IPv6. Для IPv6-адреса методы вернут пустой `Optional`.
- Поддерживается только формат 2.2, то есть все актуальные базы.
- Как и в `SxGeo.php`, для IP, который ровно совпадает с началом диапазона, иногда возвращается соседний диапазон. Это особенность эталонного алгоритма, сохранённая ради совместимости.
- База занимает в памяти столько же, сколько файл (~40 МБ для City).

## Сборка и тесты

```bash
./gradlew test
```

Интеграционные тесты используют настоящие базы. Положите `SxGeo.dat` и `SxGeoCity.dat` в каталог `testdata/`, иначе эти тесты будут пропущены. В CI базы скачиваются автоматически.

## Лицензия

[MIT](LICENSE). Алгоритм основан на официальном `SxGeo.php` (© zapimir, BINOVATOR, лицензия BSD). Базы Sypex Geo распространяются на условиях, указанных на [sypexgeo.net](https://sypexgeo.net/).
