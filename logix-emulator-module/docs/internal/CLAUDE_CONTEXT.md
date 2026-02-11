# CLAUDE_CONTEXT.md - Logix PLC Emulator Module

> **Purpose**: Optimized context file for AI-assisted development with Claude Code.
> **Last Updated**: 2026-02-11
> **Module Version**: 8.2.10

---

## Project Overview

**Name**: Logix PLC Emulator
**Type**: Ignition Gateway Module (Device Driver)
**Purpose**: Rockwell Logix PLC emulation with OPC-UA integration
**Core Functionality**: Parses L5K/L5X/JSON/CSV files → Creates hierarchical OPC-UA tags → Simulates dynamic values

**Key Value Proposition**: Allows testing HMI/SCADA applications without physical PLC hardware by simulating realistic tag structures from vendor export files.

---

## Current Development Phase

**Version**: 8.2.10 (Production Ready)
**Phase**: Production + Quality Assurance
**Status**: Unified Connection Browser, security hardening complete, CI/CD operational
**Last Major Release**: v8.2.10 (2026-02-11)

**Recent Changes (v8.2.x)**:
- ✅ v8.2.10: Removed broken custom auth from all data routes (Ignition handles `/data/` auth)
- ✅ v8.2.9: Fixed missing route type/access control on page routes
- ✅ v8.2.8: Removed iframe sandbox/referrerPolicy blocking session cookies
- ✅ v8.2.4: Fixed webpack entry export name (`LogixConnectionBrowser`)
- ✅ v8.2.3: Unique component export names to prevent cross-module page collision
- ✅ v8.2.1: Security hardening, code cleanup, architecture improvements
- ✅ v8.1.0: Merged File Upload + Tag Browser into unified Connection Browser
- ✅ v8.0.0: Renamed module to Logix PLC Emulator, removed unused vendor parsers

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
/modules/ignition-module-plc-emulator/logix-emulator-module/
├── build.gradle.kts                    # Build configuration (version: 8.2.10)
├── gradle.properties                   # Signing config (uses environment variables)
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
        │   │   ├── LogixEmulatorDevice.java  # ⭐ Device driver implementation
        │   │   ├── LogixEmulatorConfig.java  # ⭐ Configuration records
        │   │   ├── LogixEmulatorExtensionPoint.java  # Device registration
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
            ├── LogixEmulator.properties      # i18n display names
            ├── mounted/                          # Public web resources
            │   ├── index.html                    # Landing page
            │   └── plc-file-upload.js            # Client-side enhancement
            └── pages/                            # Authenticated HTML pages
                ├── connection-browser.html        # Unified tag browser + file upload
                └── edit-program.html              # PLC program editor
```

---

## Key Entry Points

### Module Lifecycle
**File**: `SimulatorModuleHook.java`
**Extends**: `AbstractGatewayModuleHook`
**Responsibilities**:
- Module startup/shutdown
- Route mounting (`/main/data/logixemulator/*`)
- Extension point registration
- Device registry management

### Device Implementation
**File**: `LogixEmulatorDevice.java` (601 lines - marked for refactoring)
**Extends**: `ManagedAddressSpaceWithLifecycle`
**Implements**: `Device`
**Responsibilities**:
- File preparation and storage
- Parser coordination
- OPC-UA address space lifecycle
- Hot reload handling
- Simulation engine management

### Configuration
**File**: `LogixEmulatorConfig.java`
**Type**: Java records (nested)
**Structure**:
```java
LogixEmulatorConfig(
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
cd /modules/ignition-module-plc-emulator/logix-emulator-module
./gradlew clean build
# Output: build/LogixPLCEmulator-8.2.10.modl (~12MB)
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
3. Add enum to `LogixEmulatorConfig.ParserType`
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
- `POST /main/data/logixemulator/upload` - Upload file
- `GET /main/data/logixemulator/devices` - List devices
- `GET /main/data/logixemulator/device/{name}/status` - Get device status
- `GET /main/data/logixemulator/health` - Health check

---

## Known Issues & TODOs

### Security (RESOLVED) ✅
- ✅ Authentication via Ignition's `/data/` route infrastructure (custom auth removed in v8.2.10)
- ✅ Path traversal protection implemented
- ✅ XXE protection complete with 6 security features (L5XParser)
- ✅ Hardcoded credentials removed (gradle.properties uses environment variables)
- ✅ File size DoS prevention
- ✅ Comprehensive security tests (18 tests in FileUploadRoutesSecurityTest)
- ✅ Reflection removed — all public API methods on LogixEmulatorDevice (v8.2.1)

### Testing (Added in v5.4.9) ✅
- ✅ L5XParserTest: 12 tests including XXE prevention
- ✅ FileValidatorTest: 10 tests for file validation
- ✅ FileUploadRoutesSecurityTest: 18 security-focused tests
- ✅ Total: 40 tests, 100% passing
- ✅ CI/CD: GitHub Actions pipeline with automated testing

### Code Quality (Medium Priority)
- LogixEmulatorDevice.java too large (800+ lines, should be <500)
- L5KParser.java too large (843 lines, target 400)
- FileUploadRoutes.java too large (932 lines, target 300)
- Simulation engine incomplete (OpcUaSimulationEngine:105-117 stubbed out)
- Hot reload uses full rebuild (should be incremental)
- Static device registry (should use instance-based)

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

### Issue: "Routes not mounting" or "Authentication required" on data routes
**Cause**: Do NOT add custom `isAuthenticated()` wrappers — `getRemoteUser()`/`getUserPrincipal()` are not populated by Ignition's data route infrastructure
**Fix**: Use `.handler().accessControl(AccessControlStrategy.OPEN_ROUTE).mount()` — Ignition handles auth for `/data/` routes

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
- **Device logic**: LogixEmulatorDevice.java, LogixEmulatorConfig.java
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

**Production Readiness**:
- ✅ Core functionality complete
- ✅ Parser system operational
- ✅ Security hardened (v5.4.9+)
- ✅ Unified Connection Browser (v8.1.0)
- ✅ Documentation comprehensive

---

## Quick Reference Commands

```bash
# Build
./gradlew build

# Clean build
./gradlew clean build

# Install locally
cp build/LogixPLCEmulator-8.2.10.modl ~/ignition/user-lib/modules/

# Check what changed
git status
git diff

# Commit
git add .
git commit -m "feat: description"
git push origin master

# View logs in Ignition
tail -f /var/log/ignition/wrapper.log | grep logixemulator
```

---

**End of Context File**

This file should be the first thing loaded in any Claude Code session for efficient development.
