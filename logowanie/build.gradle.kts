// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
plugins {
    java
}

group = "pl.medivalrpg"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Runtime libraries that Paper itself ships (never packed into the jar): log4j-core for the console filter that
// hides passwords typed in /login, /register, ... and the SQLite JDBC driver (org.sqlite) for the account store
// and the read-only nLogin import. Same versions as bundled with Paper 26.3.
val log4jCore = "org.apache.logging.log4j:log4j-core:2.26.0"
val sqliteJdbc = "org.xerial:sqlite-jdbc:3.49.1.0"

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    compileOnly(log4jCore)

    testImplementation("io.papermc.paper:paper-api:26.3.build.218-stable")
    testImplementation(sqliteJdbc)
    testImplementation(log4jCore)
    // Reference Argon2 implementation (tests only): our pure-Java Argon2 must match it byte for byte.
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.79")
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

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    testLogging {
        events("passed", "skipped", "failed")
    }
}
