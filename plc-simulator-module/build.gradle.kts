plugins {
    base
    id("io.ia.sdk.modl") version "0.4.0"
}

version = "5.4.0"
group = "com.gaskony"

ignitionModule {
    fileName.set("EnhancedPLCSimulator-${project.version}")
    name.set("Enhanced PLC Simulator")
    // TEST: Use IA driver namespace to test classloader access hypothesis
    id.set("com.inductiveautomation.opcua.drivers.plcsimulator")
    moduleVersion.set(project.version.toString())
    license.set("license.html")
    moduleDescription.set("PLC device simulator with easy file upload. Currently supports Rockwell L5K files with full UDT/AOI expansion and hierarchical OPC-UA tags. Planned multi-vendor support: Siemens TIA Portal, Schneider Electric, Beckhoff TwinCAT, JSON formats.")
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
