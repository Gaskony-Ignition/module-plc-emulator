# PLC Emulator - Skills

Knowledge base for the PLC Emulator module. Reference this to understand patterns and conventions.

## Architecture
- Gateway scope: L5K/L5X/JSON/CSV parsers, OPC-UA device driver, web routes, simulation engine
- Designer scope: Minimal hook (DesignerHook.java only)
- Common scope: Shared types
- Web UI: React/TypeScript with Webpack UMD bundle

## Key Patterns
- Controller pattern: DeviceController, TagController, SimulationController, SystemController
- FileUploadRoutes is a thin router delegating to controllers
- ParserFactory auto-detects file format (L5K/L5X/JSON/CSV)
- FileVersionManager keeps last 5 versions of uploaded files
- OpcUaSimulationEngine supports RAMP, SINE, RANDOM, TOGGLE, STATIC patterns
- Three rate limiters: file upload (60/hr), reads (300/hr), writes (60/hr)

## Build System
- Gradle Kotlin DSL with version catalog (libs.versions.toml)
- io.ia.sdk.modl plugin v0.5.0 (latest)
- syncVersion task syncs version to 15+ files (package.json, Java, docs, etc.)
- syncVersion runs as dependency of assembleModlStructure

## Testing
- 285 tests across 19 test classes, all passing
- Security tests include XXE prevention, path traversal, auth bypass
- Awaitility used for async test assertions (replaced Thread.sleep)

## Common Mistakes to Avoid
- Never commit gradle.properties (contains signing credentials)
- Always use parameterised SLF4J logging
- L5X parser must use XXE-protected DocumentBuilderFactory
- File paths must be sanitised via PathSecurity before use
- OPC-UA device registry is an interface, not a concrete class
