plugins {
    `java-library`
    `maven-publish`
}

group = "com.github.row2002"
version = findProperty("version")?.takeIf { it != "unspecified" } ?: "1.1.0" // JitPack passes -Pversion=<tag>

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile> {
    options.release = 17
    options.encoding = "UTF-8"
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "io.github.row2002.sypexgeo")
    }
}

tasks.test {
    useJUnitPlatform()
    // the integration tests read the real databases from testdata/: re-run them when it changes
    inputs.files(fileTree("testdata")).withPropertyName("testdata")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = "sypexgeo-java"
                description = "Reader for Sypex Geo 2.2 IP geolocation databases (SxGeo.dat, SxGeoCity.dat, SxGeoMax.dat)"
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
                        url = "https://github.com/row2002"
                    }
                }
                scm {
                    connection = "scm:git:https://github.com/row2002/sypexgeo-java.git"
                    developerConnection = "scm:git:ssh://git@github.com/row2002/sypexgeo-java.git"
                    url = "https://github.com/row2002/sypexgeo-java"
                }
            }
        }
    }
}
