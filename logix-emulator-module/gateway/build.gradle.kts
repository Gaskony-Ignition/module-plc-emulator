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
    compileOnly("com.inductiveautomation.opcua:opc-ua-gateway-api:10.3.0")

    compileOnly("javax.servlet:javax.servlet-api:3.1.0")

    // Gateway web UI dependencies for config pages
    compileOnly("com.inductiveautomation.ignition:gateway-web:8.3.0")
    compileOnly("org.apache.wicket:wicket-core:9.8.0")

    // Include web-ui component bundle
    modlImplementation(projects.webUi)

    // Only bundle dependencies NOT provided by Ignition/modules
    modlImplementation(libs.gson)

    // SQLite JDBC - for reading gateway logs from system_logs.idb
    modlImplementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // Testing dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.7.0")
    testImplementation("org.assertj:assertj-core:3.24.2")

    // Make compile-only dependencies available for tests
    testImplementation(libs.ignition.common)
    testImplementation(libs.ignition.gateway.api)
    testImplementation("javax.servlet:javax.servlet-api:3.1.0")
    testImplementation(libs.gson)
    testImplementation("com.inductiveautomation.opcua:opc-ua-gateway-api:10.3.0")
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
