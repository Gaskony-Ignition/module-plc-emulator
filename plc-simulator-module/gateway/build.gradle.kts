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

    // Only bundle dependencies NOT provided by Ignition/modules
    modlImplementation(libs.gson)
}
