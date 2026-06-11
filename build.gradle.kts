plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    application
    id("com.gradleup.shadow") version "9.0.0-beta10"
}

group = "jadx.server"
version = "0.1.3"

repositories {
    mavenCentral()
    google() // for jadx android deps
}

dependencies {
    // ── MCP Kotlin SDK ──
    implementation("io.modelcontextprotocol:kotlin-sdk:0.12.0")

    // ── Ktor (for Streamable HTTP transport) ──
    implementation("io.ktor:ktor-server-core:3.1.3")
    implementation("io.ktor:ktor-server-netty:3.1.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
    implementation("io.ktor:ktor-server-cors:3.1.3")

    // ── kotlinx ──
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // ── jadx-core (local) ──
    implementation(files("ref/jadx/jadx-core/build/libs/jadx-core-dev.jar"))
    implementation(files("ref/jadx/jadx-plugins/jadx-input-api/build/libs/jadx-input-api-dev.jar"))
    implementation(files("ref/jadx/jadx-commons/jadx-zip/build/libs/jadx-zip-dev.jar"))
    // jadx runtime plugins
    runtimeOnly(files("ref/jadx/jadx-plugins/jadx-dex-input/build/libs/jadx-dex-input-dev.jar"))
    runtimeOnly(files("ref/jadx/jadx-plugins/jadx-java-input/build/libs/jadx-java-input-dev.jar"))
    runtimeOnly(files("ref/jadx/jadx-plugins/jadx-smali-input/build/libs/jadx-smali-input-dev.jar"))
    runtimeOnly(files("ref/jadx/jadx-plugins/jadx-xapk-input/build/libs/jadx-xapk-input-dev.jar"))
    runtimeOnly(files("ref/jadx/jadx-plugins/jadx-aab-input/build/libs/jadx-aab-input-dev.jar"))
    runtimeOnly(files("ref/jadx/jadx-plugins/jadx-apks-input/build/libs/jadx-apks-input-dev.jar"))
    runtimeOnly(files("ref/jadx/jadx-plugins/jadx-apkm-input/build/libs/jadx-apkm-input-dev.jar"))
    runtimeOnly(files("ref/jadx/jadx-plugins-tools/build/libs/jadx-plugins-tools-dev.jar"))
    runtimeOnly(files("ref/jadx/jadx-commons/jadx-app-commons/build/libs/jadx-app-commons-dev.jar"))

    // ── jadx transitive deps ──
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("ch.qos.logback:logback-classic:1.5.32")

    // ── CLI ──
    implementation("com.github.ajalt.clikt:clikt:5.0.3")

    // ── Test ──
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

application {
    mainClass.set("jadx.server.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Xms256M",
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=200",
        "-XX:G1PeriodicGCInterval=30000",
        "-XX:InitiatingHeapOccupancyPercent=45",
    )
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Xms256M", "-Xmx4G", "-XX:+UseG1GC")
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
}
