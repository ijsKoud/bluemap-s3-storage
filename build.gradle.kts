plugins {
    java
}

group = "nl.klrnbk"

// We compile against the newest BlueMap artifacts published on repo.bluecolored.de (5.3).
// BlueMap 5.28 is source compatible for everything we use; methods added after 5.3
// (MapStorage.regionState) are implemented without @Override so the jar loads on both.
val bluemapVersion = "5.3"

repositories {
    mavenCentral()
    maven("https://libraries.minecraft.net") {
        content { includeGroup("com.mojang") }
    }
    maven("https://repo.bluecolored.de/releases") {
        content { includeGroupByRegex("de\\.bluecolored.*") }
    }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    compileOnly("de.bluecolored.bluemap:BlueMapCore:$bluemapVersion")
    compileOnly("de.bluecolored.bluemap:BlueMapCommon:$bluemapVersion")
    // BlueMapCore 5.3's POM references BlueMapAPI without a version, so pin it explicitly.
    compileOnly("de.bluecolored.bluemap:BlueMapAPI:2.7.2")

    testImplementation("de.bluecolored.bluemap:BlueMapCore:$bluemapVersion")
    testImplementation("de.bluecolored.bluemap:BlueMapCommon:$bluemapVersion")
    testImplementation("de.bluecolored.bluemap:BlueMapAPI:2.7.2")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "3g"
    testLogging { events("failed", "skipped"); showStandardStreams = true }
}

// No third-party code is bundled (JDK + BlueMap only), so a plain jar is the addon jar.
tasks.jar {
    archiveBaseName = "bluemap-s3-storage"
}
