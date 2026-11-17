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

// Panel provides no API of its own: every MEDIVALRPG API (Kraje, Skarbiec, Armia, Ludnosc, Postep, Zycie) is compile-only and
// comes at runtime from its provider plugin. The jar task below is a safety net so no API class is ever shipped.
val medivalApiJar = files("../../medival-api/build/libs/medival-api.jar")
// nLogin (softdepend): only pl.medivalrpg.panel.auth.NLoginVerifier touches it, loaded when the plugin is present.
val nLoginJar = files("../../downloads/nlogin/nLogin.jar")

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    compileOnly(medivalApiJar)
    compileOnly(nLoginJar)

    // paper-api (Gson, Adventure) and the shared API (Rank) on the test classpath only; never shipped.
    testImplementation("io.papermc.paper:paper-api:26.3.build.218-stable")
    testImplementation(medivalApiJar)
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
    archiveFileName = "Panel-${project.version}.jar"
    // Safety net: never ship classes of the APIs we only consume.
    exclude("pl/medivalrpg/kraje/**", "pl/medivalrpg/skarbiec/**", "pl/medivalrpg/ludnosc/**", "pl/medivalrpg/armia/**", "pl/medivalrpg/postep/**", "pl/medivalrpg/zycie/**", "com/nickuc/**")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
