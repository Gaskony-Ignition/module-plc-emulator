plugins {
    `java-library`
    jacoco
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

    // Include web-ui component bundle
    modlImplementation(projects.webUi)

    // Gson is bundled with the module (module classloader wins - no runtime mismatch
    // with the platform's own bundled copy). Ships at the latest stable version rather
    // than being pinned down to the platform's 2.8.9: confirmed no Gson-constructed
    // object (JsonObject/JsonArray/JsonElement/Gson) is ever passed to or received from
    // an Ignition SDK, gateway-web, or Wicket API in this module - DeviceController
    // converts the one Gson JsonObject that reaches the web layer to an org.json
    // JSONObject via .toString() before it touches the servlet response, so no two
    // Gson copies from two classloaders ever meet. See 28-30/07/2026 dependency audit.
    modlImplementation(libs.gson)

    // SQLite JDBC - for reading gateway logs from system_logs.idb
    modlImplementation(libs.sqlite.jdbc)

    // Testing dependencies
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    // Explicit launcher pin: Gradle 8.10.2's own bundled junit-platform-launcher is older
    // than junit-platform-engine 1.14.x (pulled in by junit-jupiter 5.14.4) and fails
    // discovery with "OutputDirectoryCreator not available" when the two are unaligned.
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)

    // Make compile-only dependencies available for tests
    testImplementation(libs.ignition.common)
    testImplementation(libs.ignition.gateway.api)
    testImplementation(libs.jakarta.servlet)
    testImplementation(libs.opc.ua.gateway.api)
}

// Fidelity tests (@Tag("fidelity")) encode the TARGET driver-matching NodeId behaviour from
// docs/plans/ADDRESSING.md (Stage C, spec in progress) and are enabled per-fix as that work
// lands. They are excluded from the default `test` task and instead run via the `fidelityTest`
// task, or by passing -PincludeFidelity to `test` itself. See
// AddressSpaceBuilderFidelityTest's Javadoc for the full explanation.
val includeFidelity = project.hasProperty("includeFidelity")

tasks.test {
    useJUnitPlatform {
        if (!includeFidelity) {
            excludeTags("fidelity")
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("fidelityTest") {
    description = "Runs only the @Tag(\"fidelity\") suite (Stage C driver-matching NodeId assertions)."
    group = "verification"
    useJUnitPlatform {
        includeTags("fidelity")
    }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                minimum = "0.10".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
