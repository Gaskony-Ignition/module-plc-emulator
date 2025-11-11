# CLAUDE_CONTEXT.md - Enhanced PLC Simulator Module

> **Purpose**: Optimized context file for AI-assisted development with Claude Code.
> **Last Updated**: 2025-11-11
> **Module Version**: 2.0.5

---

## Project Overview

**Name**: Enhanced PLC Simulator
**Type**: Ignition Gateway Module (Device Driver)
**Purpose**: Multi-vendor PLC simulation with OPC-UA integration
**Core Functionality**: Parses L5K/JSON/CSV files → Creates hierarchical OPC-UA tags → Simulates dynamic values

**Key Value Proposition**: Allows testing HMI/SCADA applications without physical PLC hardware by simulating realistic tag structures from vendor export files.

---

## Current Development Phase

**Version**: 2.0.5 (Production Ready)
**Phase**: Maintenance + parser expansion
**Status**: Core features complete, adding vendor parsers (Siemens, Schneider, Beckhoff)
**Last Major Release**: v2.0.0 (2025-11-11) - Production release with Java parsers

**Recent Changes (Last Session - 2025-11-11)**:
- v2.0.5: Full URL display with copy button in device config
- v2.0.4: Clickable Program Manager URL field
- Comprehensive code and documentation review completed
- Security issues identified (authentication, path traversal) - marked for future fix

---

## Critical Technical Decisions

### Architecture
- **Pure Java** implementation (Python dependency removed in v2.0.0)
- **AbstractGatewayModuleHook** (migrated from AbstractDeviceModuleHook in v2.0.0)
- **Manual device registration** via extension points (no automatic scanning)
- **OPC-UA device driver** integration with Ignition's OPC-UA server

### Technologies
- **Java 17** (minimum)
- **Ignition SDK 0.4.0**
- **Gradle 7.x+** with Kotlin DSL
- **Jakarta Servlet API** (for web routes)
- **Milo OPC-UA** (via Ignition SDK)
- **Gson** for JSON parsing

### Critical Constraints
1. Must integrate with Ignition's OPC-UA server (cannot be standalone)
2. Device registration via extension points (no automatic discovery)
3. Gateway Config UI uses Wicket framework (not React/modern JS)
4. File storage must use Ignition data directory API (cross-platform)
5. Module signing required for Ignition 8.3+ (self-signed OK for dev)

---

## Project Structure

```
/modules/ignition-plc-simulator/plc-simulator-module/
├── build.gradle.kts                    # Build configuration (version: 2.0.5)
├── gradle.properties                   # Signing config (dev certificates)
├── CHANGELOG.md                        # Detailed version history ⭐
├── README.md                          # Project overview
├── QUICK_START.md                     # User quick start guide
├── KNOWN_ISSUES.md                    # Current limitations
├── CLAUDE_CONTEXT.md                  # This file
│
├── docs/
│   └── archive/                       # Historical documentation
│
├── common/                            # Shared code (currently minimal)
│
├── designer/                          # Designer scope (minimal hook)
│   └── src/main/java/.../designer/
│       └── DesignerHook.java
│
└── gateway/                           # Main module code (Gateway scope)
    └── src/main/
        ├── java/.../gateway/
        │   ├── SimulatorModuleHook.java          # ⭐ Module lifecycle
        │   ├── ParserService.java                # Python parser connector (fallback)
        │   ├── FileWatcher.java                  # Hot reload monitoring
        │   ├── FileVersionManager.java           # Version history (5 versions)
        │   ├── OpcUaSimulationEngine.java        # Value simulation (TODO)
        │   │
        │   ├── device/
        │   │   ├── EnhancedSimulatorDevice.java  # ⭐ Device driver implementation
        │   │   ├── EnhancedSimulatorConfig.java  # ⭐ Configuration records
        │   │   ├── EnhancedSimulatorExtensionPoint.java  # Device registration
        │   │   └── AddressSpaceBuilder.java      # OPC-UA address space creation
        │   │
        │   ├── parser/                           # ⭐ Java parsers
        │   │   ├── PLCParser.java                # Interface
        │   │   ├── ParserFactory.java            # Factory pattern
        │   │   ├── L5XParser.java                # Rockwell L5K/L5X
        │   │   ├── JsonPLCParser.java            # Generic JSON
        │   │   └── CsvParser.java                # CSV format
        │   │
        │   ├── validation/
        │   │   └── FileValidator.java            # File size/format validation
        │   │
        │   └── web/                              # Web routes and UI
        │       └── FileUploadRoutes.java         # REST API endpoints
        │
        └── resources/
            ├── EnhancedSimulator.properties      # i18n display names
            └── mounted/                          # Web resources
                ├── index.html                    # Landing page
                ├── simple-upload.html            # Upload interface
                ├── plc-file-upload.js            # Client-side enhancement
                └── (other HTML/JS files)
```

---

## Key Entry Points

### Module Lifecycle
**File**: `SimulatorModuleHook.java`
**Extends**: `AbstractGatewayModuleHook`
**Responsibilities**:
- Module startup/shutdown
- Route mounting (`/main/data/plcsimulator/*`)
- Extension point registration
- Device registry management

### Device Implementation
**File**: `EnhancedSimulatorDevice.java` (601 lines - marked for refactoring)
**Extends**: `ManagedAddressSpaceWithLifecycle`
**Implements**: `Device`
**Responsibilities**:
- File preparation and storage
- Parser coordination
- OPC-UA address space lifecycle
- Hot reload handling
- Simulation engine management

### Configuration
**File**: `EnhancedSimulatorConfig.java`
**Type**: Java records (nested)
**Structure**:
```java
EnhancedSimulatorConfig(
    General(deviceName, enabled),
    ParserSettings(programManagerUrl, fileName, fileContent, parserType, hotReload, reloadInterval),
    SimulationSettings(enabled, updateInterval, defaultPattern)
)
```

### Parser System
**File**: `ParserFactory.java` + parser implementations
**Pattern**: Factory + Strategy
**Parsers**: L5XParser (Rockwell), JsonPLCParser, CsvParser
**Returns**: `JsonObject` with standardized structure (global_tags, programs, udts)

---

## Common Development Tasks

### Building
```bash
cd /modules/ignition-plc-simulator/plc-simulator-module
./gradlew clean build
# Output: build/EnhancedPLCSimulator-2.0.5.modl (~12MB)
```

### Testing
```bash
# Copy to Ignition
cp build/*.modl /path/to/ignition/user-lib/modules/

# Restart Gateway (or use Module API)
systemctl restart ignition

# Check logs
tail -f /var/log/ignition/wrapper.log
```

### Adding a New Parser
1. Create class implementing `PLCParser` interface
2. Add to `ParserFactory.getAllParsers()` static block
3. Add enum to `EnhancedSimulatorConfig.ParserType`
4. Implement `parse()` and `parseContent()` methods
5. Return standardized JsonObject with: `global_tags`, `programs`, `udts`
6. Add tests and update documentation

---

## Critical Code Patterns

### Parser Output Format
```json
{
  "controller": "ControllerName",
  "vendor": "rockwell",
  "global_tags": [
    {
      "name": "TagName",
      "data_type": "DINT",
      "initial_value": 0,
      "udt_members": [/* for UDT instances */]
    }
  ],
  "programs": [
    {
      "name": "MainProgram",
      "tags": [/* program-scoped tags */]
    }
  ],
  "udts": [/* UDT definitions */]
}
```

### Address Space Structure
```
[DeviceName]/
├── Controller:Global/
│   ├── Motor1/              (UDT instance folder)
│   │   ├── Speed            (member variable)
│   │   └── Running          (member variable)
│   └── Tank1_Level          (atomic tag)
└── Programs/
    └── MainProgram/
        └── Counter
```

### REST API Endpoints
- `POST /main/data/plcsimulator/upload` - Upload file
- `GET /main/data/plcsimulator/devices` - List devices
- `GET /main/data/plcsimulator/device/{name}/status` - Get device status
- `GET /main/data/plcsimulator/health` - Health check

---

## Known Issues & TODOs

### Security (High Priority - Identified but not yet fixed)
- ⚠️ Authentication disabled on REST endpoints (FileUploadRoutes:48,59,70,81)
- ⚠️ Path traversal risk in file upload (EnhancedSimulatorDevice:229)
- ⚠️ XXE protection incomplete (L5XParser:52-55)

### Code Quality (Medium Priority)
- EnhancedSimulatorDevice.java too large (601 lines, should be <500)
- Simulation engine incomplete (OpcUaSimulationEngine:105-117 stubbed out)
- Hot reload uses full rebuild (should be incremental)

### Features (Future Enhancements)
- Siemens TIA Portal parser
- Schneider Electric parser
- Beckhoff TwinCAT parser
- Simulation value updates (infrastructure exists, logic TODO)

---

## Common Troubleshooting

### Issue: "Display names show as ?EnhancedSimulator...?"
**Cause**: i18n bundle not registered
**Fix**: Verify `BundleUtil.get().addBundle()` in `SimulatorModuleHook.startup()`

### Issue: "Routes not mounting"
**Cause**: Access control not specified or GatewayContext null
**Fix**: Check `.accessControl()` call and context initialization order

### Issue: "Device not in dropdown"
**Cause**: Extension point not registered
**Fix**: Verify `registerExtensionPoint()` in `SimulatorModuleHook.startup()`

### Issue: "Tags not appearing in OPC-UA browser"
**Cause**: Address space build failed or parser returned null
**Fix**: Check Gateway logs for parser errors; verify file format

### Issue: "Hot reload not working"
**Cause**: FileWatcher not started or config disabled
**Fix**: Check `hotReload` config setting; verify FileWatcher logs

---

## Token Optimization Strategy

### Always Load First:
1. CLAUDE_CONTEXT.md (this file)
2. CHANGELOG.md (recent changes section only)
3. README.md (overview only)

### Load for Specific Tasks:
- **Module lifecycle**: SimulatorModuleHook.java
- **Device logic**: EnhancedSimulatorDevice.java, EnhancedSimulatorConfig.java
- **Parsing**: ParserFactory.java + specific parser file
- **Web/API**: FileUploadRoutes.java
- **OPC-UA**: AddressSpaceBuilder.java

### Rarely Load (only when specifically needed):
- Build files (build.gradle.kts, gradle.properties)
- Web resources (HTML, JS)
- Historical documentation
- Test files

---

## Success Metrics

**Module Quality Score**: 7.5/10 (per 2025-11-11 code review)

**Production Readiness**:
- ✅ Core functionality complete
- ✅ Parser system operational
- ⚠️ Security hardening needed
- ⚠️ Simulation feature incomplete
- ✅ Documentation comprehensive

**Next Milestone**: v2.1.0 with security fixes and additional parsers

---

## Quick Reference Commands

```bash
# Build
./gradlew build

# Clean build
./gradlew clean build

# Install locally
cp build/EnhancedPLCSimulator-2.0.5.modl ~/ignition/user-lib/modules/

# Check what changed
git status
git diff

# Commit
git add .
git commit -m "feat: description"
git push origin master

# View logs in Ignition
tail -f /var/log/ignition/wrapper.log | grep plcsimulator
```

---

**End of Context File**

This file should be the first thing loaded in any Claude Code session for efficient development.
