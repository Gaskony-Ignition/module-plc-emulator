plugins {
    base
    id("io.ia.sdk.modl") version "0.1.1"
}

version = "1.0.0"
group = "com.gaskony"

ignitionModule {
    fileName.set("PLCSimulator-${project.version}")
    name.set("PLC Simulator")
    id.set("com.gaskony.plcsimulator")
    moduleVersion.set(project.version.toString())
    moduleDescription.set("Simulates PLC operations by parsing L5K files and creating hierarchical Ignition tags. Developed by Gaskony.")
    requiredIgnitionVersion.set("8.3.0")
    freeModule.set(true)

    projectScopes.putAll(mapOf(
        ":gateway" to "G",
        ":designer" to "D",
        ":common" to "GD"
    ))

    hooks.putAll(mapOf(
        "com.inductiveautomation.plcsimulator.gateway.GatewayHook" to "G",
        "com.inductiveautomation.plcsimulator.designer.DesignerHook" to "D"
    ))

    // Enable module signing with self-signed certificate
    // Signing configured via gradle.properties
    skipModlSigning.set(false)
}
