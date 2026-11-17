// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
import java.util.zip.ZipFile

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
        // Skarbiec PROVIDES skarbiec-api: its sources are compiled into (and shipped in) this jar.
        java.srcDir("../medival-api/skarbiec-api/src/main/java")
    }
}

// Optional Kraje integration (shop territory check). medival-api.jar also contains skarbiec-api, which this
// project compiles from source, so a copy of the jar WITHOUT pl/medivalrpg/skarbiec/** is used for compilation:
// no duplicate classes on the compile classpath, and nothing from it is ever packaged (compileOnly).
val externalApiJar = tasks.register<Jar>("externalApiJar") {
    description = "medival-api.jar without the skarbiec-api classes (compile-only view of the other plugins' APIs)."
    archiveFileName = "medival-api-external.jar"
    destinationDirectory = layout.buildDirectory.dir("external-api")
    from(zipTree("../medival-api/build/libs/medival-api.jar")) {
        exclude("pl/medivalrpg/skarbiec/**")
        exclude("META-INF/**")
    }
}

// BetterModel 3.6.0-SNAPSHOT-535 (the version on the server) is not on Maven Central, so the API is compiled against the
// plugin jar itself (same as Kolej and Interakcje). Only pl.medivalrpg.skarbiec.npc.StallModels touches it.
val betterModelJar = providers.gradleProperty("betterModelJar")
    .orElse("../downloads/bettermodel/bettermodel-3.6.0-SNAPSHOT-535-paper.jar")

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    compileOnly(files(externalApiJar))
    compileOnly(files(betterModelJar))

    // paper-api on the test classpath only (Gson / Adventure / YAML for tests); never shipped in the jar.
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

tasks.jar {
    archiveFileName = "Skarbiec-${project.version}.jar"
}

// Guard: the shipped jar must contain skarbiec-api and nothing from the other plugins' APIs.
val verifyJarContents = tasks.register("verifyJarContents") {
    val jarFile = tasks.jar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    doLast {
        val names = ZipFile(jarFile.get().asFile).use { zip ->
            zip.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toList()
        }
        val foreign = names.filter { n ->
            listOf("pl/medivalrpg/kraje/", "pl/medivalrpg/ludnosc/", "pl/medivalrpg/armia/", "kr/toxicity/").any { n.startsWith(it) }
        }
        check(foreign.isEmpty()) { "Jar zawiera cudze klasy API: $foreign" }
        check(names.any { it.endsWith("pl/medivalrpg/skarbiec/api/SkarbiecApi.class") }) { "Brak SkarbiecApi w jarze" }
    }
}
tasks.build { dependsOn(verifyJarContents) }

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
