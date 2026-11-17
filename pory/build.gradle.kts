// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
plugins {
    java
}

group = "pl.medivalrpg"
version = "1.0.0"

repositories {
    mavenCentral()
}

// Pory uses server internals (Paper 26.3 is unobfuscated, no remapping): compiled against the local Paper 26.3 #218
// runtime from testserver/ (server jar + its libraries, incl. paper-api). Nothing of it is shipped in the jar.
val serverJar = file("../testserver/versions/26.3/paper-26.3.jar")
val serverLibraries = fileTree("../testserver/libraries") { include("**/*.jar") }
val medivalApiJar = file("../medival-api/build/libs/medival-api.jar")

dependencies {
    compileOnly(files(serverJar))
    compileOnly(serverLibraries)
    compileOnly(files(medivalApiJar))

    // Unit tests cover the pure logic only (palette rewriting, mapping, coverage, config parsing).
    testImplementation(serverLibraries)
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    exclude("pl/medivalrpg/ludnosc/**", "pl/medivalrpg/kraje/**", "pl/medivalrpg/skarbiec/**")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
