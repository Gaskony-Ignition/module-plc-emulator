plugins {
    base
    id("io.ia.sdk.modl") version "0.5.0"
}

version = "9.1.1"
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

        // ── Markdown docs ────────────────────────────────────────────────────
        sync(file("QUICK_START.md"),
            Regex("""LogixPLCEmulator-[\d.]+\.modl"""), "LogixPLCEmulator-${ver}.modl")
        sync(file("QUICK_START.md"),
            Regex("""(?m)^\*\*Module Version:\*\*\s+[\d.]+"""), "**Module Version:** ${ver}")

        sync(file("TESTING.md"),
            Regex("""Testing Guide v[\d.]+"""), "Testing Guide v${ver}")
        sync(file("TESTING.md"),
            Regex("""(?m)^\*\*Version\*\*:\s+[\d.]+"""), "**Version**: ${ver}")
        sync(file("TESTING.md"),
            Regex("""(?m)^\*\*Module Version\*\*:\s+[\d.]+"""), "**Module Version**: ${ver}")
        sync(file("TESTING.md"),
            Regex("""(?m)^- \*\*Version\*\*:\s+[\d.]+"""), "- **Version**: ${ver}")

        sync(file("DEVELOPMENT.md"),
            Regex("""version = "[\d.]+""""), """version = "${ver}"""")

        sync(file("BUILD.md"),
            Regex("""version = "[\d.]+""""), """version = "${ver}"""")

        sync(file("KNOWN_ISSUES.md"),
            Regex("""(?m)^## Current Version:\s+v[\d.]+"""), "## Current Version: v${ver}")
        sync(file("KNOWN_ISSUES.md"),
            Regex("""should show v[\d.]+"""), "should show v${ver}")
        sync(file("KNOWN_ISSUES.md"),
            Regex("""e\.g\., v[\d.]+"""), "e.g., v${ver}")

        sync(file("PLAN.md"),
            Regex("""(?m)^## Current State \(v[\d.]+\)"""), "## Current State (v${ver})")
        sync(file("PLAN.md"),
            Regex("""\(v[\d.]+ release\)"""), "(v${ver} release)")

        sync(file("docs/ARCHITECTURE.md"),
            Regex("""Logix PLC Emulator \(v[\d.]+\)"""), "Logix PLC Emulator (v${ver})")
        sync(file("docs/ARCHITECTURE.md"),
            Regex("""(?m)^\*\*Module Version\*\*:\s+[\d.]+"""), "**Module Version**: ${ver}")

        sync(file("docs/API_REFERENCE.md"),
            Regex("""(?m)^\*\*Version\*\*:\s+[\d.]+"""), "**Version**: ${ver}")
        sync(file("docs/API_REFERENCE.md"),
            Regex(""""moduleVersion":\s*"[\d.]+""""), """"moduleVersion": "${ver}"""")

        sync(file("docs/SECURITY_TESTING.md"),
            Regex("""(?m)^\*\*Version\*\*:\s+[\d.]+"""), "**Version**: ${ver}")
        sync(file("docs/SECURITY_TESTING.md"),
            Regex("""(?m)^\*\*Module Version\*\*:\s+[\d.]+"""), "**Module Version**: ${ver}")

        sync(file("docs/TAG_CREATION_FLOW.md"),
            Regex("""(?m)^>\s*\*\*Version\*\*:\s+[\d.]+"""), "> **Version**: ${ver}")

        sync(file("docs/internal/CLAUDE_CONTEXT.md"),
            Regex("""(?m)^>\s*\*\*Module Version\*\*:\s+[\d.]+"""), "> **Module Version**: ${ver}")
        sync(file("docs/internal/CLAUDE_CONTEXT.md"),
            Regex("""(?m)^\*\*Version\*\*:\s+[\d.]+"""), "**Version**: ${ver}")
        sync(file("docs/internal/CLAUDE_CONTEXT.md"),
            Regex("""\(version: [\d.]+\)"""), "(version: ${ver})")
        sync(file("docs/internal/CLAUDE_CONTEXT.md"),
            Regex("""LogixPLCEmulator-[\d.]+\.modl"""), "LogixPLCEmulator-${ver}.modl")

        logger.lifecycle("syncVersion: all files set to $ver")
    }
}

tasks.named("assembleModlStructure") {
    dependsOn("syncVersion")
}
