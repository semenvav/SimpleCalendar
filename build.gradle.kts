plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "dev.simplecalendar"

// The version people see comes from the image — `SC_VERSION`, the number CI gave that build —
// and is what /api/health reports. Gradle's own version names local archives and nothing else,
// so it stays at the series: baking the build number in here would invalidate the Docker layer
// that compiles the server on every single build.
version = file("VERSION").readText().trim() + ".0-dev"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.simplecalendar.ApplicationKt")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.serialization.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.logging)

    implementation(libs.coroutines.core)
    implementation(libs.logback.classic)
    implementation(libs.sqlite.jdbc)
    implementation(libs.hikaricp)
    implementation(libs.ical4j)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    // Fixtures and expectations contain Cyrillic; never let a Windows default codepage decide.
    systemProperty("file.encoding", "UTF-8")
    defaultCharacterEncoding = "UTF-8"
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
