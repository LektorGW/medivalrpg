// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
plugins {
    java
}

group = "pl.medivalrpg"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/") {
        content {
            includeGroup("com.sk89q.worldedit")
            includeGroup("com.sk89q.worldedit.worldedit-libs")
            includeGroupByRegex("org[.]enginehub.*")
        }
    }
}

// Shared MEDIVALRPG API (see ../medival-api). Kraje PROVIDES kraje-api: its sources are compiled into this jar.
// The other APIs (Skarbiec, Ludnosc, Armia) are compile-only: their classes come from the jar of the shared API,
// with the kraje packages filtered out so that kraje-api exists exactly once on the compile classpath.
val medivalApiJar = file("../medival-api/build/libs/medival-api.jar")
val foreignApiClasses = tasks.register<Sync>("foreignApiClasses") {
    from(zipTree(medivalApiJar)) {
        exclude("pl/medivalrpg/kraje/**", "META-INF/**")
    }
    into(layout.buildDirectory.dir("foreign-api"))
}

sourceSets {
    main {
        java.srcDir("../medival-api/kraje-api/src/main/java")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    compileOnly("xyz.jpenilla:squaremap-api:1.4.3")
    // WorldEdit is a softdepend, used only from pl.medivalrpg.kraje.integration.WorldEditSelection
    // (touched only when WorldEdit is enabled) for /kraj admin zajmij|porzuc <kraj> zaznaczenie.
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.6") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.6") { isTransitive = false }
    compileOnly(files(foreignApiClasses))
    // BetterModel (softdepend) for the country banners: only pl.medivalrpg.kraje.flag.BetterModelBanners touches it,
    // loaded when BetterModel is enabled. Not on Maven Central: compiled against the plugin jar (like Kolej/Interakcje).
    compileOnly(files(providers.gradleProperty("betterModelJar")
        .orElse("../downloads/bettermodel/bettermodel-3.6.0-SNAPSHOT-535-paper.jar")))

    // paper-api on the test classpath only (Gson / Adventure for codec tests); never shipped in the jar.
    testImplementation("io.papermc.paper:paper-api:26.3.build.218-stable")
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
    testLogging {
        events("passed", "skipped", "failed")
    }
}
