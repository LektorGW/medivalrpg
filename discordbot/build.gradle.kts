// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
plugins { java }
group = "pl.medivalrpg"
version = "1.0.0"
repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    compileOnly("net.dv8tion:JDA:5.6.1") { exclude(module = "opus-java") }
    testImplementation("net.dv8tion:JDA:5.6.1") { exclude(module = "opus-java") }
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release = 25 }
tasks.processResources { filesMatching("plugin.yml") { expand("version" to project.version) } }
tasks.test { useJUnitPlatform() }
tasks.jar { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
