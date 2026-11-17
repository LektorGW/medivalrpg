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
            includeGroup("com.sk89q.worldguard")
            includeGroup("com.sk89q.worldguard.worldguard-libs")
            includeGroupByRegex("org[.]enginehub.*")
        }
    }
}

// Kolej is a pure CONSUMER of the shared MEDIVALRPG API (Kraje, Skarbiec, Ludnosc, Armia): compile-only, never shipped.
val medivalApiJar = file("../medival-api/build/libs/medival-api.jar")

// BetterModel 3.6.0-SNAPSHOT-535 (the version on the server) is not on Maven Central, so the API is compiled against
// the plugin jar itself (same as Interakcje). Only pl.medivalrpg.kolej.visual.BetterModelRenderer touches it.
val betterModelJar = providers.gradleProperty("betterModelJar")
    .orElse("../downloads/bettermodel/bettermodel-3.6.0-SNAPSHOT-535-paper.jar")

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    compileOnly(files(medivalApiJar))
    compileOnly(files(betterModelJar))
    // WorldGuard is a softdepend used only from pl.medivalrpg.kolej.world.WorldGuardProtection (loaded by reflection
    // when WorldGuard is enabled) so crews never build inside a protected region.
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.6") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.6") { isTransitive = false }
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.20") { isTransitive = false }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.20") { isTransitive = false }

    // Pure logic is tested without a server; paper-api only for YAML/Gson in tests (never shipped).
    testImplementation("io.papermc.paper:paper-api:26.3.build.218-stable")
    testImplementation(files(medivalApiJar))
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

tasks.jar {
    archiveFileName.set("Kolej-${project.version}.jar")
}
