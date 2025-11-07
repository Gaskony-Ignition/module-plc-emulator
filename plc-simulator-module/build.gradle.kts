plugins {
    base
    id("io.ia.sdk.modl") version "0.4.0"
}

version = "1.3.0"
group = "com.gaskony"

ignitionModule {
    fileName.set("EnhancedPLCSimulator-${project.version}")
    name.set("Enhanced PLC Simulator")
    // TEST: Use IA driver namespace to test classloader access hypothesis
    id.set("com.inductiveautomation.opcua.drivers.plcsimulator")
    moduleVersion.set(project.version.toString())
    license.set("license.html")
    moduleDescription.set("Multi-vendor PLC device simulator supporting Rockwell L5K, Siemens, Schneider, Beckhoff, and JSON formats with hierarchical OPC-UA tag structure.")
    requiredIgnitionVersion.set("8.3.0")
    freeModule.set(true)

    projectScopes.putAll(mapOf(
        ":gateway" to "G",
        ":designer" to "D",
        ":common" to "GD"
    ))

    hooks.putAll(mapOf(
        "com.inductiveautomation.plcsimulator.gateway.SimulatorModuleHook" to "G",
        "com.inductiveautomation.plcsimulator.designer.DesignerHook" to "D"
    ))

    // Declare dependency on OPC-UA module for device driver APIs (Ignition 8.3+ format)
    moduleDependencySpecs {
        register("com.inductiveautomation.opcua") {
            scope = "G"
            required = true
        }
    }

    // Enable module signing with self-signed certificate
    // Signing configured via gradle.properties
    skipModlSigning.set(false)
}
