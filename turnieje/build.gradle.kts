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
            includeGroup("com.sk89q.worldguard")
            includeGroup("com.sk89q.worldedit.worldedit-libs")
            includeGroupByRegex("org[.]enginehub.*")
        }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    // Kraje / Skarbiec APIs: compile only, never packed (each class lives in its provider plugin).
    compileOnly(files("../medival-api/build/libs/medival-api.jar"))
    // WorldEdit is a softdepend, touched only from pl.medivalrpg.turnieje.arena.WorldEditSelection when enabled.
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.6") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.6") { isTransitive = false }
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.20") { isTransitive = false }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.20") { isTransitive = false }

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
    exclude("pl/medivalrpg/kraje/**", "pl/medivalrpg/skarbiec/**", "pl/medivalrpg/ludnosc/**", "pl/medivalrpg/armia/**",
        "pl/medivalrpg/postep/**", "pl/medivalrpg/zycie/**", "com/sk89q/**")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
