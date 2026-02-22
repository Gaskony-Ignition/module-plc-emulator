# CLAUDE.md - Logix PLC Emulator Module

> **Purpose**: Optimized context file for AI-assisted development with Claude Code.
> **Last Updated**: 2026-02-21
> **Module Version**: 9.1.1

---

## Project Overview

**Name**: Logix PLC Emulator
**Type**: Ignition Gateway Module (Device Driver)
**Purpose**: Rockwell Logix PLC emulation with OPC-UA integration
**Core Functionality**: Parses L5K/L5X/JSON/CSV files -> Creates hierarchical OPC-UA tags -> Simulates dynamic values
**Repository**: `Gaskony-Ignition/ignition-module-plc-emulator`

**Key Value Proposition**: Allows testing HMI/SCADA applications without physical PLC hardware by simulating realistic tag structures from vendor export files.

---

## Current Development Phase

**Version**: 9.1.1 (Production Ready)
**Phase**: Production + Quality Assurance
**Status**: Modern React UI, SQLite logging, comprehensive test coverage, security hardened, CI/CD operational

**What's new in v9.0.0 (major version bump)**:
- Full React + TypeScript Connection Browser with sidebar-driven multi-view layout
- Dashboard, Devices, Tags, Logs, Diagnostics, and Simulation views
- SQLite-backed log storage with real-time filtering
- CPU/RAM status bar monitoring
- Catppuccin-inspired neutral charcoal theme
- 113 tests (100% passing)
- Dead code cleanup, dependency consolidation, version alignment

---

## Critical Technical Decisions

### Architecture
- **Pure Java** implementation (Python dependency removed in v2.0.0)
- **AbstractGatewayModuleHook** (migrated from AbstractDeviceModuleHook in v2.0.0)
- **Manual device registration** via extension points (no automatic scanning)
- **OPC-UA device driver** integration with Ignition's OPC-UA server
- **React + TypeScript** web UI bundled via webpack (UMD output)

### Technologies
- **Java 17** (minimum)
- **Ignition SDK 0.5.0**
- **Gradle 8.5** with Kotlin DSL
- **React 18** + TypeScript + Lucide icons
- **Jakarta Servlet API** (for web routes)
- **Milo OPC-UA** (via Ignition SDK)
- **Gson** for JSON parsing
- **SQLite** for log storage

### Critical Constraints
1. Must integrate with Ignition's OPC-UA server (cannot be standalone)
2. Device registration via extension points (no automatic discovery)
3. Gateway Config UI uses Wicket framework (not React/modern JS)
4. File storage must use Ignition data directory API (cross-platform)
5. Module signing required for Ignition 8.3+ (self-signed OK for dev)

---

## Project Structure

```
/modules/ignition-module-plc-emulator/logix-emulator-module/
├── build.gradle.kts                    # Build configuration (version: 9.1.1)
├── gradle.properties                   # Signing config (gitignored, uses env vars)
├── gradle.properties.template          # Template for signing config
├── CHANGELOG.md                        # Detailed version history
├── README.md                           # Project overview
├── QUICK_START.md                      # User quick start guide
├── KNOWN_ISSUES.md                     # Current limitations
│
├── docs/
│   ├── ARCHITECTURE.md                 # System architecture
│   ├── API_REFERENCE.md                # REST API documentation
│   ├── SECURITY.md                     # Security best practices
│   ├── SECURITY_TESTING.md             # Security testing guide
│   ├── TAG_CREATION_FLOW.md            # Tag pipeline reference
│   ├── internal/
│   │   └── CLAUDE.md                   # This file
│   └── archive/                        # Historical documentation
│
├── common/                             # Shared code (currently minimal)
│
├── designer/                           # Designer scope (minimal hook)
│   └── src/main/java/.../designer/
│       └── DesignerHook.java
│
├── web-ui/                             # React + TypeScript frontend
│   ├── package.json
│   ├── webpack.config.js
│   └── src/
│       ├── index.ts                    # Entry point (exports LogixConnectionBrowser)
│       ├── App.tsx                     # Main app with sidebar navigation
│       ├── App.scss
│       └── components/                 # View components
│           ├── Sidebar.tsx
│           ├── StatusBar.tsx
│           ├── DashboardView.tsx
│           ├── DevicesView.tsx
│           ├── TagsView.tsx
│           ├── LogsView.tsx
│           ├── DiagnosticsView.tsx
│           └── SimulationView.tsx
│
└── gateway/                            # Main module code (Gateway scope)
    └── src/main/
        ├── java/.../gateway/
        │   ├── SimulatorModuleHook.java          # Module lifecycle
        │   ├── FileWatcher.java                  # Hot reload monitoring
        │   ├── FileVersionManager.java           # Version history (5 versions)
        │   ├── OpcUaSimulationEngine.java        # Value simulation
        │   │
        │   ├── device/
        │   │   ├── LogixEmulatorDevice.java      # Device driver implementation
        │   │   ├── LogixEmulatorConfig.java       # Configuration records
        │   │   ├── LogixEmulatorExtensionPoint.java  # Device registration
        │   │   └── AddressSpaceBuilder.java       # OPC-UA address space creation
        │   │
        │   ├── parser/                            # Java parsers
        │   │   ├── PLCParser.java                 # Interface
        │   │   ├── ParserFactory.java             # Factory pattern
        │   │   ├── L5XParser.java                 # Rockwell L5K/L5X
        │   │   ├── JsonPLCParser.java             # Generic JSON
        │   │   └── CsvParser.java                 # CSV format
        │   │
        │   ├── validation/
        │   │   └── FileValidator.java             # File size/format validation
        │   │
        │   └── web/                               # Web routes and UI
        │       └── FileUploadRoutes.java          # REST API endpoints
        │
        └── resources/
            ├── LogixEmulator.properties           # i18n display names
            ├── mounted/                           # Public web resources
            │   ├── index.html                     # Landing page
            │   └── plc-file-upload.js             # Client-side enhancement
            └── pages/                             # Authenticated HTML pages
                ├── connection-browser.html         # React mount point
                └── edit-program.html               # PLC program editor
```

---

## Key Entry Points

### Module Lifecycle
**File**: `SimulatorModuleHook.java`
**Extends**: `AbstractGatewayModuleHook`
**Responsibilities**: Module startup/shutdown, route mounting, extension point registration, device registry management

### Device Implementation
**File**: `LogixEmulatorDevice.java`
**Extends**: `ManagedAddressSpaceWithLifecycle` implements `Device`
**Responsibilities**: File preparation/storage, parser coordination, OPC-UA address space lifecycle, hot reload, simulation engine

### Web UI
**Entry**: `web-ui/src/index.ts` -> exports `LogixConnectionBrowser`
**Webpack**: UMD output, entry name must match `.mount()` call in Java ModuleHook
**Key Pattern**: Resolve extensions `.ts`/`.tsx` MUST come before `.scss`

### REST API
**File**: `FileUploadRoutes.java`
**Routes**: All under `/data/logixemulator/`
**Auth**: Uses `OPEN_ROUTE` access control — Ignition handles `/data/` auth at higher level

---

## Build & Deploy

```bash
cd /modules/ignition-module-plc-emulator/logix-emulator-module
./gradlew clean build
# Output: build/LogixPLCEmulator-9.1.1.modl (~14MB)
```

**Important**: Always bump version in `build.gradle.kts` before building — Ignition requires a different version each time a module is installed.

---

## Common Troubleshooting

### Issue: Routes not mounting or unexpected auth behavior
**Fix**: Use `.handler().accessControl(OPEN_ROUTE).mount()` — do NOT add custom auth wrappers

### Issue: Device not in dropdown
**Fix**: Verify `registerExtensionPoint()` in `SimulatorModuleHook.startup()`

### Issue: Webpack export name mismatch
**Fix**: Export name in `index.ts` must match second arg to `.mount()` in Java hook

---

**End of Context File**
