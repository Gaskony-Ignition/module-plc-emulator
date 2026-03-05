plugins {
    base
    id("io.ia.sdk.modl") version "0.5.0"
    id("com.github.spotbugs") version "6.0.27" apply false
    id("org.owasp.dependencycheck") version "12.1.0" apply false
}

// ── OWASP Dependency Check ──────────────────────────────────────────────────
apply(plugin = "org.owasp.dependencycheck")
configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "JSON")
    analyzers.assemblyEnabled = false
}

version = "9.2.0"
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

    // Module signing configuration
    // Auto-skips signing when the keystore file does not exist (e.g. in CI without secrets).
    // To sign locally, set ignition.signing.keystoreFile in gradle.properties.
    val keystoreFilePath = (findProperty("ignition.signing.keystoreFile") as? String) ?: ""
    skipModlSigning.set(keystoreFilePath.isBlank() || !file(keystoreFilePath).exists())
}

// ── Static analysis ──────────────────────────────────────────────────────────
subprojects {
    afterEvaluate {
        if (plugins.hasPlugin("java") || plugins.hasPlugin("java-library")) {
            apply(plugin = "checkstyle")
            apply(plugin = "com.github.spotbugs")
            apply(plugin = "jacoco")

            configure<CheckstyleExtension> {
                configFile = rootProject.file("config/checkstyle/checkstyle.xml")
                toolVersion = "10.12.5"
                isIgnoreFailures = true
            }

            configure<com.github.spotbugs.snom.SpotBugsExtension> {
                ignoreFailures.set(true)
                effort.set(com.github.spotbugs.snom.Effort.MAX)
                reportLevel.set(com.github.spotbugs.snom.Confidence.MEDIUM)
            }

            configure<JacocoPluginExtension> {
                toolVersion = "0.8.11"
            }

            tasks.withType<JacocoReport> {
                reports {
                    xml.required.set(true)
                    html.required.set(true)
                }
            }
        }
    }
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
        sync(file("docs/QUICK_START.md"),
            Regex("""LogixPLCEmulator-[\d.]+\.modl"""), "LogixPLCEmulator-${ver}.modl")
        sync(file("docs/QUICK_START.md"),
            Regex("""(?m)^\*\*Module Version:\*\*\s+[\d.]+"""), "**Module Version:** ${ver}")

        sync(file("docs/TESTING.md"),
            Regex("""Testing Guide v[\d.]+"""), "Testing Guide v${ver}")
        sync(file("docs/TESTING.md"),
            Regex("""(?m)^\*\*Version\*\*:\s+[\d.]+"""), "**Version**: ${ver}")
        sync(file("docs/TESTING.md"),
            Regex("""(?m)^\*\*Module Version\*\*:\s+[\d.]+"""), "**Module Version**: ${ver}")
        sync(file("docs/TESTING.md"),
            Regex("""(?m)^- \*\*Version\*\*:\s+[\d.]+"""), "- **Version**: ${ver}")

        sync(file("docs/DEVELOPMENT.md"),
            Regex("""version = "[\d.]+""""), """version = "${ver}"""")

        sync(file("docs/BUILD.md"),
            Regex("""version = "[\d.]+""""), """version = "${ver}"""")

        sync(file("docs/KNOWN_ISSUES.md"),
            Regex("""(?m)^## Current Version:\s+v[\d.]+"""), "## Current Version: v${ver}")
        sync(file("docs/KNOWN_ISSUES.md"),
            Regex("""should show v[\d.]+"""), "should show v${ver}")
        sync(file("docs/KNOWN_ISSUES.md"),
            Regex("""e\.g\., v[\d.]+"""), "e.g., v${ver}")

        sync(file("docs/PLAN.md"),
            Regex("""(?m)^## Current State \(v[\d.]+\)"""), "## Current State (v${ver})")
        sync(file("docs/PLAN.md"),
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


        logger.lifecycle("syncVersion: all files set to $ver")
    }
}

tasks.named("assembleModlStructure") {
    dependsOn("syncVersion")
}
