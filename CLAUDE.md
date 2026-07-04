# CLAUDE.md - Logix PLC Emulator Module

This file contains module-specific instructions. Shared standards are in `/modules/CLAUDE.md` and `/modules/.claude/skills/`.

## Project Overview

**Name**: Logix PLC Emulator
**Module ID**: `com.inductiveautomation.opcua.drivers.logixemulator`
**Version**: See `build.gradle.kts` (single source of truth)
**Status**: Production Ready
**Language**: Java 17
**Framework**: Ignition SDK 8.3.0, OPC-UA Device Driver APIs
**Purpose**: Emulates Rockwell Logix PLC tag structures from L5K/L5X exports with full UDT/AOI expansion, hierarchical OPC-UA tags, simulation engine, and hot-reload

## Project Location

```
/modules/ignition-module-plc-emulator/
```

## Essential Reading Order

1. **`docs/PROJECT_CHARTER.md`** - Purpose, definition of done, won't-do list — drives all release decisions
2. **This file** - Overall context and architecture
3. **`/modules/.claude/skills/`** - Shared skills across all modules
4. **README.md** - User-facing documentation

(The former module-local skills `plc-parsing` and `plc-simulation` were lost in the
June 2026 data loss and have not been recreated — there is no `.claude/` directory
in this module.)

## Build Commands

```bash
cd /modules/ignition-module-plc-emulator
./gradlew clean build          # Build module
./gradlew test                 # Run tests only
./gradlew syncVersion          # Sync version to all files
./gradlew dependencyCheckAnalyze  # OWASP dependency scan
```

**Output**: `build/LogixPLCEmulator-{version}.modl`

## Architecture

### Module Structure

```
ignition-module-plc-emulator/
├── build.gradle.kts                 # Root build (io.ia.sdk.modl v0.5.0)
├── settings.gradle.kts              # Multi-module settings
├── common/                          # Shared types (GD scope)
├── designer/                        # Designer hook (D scope)
├── gateway/                         # Main implementation (G scope)
│   └── src/main/java/com/inductiveautomation/logixemulator/gateway/
│       ├── SimulatorModuleHook.java           # Module entry point
│       ├── device/                             # OPC-UA device driver
│       │   ├── SimulatorDevice.java           # Device lifecycle
│       │   ├── SimulatorDeviceConfig.java     # Config record
│       │   └── SimulatorDeviceExtensionPoint.java
│       ├── parser/                             # File format parsers
│       │   ├── ParserFactory.java             # Auto-detect format
│       │   ├── L5KParser.java                 # Rockwell L5K
│       │   ├── L5XParser.java                 # Rockwell L5X (XML)
│       │   ├── JsonParser.java                # JSON tags
│       │   └── CsvParser.java                 # CSV variables
│       ├── simulation/                         # Simulation engine
│       │   └── OpcUaSimulationEngine.java     # RAMP/SINE/RANDOM/TOGGLE
│       ├── web/                                # Gateway WebUI + REST
│       │   ├── controller/                    # REST controllers
│       │   └── routes/                        # HTTP route registration
│       └── versioning/                         # File version manager
└── web-ui/                          # React/TypeScript Gateway UI
    ├── package.json
    ├── webpack.config.js
    └── src/
```

### Key Components

- **SimulatorModuleHook** - Module lifecycle, device registration, web route mounting
- **ParserFactory** - Auto-detects L5K/L5X/JSON/CSV format
- **L5KParser/L5XParser** - Full UDT expansion, AOI support, 22 predefined types
- **OpcUaSimulationEngine** - RAMP, SINE, RANDOM, TOGGLE, STATIC patterns
- **FileVersionManager** - Keeps last 5 versions of uploaded files

### Design Patterns

- **Controller pattern**: DeviceController, TagController, SimulationController, SystemController
- **FileUploadRoutes** is a thin router delegating to controllers
- **Three rate limiters**: file upload (60/hr), reads (300/hr), writes (60/hr)
- **Hot-reload**: Incremental tag updates without device restart

## Module Dependencies

- Depends on `com.inductiveautomation.opcua` module (OPC-UA device driver APIs)
- Web UI uses React UMD bundle served via Gateway SystemJS

## Build Quality Tools

- **Checkstyle** 10.26.1 with `config/checkstyle/checkstyle.xml`
- **SpotBugs** 6.4.8 (effort=MAX, confidence=MEDIUM, ignoreFailures=true)
- **JaCoCo** 0.8.11 (XML + HTML reports)
- **OWASP Dependency Check** 12.2.0 (failBuildOnCVSS=7.0)

## Version Management

- Version defined in `build.gradle.kts` (`version = "X.Y.Z"`)
- `syncVersion` task propagates to 15+ files (package.json, Java, docs)
- `syncVersion` runs automatically before `assembleModlStructure`

## Common Mistakes to Avoid

- Always use parameterised SLF4J logging `{}` (no string concatenation)
- Properties files must be in exact package structure for i18n
- Resource bundles must be registered with BundleUtil or names show as "?...?"
- Never commit gradle.properties (contains signing credentials)
- L5K parser is line-based; L5X parser uses XXE-protected XML

## Security

- All XML parsing uses XXE-protected DocumentBuilderFactory
- File upload validates content type and size
- Path traversal protection on all file operations
- Rate limiting on upload and read endpoints

---

**Last Updated**: 2026-03-05
