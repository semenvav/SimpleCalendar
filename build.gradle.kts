plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "dev.simplecalendar"
version = "0.1.0"

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
