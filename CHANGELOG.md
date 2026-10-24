# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - Unreleased

### Fixed

- An IP equal to the first address of a range (or of a main index entry) resolves to that range;
  1.0.0, like `SxGeo.php` 2.2.3, sometimes answered with the previous range.
- The search never reads outside the block table or the main index, and an empty first-octet group or a
  single block not starting at `X.0.0.0` no longer resolves to the next block.
- The truncation check includes the country records; the first-byte index and the pack formats are validated
  when the database is opened.
- Corrupted records make lookups throw `IllegalStateException("Corrupted Sypex Geo database: …")` instead of
  `ArrayIndexOutOfBoundsException`, `StringIndexOutOfBoundsException` or `NumberFormatException`.
- `c` (fixed-length string) fields drop trailing spaces only, like PHP `rtrim($s, ' ')`.

### Changed

- Strict IPv4 parsing: only ASCII whitespace (`' '`, `'\t'`, `'\n'`, `'\r'`) around the address is ignored and
  octets with leading zeros (`08.8.8.8`, `010.0.0.1`) are rejected, like PHP `ip2long`.
- When a city has no region or its region belongs to another country, `lookup()` returns the full record of the
  city's country (names, coordinates, fields) instead of only its id and ISO code.
- The constructors throw `IllegalArgumentException` for invalid, corrupted or truncated databases, with a message
  that says what the data is instead (a ZIP archive, an HTML page, a MaxMind `.mmdb` file, an empty file);
  the `Path` and `File` constructors prefix it with the path.
- The `byte[]` constructor copies the array.
- `country()` and `countryId()` on a city database read only the country id of the record: about twice as fast.
  They decode only the country id, so a corrupted record is reported by `lookup()` (`IllegalStateException`) but may
  not be by `country()` and `countryId()`.

### Added

- `SxGeo(File)` and `SxGeo(InputStream)` constructors (the stream is read to the end and not closed).
- `country(InetAddress)`, `countryId(InetAddress)` and `lookup(InetAddress)`, computed from the address bytes;
  an `Inet6Address` gives an empty result. The `Inet4Address` overloads remain.
- IPv4-mapped IPv6 strings `::ffff:a.b.c.d` (prefix in any case) are accepted.
- `name(Locale)` on `Country`, `Region` and `City`: the Russian name for a Russian locale, otherwise the English one.
- JSpecify nullability annotations (`@NullMarked` package, `@Nullable` `GeoInfo.region`/`city` and `String ip`
  parameters), so Kotlin sees `Region?` and `City?`; a compile-time-only dependency.
- `Automatic-Module-Name: io.github.row2002.sypexgeo` and full POM metadata.
- Published to Maven Central as `io.github.row2002:sypexgeo-java`.
- Hermetic tests on synthetic databases; README recipes (Spring Boot, client IP behind a proxy, servlet filter,
  database updates), FAQ and measured performance.

## [1.0.0] - 2026-10-24

- First release: reader for Sypex Geo 2.2 databases (`SxGeo.dat`, `SxGeoCity.dat`, `SxGeoMax.dat`) with
  `country()`, `countryId()`, `lookup()` and `info()`.

[1.1.0]: https://github.com/row2002/sypexgeo-java/compare/1.0.0...HEAD
[1.0.0]: https://github.com/row2002/sypexgeo-java/releases/tag/1.0.0
