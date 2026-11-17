// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
plugins {
    java
}

group = "pl.medivalrpg"
version = "1.0.0"

/** Official Anthropic Java SDK. Loaded at runtime by Paper from Maven Central via plugin.yml `libraries:` (NOT shaded). */
val anthropicSdkVersion = "3.3.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    // Kraje / Skarbiec / Armia / Ludnosc APIs: compile only, never packed (each class lives in its provider plugin).
    compileOnly(files("../medival-api/build/libs/medival-api.jar"))
    // SDK: compile only; the server downloads it (plugin.yml `libraries`), so the jar stays small.
    compileOnly("com.anthropic:anthropic-java:$anthropicSdkVersion")

    testImplementation("io.papermc.paper:paper-api:26.3.build.218-stable")
    testImplementation(files("../medival-api/build/libs/medival-api.jar"))
    testImplementation("com.anthropic:anthropic-java:$anthropicSdkVersion")
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
    val props = mapOf("version" to project.version, "anthropicSdkVersion" to anthropicSdkVersion)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    // Safety net: never ship classes of the APIs we only consume, nor the SDK.
    exclude("pl/medivalrpg/kraje/**", "pl/medivalrpg/skarbiec/**", "pl/medivalrpg/ludnosc/**", "pl/medivalrpg/armia/**", "com/anthropic/**")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

/** Prints the resolved SDK classpath (used to inspect the SDK with javap). */
tasks.register("sdkClasspath") {
    doLast {
        configurations["testRuntimeClasspath"].resolve().filter { it.name.startsWith("anthropic") || it.name.contains("jackson") || it.name.contains("okhttp") || it.name.contains("kotlin") }.forEach { println(it) }
    }
}
