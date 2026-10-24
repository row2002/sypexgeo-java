plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.35.0"
}

group = "io.github.row2002"
version = findProperty("version")?.takeIf { it != "unspecified" } ?: "1.0.0" // CI and JitPack pass -Pversion=<tag>

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.release = 17
    options.encoding = "UTF-8"
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral()
    // only when a key is given (CI), so publishToMavenLocal works without one
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    pom {
        name = "sypexgeo-java"
        description = "Java reader for Sypex Geo IP geolocation databases (SxGeo.dat, SxGeoCity.dat) — offline, no dependencies"
        url = "https://github.com/row2002/sypexgeo-java"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                id = "row2002"
                name = "aevseev"
                url = "https://github.com/row2002"
            }
        }
        scm {
            url = "https://github.com/row2002/sypexgeo-java"
            connection = "scm:git:https://github.com/row2002/sypexgeo-java.git"
            developerConnection = "scm:git:ssh://git@github.com/row2002/sypexgeo-java.git"
        }
    }
}
