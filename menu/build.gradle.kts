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

// Menu has NO compile-time dependency on other MEDIVALRPG plugins: buttons run commands as the player,
// Kraje (KrajeApi), Interakcje (config.yml) and nLogin (AuthenticateEvent) are reached via reflection/Bukkit API,
// so an older or missing plugin never breaks class loading.
// WorldEdit and WorldGuard are softdepends used only from pl.medivalrpg.menu.build.WorldEditPaster /
// WorldGuardRegion, which are loaded only when the plugin is enabled.
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.6") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.6") { isTransitive = false }
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.20") { isTransitive = false }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.20") { isTransitive = false }

    // paper-api on the test classpath only (YamlConfiguration, Adventure); never shipped in the jar.
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

tasks.jar {
    archiveFileName.set("Menu-${project.version}.jar")
}
