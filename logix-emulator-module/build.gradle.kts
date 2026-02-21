plugins {
    base
    id("io.ia.sdk.modl") version "0.5.0"
}

version = "9.0.2"
group = "com.gaskony"

ignitionModule {
    fileName.set("LogixPLCEmulator-${project.version}")
    name.set("Logix PLC Emulator")
    id.set("com.inductiveautomation.opcua.drivers.logixemulator")
    moduleVersion.set(project.version.toString())
    license.set("license.html")
    moduleDescription.set("Rockwell Logix PLC emulator for Ignition. Emulates CompactLogix/ControlLogix tag structures from L5K/L5X exports with full UDT/AOI expansion, hierarchical OPC-UA tags, hot-reload with incremental updates, simulation engine, and file versioning.")
    requiredIgnitionVersion.set("8.3.0")
    freeModule.set(true)

    projectScopes.putAll(mapOf(
        ":gateway" to "G",
        ":designer" to "D",
        ":common" to "GD"
    ))

    hooks.putAll(mapOf(
        "com.inductiveautomation.logixemulator.gateway.SimulatorModuleHook" to "G",
        "com.inductiveautomation.logixemulator.designer.DesignerHook" to "D"
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
