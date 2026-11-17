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

sourceSets {
    main {
        // Armia PROVIDES pl.medivalrpg.armia.api: compiled into (and shipped only by) this jar.
        java.srcDir("../medival-api/armia-api/src/main/java")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    // Kraje / Skarbiec / Ludnosc APIs: compile only, never packed (each class lives in its provider plugin).
    compileOnly(files("../medival-api/build/libs/medival-api.jar"))
    // BetterModel 3.6.0-SNAPSHOT-535 (softdepend, the version on the server) is not on Maven Central: compiled
    // against the plugin jar like Kolej/Interakcje. Only pl.medivalrpg.armia.visual.bm touches it.
    compileOnly(files(providers.gradleProperty("betterModelJar")
        .orElse("../downloads/bettermodel/bettermodel-3.6.0-SNAPSHOT-535-paper.jar")))

    // Decision logic under test is plain Java; paper-api only for the YAML/messages test (never shipped).
    testImplementation("io.papermc.paper:paper-api:26.3.build.218-stable")
    testImplementation(files("../medival-api/build/libs/medival-api.jar"))
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
    // Safety net: never ship classes of the APIs we only consume.
    exclude("pl/medivalrpg/kraje/**", "pl/medivalrpg/skarbiec/**", "pl/medivalrpg/ludnosc/**")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
