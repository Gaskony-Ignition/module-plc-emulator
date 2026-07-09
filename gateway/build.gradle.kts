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
    compileOnly(libs.wicket.core)

    // Include web-ui component bundle
    modlImplementation(projects.webUi)

    // Gson is provided by Ignition at runtime - do NOT bundle
    compileOnly(libs.gson)

    // SQLite JDBC - for reading gateway logs from system_logs.idb
    modlImplementation(libs.sqlite.jdbc)

    // Testing dependencies
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
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
