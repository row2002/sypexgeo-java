import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.JavaLibrary

plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.35.0"
}

// JitPack (it sets JITPACK=true) publishes com.github.row2002:sypexgeo-java:<tag>; Maven Central uses io.github.row2002
group = if (System.getenv("JITPACK") == "true") "com.github.row2002" else "io.github.row2002"
version = findProperty("version")?.takeIf { it != "unspecified" } ?: "1.1.0" // the release workflow and JitPack pass -Pversion=<tag>

repositories {
    mavenCentral()
}

dependencies {
    // nullability annotations for Kotlin and static analysis; not needed at run time
    compileOnly("org.jspecify:jspecify:1.0.0")
    testCompileOnly("org.jspecify:jspecify:1.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withJavadocJar() // part of assemble, published through the java component
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

// sources and javadoc jars, POM; ./gradlew publishToMavenCentral needs the mavenCentralUsername/mavenCentralPassword
// properties (see RELEASING.md), artifacts are signed only when a signing key is given (a blank one counts as none)
mavenPublishing {
    configure(JavaLibrary(javadocJar = JavadocJar.None(), sourcesJar = true)) // the javadoc jar comes from withJavadocJar()
    publishToMavenCentral()
    if (!providers.gradleProperty("signingInMemoryKey").orNull.isNullOrBlank()) signAllPublications()
    coordinates(group.toString(), "sypexgeo-java", version.toString())
    pom {
        name = "sypexgeo-java"
        description = "Reader for Sypex Geo 2.2 IP geolocation databases (SxGeo.dat, SxGeoCity.dat, SxGeoMax.dat)"
        inceptionYear = "2026"
        url = "https://github.com/row2002/sypexgeo-java"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
                distribution = "repo"
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
            connection = "scm:git:https://github.com/row2002/sypexgeo-java.git"
            developerConnection = "scm:git:ssh://git@github.com/row2002/sypexgeo-java.git"
            url = "https://github.com/row2002/sypexgeo-java"
        }
    }
}
