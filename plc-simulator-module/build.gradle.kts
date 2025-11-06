plugins {
    base
    id("io.ia.sdk.modl") version "0.1.1"
}

version = "1.0.1"
group = "com.gaskony"

ignitionModule {
    fileName.set("EnhancedPLCSimulator-${project.version}")
    name.set("Enhanced PLC Simulator")
    id.set("com.gaskony.plcsimulator")
    moduleVersion.set(project.version.toString())
    license.set("license.html")
    moduleDescription.set("Multi-vendor PLC device simulator supporting Rockwell L5K, Siemens, Schneider, Beckhoff, Gaskony, and JSON formats with hierarchical OPC-UA tag structure. Developed by Gaskony.")
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

    // Enable module signing with self-signed certificate
    // Signing configured via gradle.properties
    skipModlSigning.set(false)
}
