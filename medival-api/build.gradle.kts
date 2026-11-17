// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
//
// Compile-only API jar shared by the MEDIVALRPG plugins (Skarbiec, Kraje, Ludnosc, Armia, Postep).
//
// Each PROVIDER plugin compiles its own API module into its jar via
//   sourceSets.main { java.srcDir("../medival-api/<x>-api/src/main/java") }
// and every CONSUMER uses
//   compileOnly(files("../medival-api/build/libs/medival-api.jar"))
// so each API class exists exactly once at runtime (inside its provider plugin).
plugins {
    `java-library`
}

group = "pl.medivalrpg"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
}

sourceSets {
    main {
        java.srcDir("skarbiec-api/src/main/java")
        java.srcDir("kraje-api/src/main/java")
        java.srcDir("armia-api/src/main/java")
        java.srcDir("ludnosc-api/src/main/java")
        java.srcDir("postep-api/src/main/java")
        java.srcDir("zycie-api/src/main/java")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.jar {
    archiveFileName = "medival-api.jar"
}
