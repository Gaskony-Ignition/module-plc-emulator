plugins {
    base
    id("io.ia.sdk.modl") version "0.5.0"
}

version = "9.1.0"
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

// ── Version sync ──────────────────────────────────────────────────────────────
tasks.register("syncVersion") {
    group = "versioning"
    description = "Syncs project.version to all files that embed it"
    doLast {
        val ver = project.version.toString()
        fun sync(f: File, pattern: Regex, replacement: String) {
            val text = f.readText()
            val updated = text.replace(pattern, replacement)
            if (updated != text) { f.writeText(updated); logger.lifecycle("  synced ${f.name} → $ver") }
        }
        sync(file("web-ui/package.json"),
            Regex(""""version":\s*"[^"]+""""), """"version": "$ver"""")
        sync(file("gradle.properties.template"),
            Regex("""(?m)^version=.+$"""), "version=$ver")
        sync(file("gateway/src/main/java/com/inductiveautomation/logixemulator/gateway/web/controller/SystemController.java"),
            Regex("""\.put\("moduleVersion",\s*"[^"]+"\)"""), """.put("moduleVersion", "$ver")""")
        sync(file("web-ui/src/App.tsx"),
            Regex("""const MODULE_VERSION = "[^"]+""""), """const MODULE_VERSION = "$ver"""")
        sync(file("license.html"),
            Regex("""(?<=<strong>Version:</strong> )[0-9.]+"""), ver)
        sync(file("README.md"),
            Regex("""(?m)^\*\*Version\*\*:\s*v[\d.]+"""), "**Version**: v${ver}")
        logger.lifecycle("syncVersion: all files set to $ver")
    }
}

tasks.named("assembleModlStructure") {
    dependsOn("syncVersion")
}
