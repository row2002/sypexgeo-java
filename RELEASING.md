# Releasing

Releases are published by tag. [JitPack](https://jitpack.io/#row2002/sypexgeo-java) builds every tag by itself as
`com.github.row2002:sypexgeo-java:<tag>`. The [release workflow](.github/workflows/release.yml) builds the tag,
publishes it to Maven Central as `io.github.row2002:sypexgeo-java:<version>` when the secrets below are set
(otherwise it skips this step with a notice) and creates a GitHub release with the tag's section of `CHANGELOG.md`.

The group depends on the environment: JitPack sets `JITPACK=true` and gets `com.github.row2002`, every other build
uses `io.github.row2002` (see `build.gradle.kts`). Check both locally:

```bash
JITPACK=true ./gradlew publishToMavenLocal   # ~/.m2/repository/com/github/row2002/sypexgeo-java/
./gradlew publishToMavenLocal                # ~/.m2/repository/io/github/row2002/sypexgeo-java/
```

## One-time setup for Maven Central

1. Sign in to the [Central Portal](https://central.sonatype.com/) with the `row2002` GitHub account.
   The namespace `io.github.row2002` is then verified automatically; otherwise add it under
   *Namespaces* and create the temporary public repository it asks for.
2. *Account* → *Generate User Token*: a username and a password for publishing.
3. Create a GPG key for signing and publish its public part:

   ```bash
   gpg --full-generate-key
   gpg --list-secret-keys --keyid-format short
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID>   # the value of SIGNING_KEY
   ```

4. Add the repository secrets (*Settings* → *Secrets and variables* → *Actions*):

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | user token username |
   | `MAVEN_CENTRAL_PASSWORD` | user token password |
   | `SIGNING_KEY` | ASCII-armored private key |
   | `SIGNING_KEY_PASSWORD` | its passphrase |

   They reach Gradle as the `mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey` and
   `signingInMemoryKeyPassword` properties of the [vanniktech maven-publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/central/);
   for a manual release put them into `~/.gradle/gradle.properties` and run
   `./gradlew publishAndReleaseToMavenCentral -Pversion=<version>`.

After the first successful publication, add the Maven Central coordinates to the "Installation" sections of
`README.md` and `README.en.md`.

## Release procedure

1. In `CHANGELOG.md`, rename `## [x.y.z] — Unreleased` to `## [x.y.z] — YYYY-MM-DD` and update the links at the end.
2. Make sure the version in `build.gradle.kts` (the default when no `-Pversion` is given) and in the READMEs is `x.y.z`.
3. Commit, then tag and push (existing tags have no `v` prefix; `vX.Y.Z` works too):

   ```bash
   git tag x.y.z
   git push origin main x.y.z
   ```

4. Check the *Release* workflow run, the GitHub release and, if configured, the deployment in the Central Portal.
5. Open `https://jitpack.io/#row2002/sypexgeo-java/x.y.z` to make JitPack build the tag right away.

Do the same in [sypexgeo-kotlin](https://github.com/row2002/sypexgeo-kotlin), keeping the versions of the two libraries in step.
