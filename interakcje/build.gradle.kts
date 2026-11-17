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

// BetterModel 3.6.0-SNAPSHOT-535 (the version on the server) is not on Maven Central (only 3.5.1 there,
// snapshots need GitHub credentials), so the API is compiled against the plugin jar itself.
val betterModelJar = providers.gradleProperty("betterModelJar")
    .orElse("../downloads/bettermodel/bettermodel-3.6.0-SNAPSHOT-535-paper.jar")

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    compileOnly(files(betterModelJar))
    // Kraje API (ruler check for the coronation / knighting); never shipped, looked up at runtime (softdepend)
    compileOnly(files("../medival-api/build/libs/medival-api.jar"))

    // paper-api on the test classpath only (YamlConfiguration for config parsing tests); never shipped in the jar.
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
    archiveFileName.set("Interakcje-${project.version}.jar")
}
