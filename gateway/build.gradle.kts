plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(projects.common)
    compileOnly(libs.ignition.common)
    compileOnly(libs.ignition.gateway.api)

    // Import driver-api platform (BOM that manages versions)
    compileOnly(platform(libs.ignition.driver.api))

    // OPC-UA device driver API - NOT bundled, provided by OPC-UA module at runtime
    // Using compileOnly instead of modlImplementation to avoid classloader conflicts
    compileOnly(libs.opc.ua.gateway.api)

    compileOnly(libs.jakarta.servlet)

    // Gateway web UI dependencies for config pages
    compileOnly(libs.gateway.web)
    compileOnly(libs.wicket.core)

    // Include web-ui component bundle
    modlImplementation(projects.webUi)

    // Only bundle dependencies NOT provided by Ignition/modules
    modlImplementation(libs.gson)

    // SQLite JDBC - for reading gateway logs from system_logs.idb
    modlImplementation(libs.sqlite.jdbc)

    // Testing dependencies
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)

    // Make compile-only dependencies available for tests
    testImplementation(libs.ignition.common)
    testImplementation(libs.ignition.gateway.api)
    testImplementation(libs.jakarta.servlet)
    testImplementation(libs.gson)
    testImplementation(libs.opc.ua.gateway.api)
}

tasks.test {
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }

    // Fail build if no tests found
    failFast = false

    reports {
        html.required.set(true)
        junitXml.required.set(true)
    }
}
