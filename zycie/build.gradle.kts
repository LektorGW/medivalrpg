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

// Zycie PROVIDES pl.medivalrpg.zycie.api: its sources are compiled into (and shipped only by) this jar.
// The other APIs (Kraje, Ludnosc, Skarbiec, Armia, Postep) are compile-only: their classes come from the shared API jar
// with the zycie packages cut out, so zycie-api exists exactly once on the compile classpath.
// Armia and Postep are never linked directly at runtime (looked up reflectively, see service/Integrations).
val medivalApiJar = file("../medival-api/build/libs/medival-api.jar")
val foreignApiClasses = tasks.register<Sync>("foreignApiClasses") {
    from(zipTree(medivalApiJar)) {
        exclude("pl/medivalrpg/zycie/**", "META-INF/**")
    }
    into(layout.buildDirectory.dir("foreign-api"))
}

sourceSets {
    main {
        java.srcDir("../medival-api/zycie-api/src/main/java")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    compileOnly(files(foreignApiClasses))

    // Logic under test is plain Java; paper-api only for YAML parsing in tests (never shipped).
    testImplementation("io.papermc.paper:paper-api:26.3.build.218-stable")
    testImplementation(files(foreignApiClasses))
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
    exclude(
        "pl/medivalrpg/kraje/**", "pl/medivalrpg/skarbiec/**", "pl/medivalrpg/ludnosc/**",
        "pl/medivalrpg/armia/**", "pl/medivalrpg/postep/**"
    )
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
