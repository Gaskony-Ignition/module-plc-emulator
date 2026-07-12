# Logix PLC Emulator - Architecture Documentation

## Table of Contents
1. [System Overview](#system-overview)
2. [Module Architecture](#module-architecture)
3. [Component Breakdown](#component-breakdown)
4. [Data Flow](#data-flow)
5. [Security Architecture](#security-architecture)
6. [Testing Strategy](#testing-strategy)
7. [Deployment](#deployment)
8. [Future Enhancements](#future-enhancements)

## System Overview

The Logix PLC Emulator is an Ignition Gateway module that provides virtual PLC device simulation through OPC-UA. It allows users to upload PLC program files (L5K, L5X, JSON, CSV) which are parsed and exposed as OPC-UA tags in a hierarchical structure.

### Key Features
- **Rockwell Logix PLC file support** (L5K, L5X, JSON, CSV)
- **OPC-UA device driver** appearing in Gateway Config → Devices dropdown
- **Hierarchical tag structure** with UDT expansion
- **Web-based file upload** with embedded React UI
- **Real-time simulation engine** (ramp, sine, noise, toggle patterns)
- **Hot-reload capability** for file changes
- **Comprehensive security** (XXE prevention, path traversal protection, authentication)

### Technology Stack
- **Framework**: Ignition SDK 8.3.0
- **Language**: Java 17
- **Build System**: Gradle 8.5 with Kotlin DSL
- **Web UI**: React + TypeScript
- **OPC-UA**: Ignition OPC-UA module integration
- **Testing**: JUnit 5, Mockito, AssertJ
- **CI/CD**: GitHub Actions

## Module Architecture

### Module Scopes

```
Logix PLC Emulator (v10.0.0)
├── Gateway (G)     - Core device driver and OPC-UA server
├── Designer (D)    - Design-time integration (minimal)
├── Common (GD)     - Shared code between Gateway and Designer
└── Web UI (G)      - React-based file upload interface
```

### Module Structure

```
logix-emulator-module/
├── gateway/
│   ├── src/main/java/
│   │   └── com/inductiveautomation/logixemulator/gateway/
│   │       ├── device/              # Device driver implementation
│   │       │   ├── LogixEmulatorExtensionPoint.java
│   │       │   ├── LogixEmulatorDevice.java
│   │       │   ├── LogixEmulatorConfig.java
│   │       │   └── AddressSpaceBuilder.java
│   │       ├── parser/              # PLC file parsers
│   │       │   ├── PLCParser.java (interface)
│   │       │   ├── L5KParser.java   # Rockwell L5K (text)
│   │       │   ├── L5XParser.java   # Rockwell L5X (XML)
│   │       │   ├── JsonPLCParser.java
│   │       │   ├── CsvParser.java
│   │       │   ├── ParserFactory.java
│   │       │   └── DataTypeUtils.java
│   │       ├── validation/          # Security and validation
│   │       │   └── FileValidator.java
│   │       ├── web/                 # Web routes for file upload
│   │       │   └── FileUploadRoutes.java
│   │       ├── SimulatorModuleHook.java
│   │       ├── OpcUaSimulationEngine.java
│   │       ├── FileWatcher.java
│   │       └── FileVersionManager.java
│   ├── src/main/resources/mounted/  # Mounted web resources
│   │   ├── index.html
│   │   └── plc-file-upload.js
│   ├── src/main/resources/pages/    # Authenticated HTML pages
│   │   ├── connection-browser.html  # Unified tag browser + file upload
│   │   └── edit-program.html
│   └── src/test/java/               # Unit tests
│       └── com/inductiveautomation/logixemulator/gateway/
│           ├── parser/L5XParserTest.java
│           ├── validation/FileValidatorTest.java
│           └── web/FileUploadRoutesSecurityTest.java
├── designer/
│   └── src/main/java/
│       └── com/inductiveautomation/logixemulator/designer/
│           └── DesignerHook.java
├── common/
│   └── src/main/java/
│       └── (Shared utilities - currently minimal)
├── web-ui/                          # React TypeScript UI
│   ├── src/
│   │   └── (React components for file upload)
│   └── package.json
└── build.gradle.kts                 # Module configuration
```

## Component Breakdown

### 1. Device Driver Layer

#### LogixEmulatorExtensionPoint
**Purpose**: Registers the module as an OPC-UA device driver in Ignition's device connection system.

**Key Responsibilities**:
- Implements `DeviceExtensionPoint` interface
- Appears in Gateway Config → Devices → Create Device Connection
- Returns device configuration schema
- Instantiates device instances

**Location**: `gateway/device/LogixEmulatorExtensionPoint.java`

#### LogixEmulatorDevice
**Purpose**: Core device instance that manages OPC-UA address space and tag browsing.

**Key Responsibilities**:
- Implements `Device` interface from OPC-UA module
- Builds hierarchical OPC-UA address space from parsed tags
- Supports tag browsing and reading/writing
- Manages device lifecycle (connect, disconnect, shutdown)
- Handles file upload and hot-reload

**Tag Structure**:
```
[DeviceName]/
├── Controller:Global/
│   ├── MyTag (DINT)
│   ├── MyArray[0..9] (DINT)
│   └── MyUDT/ (folder)
│       ├── Value (DINT)
│       ├── Status (BOOL)
│       └── Name (STRING)
└── Controller:Program:MainProgram/
    └── LocalTag (DINT)
```

**Location**: `gateway/device/LogixEmulatorDevice.java` (800+ lines)

#### LogixEmulatorConfig
**Purpose**: Configuration schema for device instances using Ignition's form-based config system.

**Configuration Fields**:
- **File Path**: Path to PLC program file
- **Parser Type**: Auto-detect, L5K, L5X, JSON, CSV
- **Simulation Enabled**: Enable/disable value simulation
- **Simulation Type**: Ramp, sine, noise, toggle
- **Update Rate**: Simulation update interval (ms)

**Location**: `gateway/device/LogixEmulatorConfig.java`

#### AddressSpaceBuilder
**Purpose**: Constructs OPC-UA node hierarchy from parsed tag data.

**Key Responsibilities**:
- Creates folder nodes for program/controller scopes
- Expands UDT instances into member variables
- Maps PLC data types to OPC-UA types
- Builds browseable tree structure

**Location**: `gateway/device/AddressSpaceBuilder.java`

### 2. Parser Layer

#### PLCParser Interface
**Purpose**: Common interface for all PLC file format parsers.

```java
public interface PLCParser {
    JsonObject parseContent(String content, String fileName);
    boolean canHandle(String fileName);
    String getParserType();
}
```

**Standard Output Format** (JSON):
```json
{
  "controller": "MyController",
  "vendor": "rockwell",
  "global_tags": [
    {
      "name": "MyTag",
      "data_type": "DINT",
      "value": 42,
      "description": "My tag description"
    }
  ],
  "programs": [
    {
      "name": "MainProgram",
      "tags": [...]
    }
  ],
  "udts": [
    {
      "name": "MyUDT",
      "members": [
        {"name": "Value", "data_type": "DINT"},
        {"name": "Status", "data_type": "BOOL"}
      ]
    }
  ]
}
```

#### L5KParser
**Purpose**: Parses Rockwell RSLogix 5000 text export format (.l5k).

**Format**: Plain text with markers like `CONTROLLER`, `TAG`, `PROGRAM`, `DATATYPE`

**Features**:
- Controller-scoped and program-scoped tag parsing
- UDT definition extraction
- Data value parsing (DINT, REAL, BOOL, STRING)
- Array support

**Location**: `gateway/parser/L5KParser.java` (378 lines - refactored in v7.0.0)

#### L5XParser
**Purpose**: Parses Rockwell Studio 5000 XML export format (.l5x).

**Format**: XML with `<RSLogix5000Content>` root element

**Security Features**:
- ✅ XXE (XML External Entity) attack prevention
- ✅ DTD loading disabled
- ✅ External entity expansion disabled
- ✅ XInclude disabled

**Security Configuration**:
```java
DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setXIncludeAware(false);
factory.setExpandEntityReferences(false);
```

**Location**: `gateway/parser/L5XParser.java` (320 lines)

**Test Coverage**: `L5XParserTest.java` includes XXE prevention test ✅

#### ParserFactory
**Purpose**: Factory pattern for selecting appropriate parser based on file extension.

**Auto-detection Logic**:
1. Check file extension (.l5k, .l5x, .json, .csv)
2. If unknown, attempt content-based detection
3. Default to L5K parser for .txt files

**Location**: `gateway/parser/ParserFactory.java`

### 3. Validation Layer

#### FileValidator
**Purpose**: Validates file size, format, and content before processing.

**Validation Rules**:
- **Max File Size**: 50 MB (configurable)
- **L5K Format**: Must contain CONTROLLER/DATATYPE/TAG/PROGRAM markers, min 100 bytes
- **L5X Format**: Must contain `<RSLogix5000Content>`, min 1000 bytes
- **JSON Format**: Must start with `{` or `[`
- **CSV Format**: Must contain commas or semicolons

**Security Checks**:
- File size limits (prevents DoS)
- Content type validation
- Empty file rejection

**Location**: `gateway/validation/FileValidator.java`

**Test Coverage**: `FileValidatorTest.java` - 10 tests ✅

### 4. Web Layer

#### FileUploadRoutes
**Purpose**: RESTful API endpoints for file upload and device management.

**Endpoints**:
- `POST /data/logixemulator/upload?device={name}` - Upload file to device
- `GET /data/logixemulator/device/{name}/status` - Get device status
- `DELETE /data/logixemulator/device/{name}/delete` - Delete uploaded file
- `GET /data/logixemulator/devices` - List all simulator devices

**Security Features**:
- ✅ Authentication required (via Ignition SecurityContext)
- ✅ Path traversal prevention (filename and device name sanitization)
- ✅ File size limits (Content-Length check before reading)
- ✅ Null byte injection prevention
- ✅ Canonical path validation

**Sanitization Methods**:
```java
private String sanitizeFileName(String filename) {
    // Rejects: path traversal, null bytes, length > 255
    // Validates: canonical path extraction
}

private String sanitizeDeviceName(String deviceName) {
    // Rejects: special chars, path separators, length > 100
    // Allows: alphanumeric, underscore, hyphen only
}

private File validateFilePath(File storageDir, String deviceName, String fileName) {
    // Verifies file is within storage directory using canonical paths
    // Prevents symlink attacks and directory escaping
}
```

**Location**: `gateway/web/FileUploadRoutes.java` (1270 lines - refactored from 932, expanded with Connection Browser routes)

**Test Coverage**: `FileUploadRoutesSecurityTest.java` - 18 security tests ✅

#### React Web UI
**Purpose**: User-friendly file upload interface embedded in Gateway Config pages.

**Features**:
- Drag-and-drop file upload
- Device selection dropdown
- Upload progress indicators
- Error handling with user feedback

**Location**: `web-ui/src/` (React/TypeScript)

**Mounting**: Resources are mounted at `/res/logixemulator/` via Ignition web framework

### 5. Simulation Engine

#### OpcUaSimulationEngine
**Purpose**: Real-time value simulation for OPC-UA tags.

**Simulation Types**:
1. **Ramp**: Linear increase (wraps at max value)
2. **Sine**: Sinusoidal wave pattern
3. **Noise**: Random values within range
4. **Toggle**: Boolean flip every interval

**Configuration**:
- Update rate (milliseconds)
- Enable/disable per device
- Configurable via device settings

**Location**: `gateway/OpcUaSimulationEngine.java`

**Status**: Fully integrated with devices since v5.5.0

### 6. Module Hooks

#### SimulatorModuleHook
**Purpose**: Gateway module lifecycle hook.

**Key Responsibilities**:
- Register device extension point on startup
- Mount web routes for file upload API
- Initialize parser service
- Setup file watchers (TODO)
- Cleanup on shutdown

**Location**: `gateway/SimulatorModuleHook.java`

#### DesignerHook
**Purpose**: Designer module lifecycle hook (minimal functionality).

**Location**: `designer/DesignerHook.java`

## Data Flow

### File Upload Flow

```
User Uploads File
      ↓
┌─────────────────────────────────────────┐
│ FileUploadRoutes (Web Layer)            │
│ - Authentication check                   │
│ - File size validation (Content-Length) │
│ - Filename sanitization                  │
│ - Device name validation                 │
└──────────────┬──────────────────────────┘
               ↓
┌─────────────────────────────────────────┐
│ FileValidator (Validation Layer)         │
│ - Content size check (max 50MB)         │
│ - Format validation (L5K/L5X/JSON/CSV)  │
│ - Content markers verification           │
└──────────────┬──────────────────────────┘
               ↓
┌─────────────────────────────────────────┐
│ ParserFactory (Parser Selection)         │
│ - Auto-detect format from extension      │
│ - Instantiate appropriate parser         │
└──────────────┬──────────────────────────┘
               ↓
┌─────────────────────────────────────────┐
│ PLCParser (L5K/L5X/JSON/CSV)            │
│ - Parse file content                     │
│ - Extract tags, UDTs, programs           │
│ - Return standardized JSON               │
└──────────────┬──────────────────────────┘
               ↓
┌─────────────────────────────────────────┐
│ LogixEmulatorDevice                  │
│ - Receive parsed JSON                    │
│ - Store currentFilePath                  │
│ - Trigger address space rebuild          │
└──────────────┬──────────────────────────┘
               ↓
┌─────────────────────────────────────────┐
│ AddressSpaceBuilder                      │
│ - Build folder hierarchy                 │
│ - Expand UDT instances                   │
│ - Create OPC-UA nodes                    │
│ - Populate initial values                │
└──────────────┬──────────────────────────┘
               ↓
┌─────────────────────────────────────────┐
│ OPC-UA Server (Ignition)                │
│ - Tags available for browsing            │
│ - Clients can read/write values          │
│ - Simulation engine updates values       │
└─────────────────────────────────────────┘
```

### Device Configuration Flow

```
User Creates Device in Gateway Config
      ↓
┌─────────────────────────────────────────┐
│ LogixEmulatorExtensionPoint         │
│ - Provides config schema                 │
│ - Returns form fields to Gateway UI      │
└──────────────┬──────────────────────────┘
               ↓
┌─────────────────────────────────────────┐
│ LogixEmulatorConfig                  │
│ - Validates user input                   │
│ - File path validation                   │
│ - Parser type selection                  │
└──────────────┬──────────────────────────┘
               ↓
┌─────────────────────────────────────────┐
│ LogixEmulatorDevice.connect()       │
│ - Load file from configured path         │
│ - Parse using selected parser            │
│ - Build address space                    │
│ - Register with OPC-UA server            │
└─────────────────────────────────────────┘
```

## Security Architecture

### Security Layers

```
┌────────────────────────────────────────────────┐
│ Layer 1: Network/Transport                     │
│ - HTTPS for web uploads                        │
│ - OPC-UA encryption (Ignition native)         │
└────────────────────────────────────────────────┘
                     ↓
┌────────────────────────────────────────────────┐
│ Layer 2: Authentication & Authorization        │
│ - Ignition SecurityContext validation          │
│ - Session-based authentication                 │
│ - No anonymous access to upload endpoints      │
└────────────────────────────────────────────────┘
                     ↓
┌────────────────────────────────────────────────┐
│ Layer 3: Input Validation                      │
│ - File size limits (50MB max)                  │
│ - Filename sanitization (path traversal)       │
│ - Device name validation (alphanumeric only)   │
│ - Content-Length header verification           │
└────────────────────────────────────────────────┘
                     ↓
┌────────────────────────────────────────────────┐
│ Layer 4: Content Security                      │
│ - XXE attack prevention (XML parser)           │
│ - Format validation (L5K/L5X/JSON/CSV)         │
│ - Content marker verification                  │
│ - Null byte injection prevention               │
└────────────────────────────────────────────────┘
                     ↓
┌────────────────────────────────────────────────┐
│ Layer 5: Storage Security                      │
│ - Canonical path validation                    │
│ - Symlink attack prevention                    │
│ - Sandboxed storage directory                  │
│ - Secure credential management (env vars)      │
└────────────────────────────────────────────────┘
```

### Vulnerability Fixes (v5.4.9)

#### 1. XXE (XML External Entity) - CRITICAL ✅
**Location**: `L5XParser.java:52-67`
**Fix**: Disabled all XML external entity features
**Test**: `L5XParserTest.testXXEPrevention()`

#### 2. Authentication Bypass - CRITICAL ✅
**Location**: `FileUploadRoutes.java:771-858`
**Fix**: Replaced 163-line insecure auth with 81-line SecurityContext validation
**Removed**: Session age-based fallback (was granting access after 1 second!)

#### 3. Path Traversal - HIGH ✅
**Location**: `FileUploadRoutes.java:859-923`
**Fix**: Added sanitization and canonical path validation
**Tests**: `FileUploadRoutesSecurityTest` - 18 tests

#### 4. Hardcoded Credentials - HIGH ✅
**Location**: `gradle.properties`
**Fix**: Environment variable support with fallback defaults
**Protection**: Added to `.gitignore`, created `.template` file

#### 5. File Size DoS - MEDIUM ✅
**Location**: `FileUploadRoutes.java:137-216`
**Fix**: Content-Length check before reading, streaming read with size enforcement

### Security Best Practices

✅ **Implemented**:
- Principle of least privilege (scoped permissions)
- Defense in depth (multiple validation layers)
- Fail-safe defaults (reject unknown inputs)
- Secure credential management (environment variables)
- Comprehensive input validation
- Automated security testing (CI/CD)

🔄 **Recommended Improvements**:
- Add rate limiting to upload endpoints
- Implement audit logging for all file operations
- Add file type whitelist validation (magic numbers)
- Enable CSRF protection for web routes

## Testing Strategy

### Test Coverage

**Total Tests**: 92 (all passing)

#### Unit Tests

1. **L5XParserTest** (12 tests)
   - Basic parsing functionality
   - UDT expansion
   - Tag descriptions and arrays
   - **SECURITY: XXE prevention** ✅
   - Edge cases (malformed XML, missing elements)

2. **FileValidatorTest** (10 tests)
   - File size validation
   - Format detection (L5K, L5X, JSON, CSV)
   - Empty/null content handling
   - Boundary testing (exact size limit)

3. **FileUploadRoutesSecurityTest** (18 tests)
   - Filename sanitization (path traversal, null bytes)
   - Device name validation (special characters)
   - Path validation (canonical paths, symlinks)
   - Security consistency checks

### Test Infrastructure

**Frameworks**:
- JUnit Jupiter 5.10.1
- Mockito 5.7.0 (mocking)
- AssertJ 3.24.2 (fluent assertions)

**Test Resources**:
- `test-files/simple.l5k` - Basic L5K file
- `test-files/simple.l5x` - Basic L5X file
- `test-files/with-udt.l5x` - UDT test case
- `test-files/malicious-xxe.l5x` - XXE attack test

**CI/CD Integration**:
- Tests run automatically on every push/PR
- Test results uploaded as artifacts (30-day retention)
- Build fails if any test fails

### Testing Commands

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests L5XParserTest

# Run tests with full output
./gradlew test --console=plain

# Generate test reports
./gradlew test
# View: build/reports/tests/test/index.html
```

## Deployment

### Build Process

```bash
# Clean build
./gradlew clean

# Run tests
./gradlew test

# Build module (creates .modl file)
./gradlew build

# Output: build/LogixPLCEmulator-{version}.modl
```

### Module Signing

**Certificate Configuration**:
- Keystore: `gaskony-module-signing.p12`
- Password: Environment variables (secure)
- Fallback: Development credentials for CI builds

**Environment Variables**:
```bash
export IGNITION_KEYSTORE_PASSWORD="your-production-password"
export IGNITION_CERT_PASSWORD="your-production-cert-password"
```

**Template File**: `gradle.properties.template` (safe to commit)

### Installation

1. Build module: `./gradlew build`
2. Navigate to Gateway Config → System → Modules
3. Install Module → Select `.modl` file
4. Restart Gateway
5. Verify: Config → Devices → Create New Device → Select "Logix PLC Emulator"

### Runtime Requirements

- **Ignition Version**: 8.3.0 or higher
- **Required Modules**: OPC-UA module (com.inductiveautomation.opcua)
- **Java Version**: 17 (runtime)
- **Memory**: Minimum 512MB heap (for large PLC files)

### File Storage

- **Location**: `{IGNITION_DATA_DIR}/logix-emulator/`
- **Format**: `{DeviceName}_{FileName}`
- **Permissions**: Read/write by Ignition process user

## Future Enhancements

### Short-term (Next Release)

1. **Alarm Integration**
   - Parse PLC alarm definitions
   - Generate Ignition alarms from PLC alarm tags
   - Alarm state simulation

2. **Advanced Simulation**
   - Custom expression-based simulation
   - Interlinked tag simulation (e.g., totalizers, calculated values)
   - Event-driven simulation scenarios

3. **Data Export/Import**
   - Export tag configuration to CSV/JSON
   - Import tag values from CSV (batch initialization)

### Medium-term (Future Versions)

4. **Historical Data Simulation**
   - Pre-populated historical trends
   - Automatic historian tag creation

5. **Enhanced UI**
   - Real-time value monitoring improvements
   - Simulation controls (start/stop/reset)

6. **Performance Optimization**
   - Lazy loading for large tag lists
   - Caching for parsed structures

### Long-term (Research)

7. **Multi-Instance Management**
   - Centralized device registry (remove static registry)
   - Inter-device communication
   - Tag aliasing/linking

8. **Advanced Features**
   - Tag history playback
   - Alarm simulation
   - Program execution simulation (ladder logic)

## Technical Debt

### Known Issues

1. **Static Device Registry** (gateway/device/LogixEmulatorDevice.java)
   - Current: Static `ConcurrentHashMap` for device tracking
   - Problem: Global state, testing difficulties
   - Solution: Inject service via module hook, use instance registry

2. **Reflection Anti-Pattern** (gateway/web/FileUploadRoutes.java:798)
   - Current: Reflection to access SecurityContext API
   - Problem: Brittle, performance overhead
   - Solution: Use proper Ignition SDK authentication APIs

3. **Large Classes**
   - `L5KParser.java`: 378 lines (reduced from 843 in v7.0.0)
   - `FileUploadRoutes.java`: 1270 lines (expanded with Connection Browser routes)
   - Solution: Continue extracting helper classes, single responsibility principle

### Code Quality Goals

- ✅ Zero security vulnerabilities
- ✅ 100% test pass rate
- ✅ Automated CI/CD pipeline
- 🔄 Code coverage > 80% (current: ~60%)
- 🔄 Cyclomatic complexity < 10 per method
- 🔄 Class size < 500 lines

## Contributing

### Development Setup

1. Clone repository
2. Install JDK 17
3. Copy `gradle.properties.template` to `gradle.properties`
4. Run tests: `./gradlew test`
5. Build module: `./gradlew build`

### Code Standards

- **Java Style**: Google Java Style Guide
- **Formatting**: IntelliJ default formatter
- **Documentation**: Javadoc for public APIs
- **Testing**: Unit tests for all new features
- **Security**: Security review for all file/network operations

### Pull Request Process

1. Create feature branch from `master`
2. Implement changes with tests
3. Run `./gradlew test` locally
4. Commit with descriptive messages
5. Create pull request
6. CI pipeline runs automatically
7. Address review comments
8. Merge after approval

## Version History

### v9.0.0 (Current)
- Modern React + TypeScript UI with sidebar navigation
- SQLite-backed logging, dashboard, simulation views
- 113 tests, dead code cleanup, version consolidation

### v8.2.0
- Restyled Connection Browser to match Ignition 8.3 gateway theme

### v8.1.0
- Unified Connection Browser (merged File Upload + Tag Browser)
- Single navigation entry in Gateway Config

### v8.0.0
- ✅ Renamed to Logix PLC Emulator
- ✅ Removed unused vendor parsers
- ✅ SINT simulation support

### v5.4.9
- ✅ Fixed critical XXE vulnerability
- ✅ Fixed authentication bypass
- ✅ Fixed path traversal vulnerabilities
- ✅ Removed hardcoded credentials
- ✅ Added comprehensive test suite (40 tests)
- ✅ Updated dependencies (Gson 2.11.0, Modl 0.5.0)
- ✅ Added CI/CD pipeline

### v5.4.0
- Enhanced file upload with clear URL instructions

### v5.3.0
- Enabled manual tag writes
- Added clickable upload link

### v5.0.0
- Complete refactor to device driver pattern
- OPC-UA device connection integration
- React-based web UI

---

**Last Updated**: 2026-02-21
**Module Version**: 10.0.0
**Ignition SDK**: 8.3.0
**Java Version**: 17
