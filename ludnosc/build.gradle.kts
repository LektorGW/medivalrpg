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

// Shared MEDIVALRPG API jar (Kraje, Skarbiec, Armia, Ludnosc). Ludnosc PROVIDES ludnosc-api (compiled from source
// below), so its classes are excluded from the compile-only jar to avoid duplicates; the other APIs are never shipped.
val medivalApi = files("../medival-api/build/libs/medival-api.jar")
val foreignApi = zipTree(medivalApi.singleFile).matching { exclude("pl/medivalrpg/ludnosc/**") }
val foreignApiDir = layout.buildDirectory.dir("foreign-api")
val extractForeignApi = tasks.register<Sync>("extractForeignApi") {
    from(foreignApi)
    into(foreignApiDir)
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    compileOnly(files(foreignApiDir).builtBy(extractForeignApi))
    // WorldEdit is a softdepend: used only from pl.medivalrpg.ludnosc.hook.WorldEditHook, loaded when present.
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.6") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.6") { isTransitive = false }

    // paper-api on the test classpath only (Gson / Adventure / YAML for pure-logic tests); never shipped in the jar.
    testImplementation("io.papermc.paper:paper-api:26.3.build.218-stable")
    testImplementation(files(foreignApiDir).builtBy(extractForeignApi))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    main {
        java.srcDir("../medival-api/ludnosc-api/src/main/java")
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
    // Safety net: only our own packages (incl. the ludnosc-api we provide) end up in the jar.
    exclude("pl/medivalrpg/kraje/**", "pl/medivalrpg/skarbiec/**", "pl/medivalrpg/armia/**")
}

tasks.test {
    useJUnitPlatform()
    // ./gradlew test -Dludnosc.preview=true renders the town simulation PNGs into preview/
    systemProperty("ludnosc.preview", System.getProperty("ludnosc.preview") ?: "false")
    workingDir = projectDir
    testLogging {
        events("passed", "skipped", "failed")
    }
}
