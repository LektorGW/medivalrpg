// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
plugins { java }

group = "pl.medivalrpg"
version = "1.0.0"

// Each release is built into its own directory (Windows keeps test reports locked): ./gradlew build -PbuildProfile=firmy-stage1
providers.gradleProperty("buildProfile").orNull?.let { layout.buildDirectory.set(layout.projectDirectory.dir("build/$it")) }

repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }

// Stage of the release: 1 = MVP, 2 = + contracts, wholesale orders and loans, 3 = + manufactories (default: everything).
// A lower stage leaves the later packages out of the jar, so each stage has its own jar and rollback.
val stage = (providers.gradleProperty("stage").orNull ?: "3").toInt()
sourceSets {
    main {
        java {
            if (stage < 2) exclude("pl/medivalrpg/firmy/stage2/**")
            if (stage < 3) exclude("pl/medivalrpg/firmy/stage3/**")
        }
    }
    test {
        java {
            if (stage < 2) exclude("**/Stage2*Test.java")
            if (stage < 3) exclude("**/Stage3*Test.java")
        }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    // Kraje, Skarbiec, Ludnosc and Postep APIs (frozen medival-api); never packaged.
    compileOnly(files("../medival-api/build/libs/medival-api.jar"))
    testImplementation("io.papermc.paper:paper-api:26.3.build.218-stable")
    testImplementation(files("../medival-api/build/libs/medival-api.jar"))
    testImplementation("com.google.code.gson:gson:2.13.2")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar { archiveFileName = "Firmy-${project.version}.jar" }

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); showStandardStreams = false }
}
