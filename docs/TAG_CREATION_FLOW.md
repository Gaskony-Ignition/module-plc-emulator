# Tag Creation and OPC-UA Address Space Flow

> **Version**: 9.2.6
> **Last Updated**: 2026-02-21
> **Purpose**: Complete reference for how PLC file tags become OPC-UA browseable nodes

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Quick Reference](#quick-reference)
3. [Flow Diagram](#flow-diagram)
4. [Detailed Walkthrough](#detailed-walkthrough)
5. [Critical Failure Points](#critical-failure-points)
6. [Troubleshooting Guide](#troubleshooting-guide)

---

## Executive Summary

The Logix PLC Emulator transforms PLC export files (L5K, L5X, JSON, CSV) into hierarchical OPC-UA tag structures through an 8-step pipeline:

1. **Device Startup** triggers tag creation workflow
2. **File Preparation** locates/validates PLC file on disk
3. **Parsing** converts file content to standardized JSON
4. **Root Node Creation** establishes device folder in OPC-UA
5. **Address Space Building** creates tag hierarchy with UDT expansion
6. **Value Initialization** sets initial tag values
7. **Simulation Engine** (optional) starts dynamic value updates
8. **Hot Reload** (optional) monitors file changes

**Critical Success Factors**:
- Device must be enabled in Gateway Config
- Valid PLC file must exist on disk
- Parser must successfully extract tags
- Address space builder must complete without errors

---

## Quick Reference

### Starting Point
```
LogixEmulatorDevice.onStartup()
Location: gateway/src/main/java/.../gateway/device/LogixEmulatorDevice.java:110
```

### 8-Step Tag Creation Flow

| Step | Method | Line | Critical? | What It Does |
|------|--------|------|-----------|--------------|
| 1 | `registerDevice()` | 117 | **YES** | Registers device in static registry (enables file uploads) |
| 2 | `prepareFile()` | 120 | **YES** | Validates file exists, returns path or null |
| 3 | `parseFile()` | 132 | **YES** | Parses L5K/L5X/JSON/CSV → JSON structure |
| 4 | `createRootNode()` | 141 | **YES** | Creates device folder in OPC-UA |
| 5 | `buildAddressSpace()` | 144 | **YES** | Converts JSON tags → OPC-UA nodes |
| 6 | `initializeSimulation()` | 147 | NO | Starts value simulation (optional) |
| 7 | `setupFileWatcher()` | 152 | NO | Enables hot reload (optional) |
| 8 | Set status "Running" | 156 | **YES** | Marks device operational |

### Key Classes & Files

```
gateway/src/main/java/.../gateway/
├── device/
│   ├── LogixEmulatorDevice.java         # Main device driver (800+ lines)
│   ├── LogixEmulatorConfig.java         # Configuration records
│   ├── LogixEmulatorExtensionPoint.java # Device registration
│   └── AddressSpaceBuilder.java             # OPC-UA node creation
├── parser/
│   ├── PLCParser.java                       # Parser interface
│   ├── ParserFactory.java                   # Parser selection
│   ├── L5KParser.java                       # Rockwell L5K (text) parser
│   ├── L5XParser.java                       # Rockwell L5X (XML) parser
│   ├── JsonPLCParser.java                   # Generic JSON parser
│   └── CsvParser.java                       # CSV parser
├── validation/
│   └── FileValidator.java                   # File size/format validation
└── web/
    └── FileUploadRoutes.java                # REST API for file upload
```

### Parser Type Mapping (v5.4.9 - Pure Java)

| Parser Type | File Extension | Parser Class | Security |
|-------------|---------------|--------------|----------|
| L5K | .l5k | L5KParser.java | ✅ Input validation |
| L5X | .l5x | L5XParser.java | ✅ XXE prevention (6 features) |
| JSON | .json | JsonPLCParser.java | ✅ Format validation |
| CSV | .csv | CsvParser.java | ✅ Delimiter validation |

**Note**: Python parser service was **removed in v2.0.0**. All parsing is now pure Java.

### REST API Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/data/logixemulator/upload?device={name}` | POST | Upload PLC file to device |
| `/data/logixemulator/devices` | GET | List all simulator devices |
| `/data/logixemulator/device/{name}/status` | GET | Get device status |
| `/data/logixemulator/device/{name}/delete` | DELETE | Delete uploaded file |

---

## Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│ TRIGGER EVENT                                                │
├─────────────────────────────────────────────────────────────┤
│ • Gateway Startup                                            │
│ • Device Created in Config                                   │
│ • Configuration Saved                                        │
│ • File Uploaded via REST API                                 │
│ • Hot Reload Detected                                        │
└──────────────────┬──────────────────────────────────────────┘
                   ↓
┌─────────────────────────────────────────────────────────────┐
│ STEP 1: Device Registration                                  │
├─────────────────────────────────────────────────────────────┤
│ LogixEmulatorDevice.onStartup():117                      │
│ SimulatorModuleHook.registerDevice(this)                     │
│                                                              │
│ Result: Device added to static registry                     │
│ Purpose: Enables FileUploadRoutes to find device instance   │
└──────────────────┬──────────────────────────────────────────┘
                   ↓
┌─────────────────────────────────────────────────────────────┐
│ STEP 2: File Preparation                                     │
├─────────────────────────────────────────────────────────────┤
│ prepareFile():225-257                                        │
│                                                              │
│ Logic:                                                       │
│ 1. Check config.parser().fileName() - from device config    │
│ 2. Check currentFilePath field - from file upload           │
│ 3. If both null: return null → "Ready - Waiting for upload" │
│ 4. If file doesn't exist: log error, return null            │
│ 5. If file exists: return File object                       │
│                                                              │
│ Success: Valid File object                                  │
│ Failure: null → Device status "Ready - Waiting for upload"  │
└──────────────────┬──────────────────────────────────────────┘
                   ↓
┌─────────────────────────────────────────────────────────────┐
│ STEP 3: File Parsing (CRITICAL)                             │
├─────────────────────────────────────────────────────────────┤
│ parseFile(File):346-371                                      │
│                                                              │
│ Flow:                                                        │
│ 1. Read file content to String                              │
│ 2. Get parser from ParserFactory                            │
│    └─ ParserFactory.getParser(fileName, config)             │
│       └─ Auto-detect format or use configured parser        │
│ 3. Call parser.parseContent(content, fileName)              │
│    └─ L5KParser/L5XParser/JsonPLCParser/CsvParser           │
│ 4. Return JsonObject with tags                              │
│                                                              │
│ Success: JsonObject with "global_tags", "programs", "udts"  │
│ Failure: null → Device status "Error: Failed to parse file" │
└──────────────────┬──────────────────────────────────────────┘
                   ↓
┌─────────────────────────────────────────────────────────────┐
│ STEP 4: Root Node Creation                                   │
├─────────────────────────────────────────────────────────────┤
│ createRootNode():586-612                                     │
│                                                              │
│ Creates: FolderNode with device name                        │
│ Path: [DeviceName]                                          │
│ NodeId: namespace=2, id=deviceName                          │
│                                                              │
│ Success: Root folder visible in OPC-UA browser              │
│ Failure: Tags have nowhere to attach (silent failure)       │
└──────────────────┬──────────────────────────────────────────┘
                   ↓
┌─────────────────────────────────────────────────────────────┐
│ STEP 5: Address Space Building (CREATES ALL TAGS)           │
├─────────────────────────────────────────────────────────────┤
│ buildAddressSpace(JsonObject):145                            │
│ → AddressSpaceBuilder.buildAddressSpace()                   │
│                                                              │
│ Process:                                                     │
│ 1. Create "Controller:Global" folder                        │
│ 2. Parse "global_tags" array from JSON                      │
│    └─ For each tag:                                         │
│       ├─ Check if UDT instance                              │
│       │  └─ Create folder + expand members                  │
│       ├─ Check if Array                                     │
│       │  └─ Create array elements [0], [1], ...             │
│       └─ Atomic tag                                         │
│          └─ Create variable node                            │
│ 3. Create "Controller:Program:{ProgramName}" folders        │
│ 4. Parse program-scoped tags                                │
│                                                              │
│ Key Methods:                                                 │
│ - addTag(tag, parentNode):157 - Processes each tag          │
│ - addAtomicTag(tag, parent):239 - Creates variable nodes    │
│ - expandUdt(tag, parent):180 - Expands UDT members          │
│ - nodeAdder.accept(node):280 - ADDS NODE TO OPC-UA          │
│                                                              │
│ Success: All tags visible in OPC-UA browser                 │
│ Failure: "WARNING: Zero tags created" in logs               │
└──────────────────┬──────────────────────────────────────────┘
                   ↓
┌─────────────────────────────────────────────────────────────┐
│ STEP 6: Value Initialization                                 │
├─────────────────────────────────────────────────────────────┤
│ mapInitialValue(tag):AddressSpaceBuilder.java               │
│                                                              │
│ Sets initial values from parsed JSON:                       │
│ - DINT → 0 or parsed value                                  │
│ - REAL → 0.0 or parsed value                                │
│ - BOOL → false or parsed value                              │
│ - STRING → "" or parsed value                               │
│                                                              │
│ Values are readable immediately via OPC-UA                   │
└──────────────────┬──────────────────────────────────────────┘
                   ↓
┌─────────────────────────────────────────────────────────────┐
│ STEP 7: Simulation Engine (OPTIONAL)                         │
├─────────────────────────────────────────────────────────────┤
│ initializeSimulation():147-149                               │
│                                                              │
│ If config.simulation().enabled() == true:                   │
│ 1. Create OpcUaSimulationEngine instance                     │
│ 2. Configure simulation type (RAMP/SINE/RANDOM/TOGGLE)      │
│ 3. Start periodic value updates                             │
│                                                              │
│ Result: Tag values change dynamically                       │
│ Optional: Tags work without simulation                      │
└──────────────────┬──────────────────────────────────────────┘
                   ↓
┌─────────────────────────────────────────────────────────────┐
│ STEP 8: Hot Reload Setup (OPTIONAL)                          │
├─────────────────────────────────────────────────────────────┤
│ setupFileWatcher():152-154                                   │
│                                                              │
│ If config.parser().hotReload() == true:                     │
│ 1. Create FileWatcher for currentFilePath                   │
│ 2. Register handleFileChange() callback                     │
│ 3. Monitor file for modifications                           │
│                                                              │
│ Result: File changes trigger automatic device reload        │
│ Optional: Manual reload via file upload                     │
└──────────────────┬──────────────────────────────────────────┘
                   ↓
┌─────────────────────────────────────────────────────────────┐
│ FINAL STATUS                                                 │
├─────────────────────────────────────────────────────────────┤
│ Device Status: "Running"                                     │
│ Tags: Visible in OPC-UA browser                             │
│ Browseable Path: [DeviceName]/Controller:Global/...         │
│ Writable: Yes (via OPC-UA client)                           │
│ Simulation: Active if enabled                               │
│ Hot Reload: Active if enabled                               │
└─────────────────────────────────────────────────────────────┘
```

---

## Detailed Walkthrough

### Device Lifecycle States

```
Initializing → Starting → Ready/Running/Error
```

| State | Description | Next Step |
|-------|-------------|-----------|
| **Initializing** | Device object created, constructor complete | onStartup() called by Ignition |
| **Starting** | onStartup() executing | Depends on file availability |
| **Ready - Waiting for upload** | No file provided in config | User must upload file |
| **Running** | File parsed successfully, tags created | Normal operation |
| **Reloading** | Hot reload triggered, rebuilding address space | Returns to Running |
| **Error: Failed to parse file** | Parser returned null | Check file format |
| **Error: {message}** | Other failure (file not found, etc.) | Check logs |

### Parser Selection Logic

```java
// ParserFactory.getParser() logic
if (config.parser().parserType() == ROCKWELL) {
    if (fileName.endsWith(".l5k")) {
        return new L5KParser();
    } else if (fileName.endsWith(".l5x")) {
        return new L5XParser();
    }
} else if (config.parser().parserType() == JSON) {
    return new JsonPLCParser();
} else if (fileName.endsWith(".csv")) {
    return new CsvParser();
}
// Auto-detection fallback if type not specified
```

### JSON Data Structure (Parser Output)

All parsers convert to this standardized format:

```json
{
  "controller": "ControllerName",
  "vendor": "rockwell",
  "global_tags": [
    {
      "name": "MyTag",
      "data_type": "DINT",
      "value": 42,
      "description": "Tag description"
    },
    {
      "name": "MyUDT",
      "data_type": "CustomUDT",
      "udt_members": [
        {"name": "Value", "data_type": "DINT", "value": 0},
        {"name": "Status", "data_type": "BOOL", "value": false}
      ]
    }
  ],
  "programs": [
    {
      "name": "MainProgram",
      "tags": [
        {"name": "LocalTag", "data_type": "REAL", "value": 3.14}
      ]
    }
  ],
  "udts": [
    {
      "name": "CustomUDT",
      "members": [
        {"name": "Value", "data_type": "DINT"},
        {"name": "Status", "data_type": "BOOL"}
      ]
    }
  ]
}
```

### OPC-UA Node Structure

**Resulting hierarchy in OPC-UA browser**:

```
[DeviceName]/
├── Controller:Global/
│   ├── MyTag (Int32) = 42
│   └── MyUDT/ (Folder)
│       ├── Value (Int32) = 0
│       └── Status (Boolean) = false
└── Controller:Program:MainProgram/
    └── LocalTag (Float) = 3.14
```

### Data Type Mapping

| PLC Type | OPC-UA Type | Java Type | Notes |
|----------|-------------|-----------|-------|
| DINT | Int32 | Integer | 32-bit signed integer |
| INT | Int16 | Short | 16-bit signed integer |
| REAL | Float | Float | 32-bit floating point |
| BOOL | Boolean | Boolean | Single bit |
| STRING | String | String | Character array |
| BYTE | SByte | Byte | 8-bit signed |

---

## Critical Failure Points

### 1. File Not Found

**Symptom**: Device status "Ready - Waiting for upload"

**Cause**: `prepareFile()` returns null

**Debug**:
```java
// Check logs for:
"No file configured for device {deviceName}"
"File not found: {filePath}"
```

**Fix**: Upload file via REST API or set file path in device config

---

### 2. Parser Returns Null

**Symptom**: Device status "Error: Failed to parse file"

**Cause**: File format doesn't match parser type or file is malformed

**Debug**:
```java
// Check logs for:
"Failed to parse file"
"Parser returned null for file {fileName}"
```

**Fix**:
- Verify file format (L5K vs L5X vs JSON)
- Check parser type in device config
- Validate file content (not empty, valid syntax)

---

### 3. Zero Tags Created

**Symptom**: Device status "Running" but no tags in OPC-UA browser

**Cause**: `buildAddressSpace()` completed but extracted zero tags

**Debug**:
```java
// Check logs for:
"WARNING: Zero tags created from parsed data"
"Building address space with {tagCount} global tags"
```

**Reasons**:
- File parsed successfully but contained no tag definitions
- JSON "global_tags" array empty
- Parser didn't extract tags properly

**Fix**:
- Review PLC file content
- Check if file actually contains tag definitions
- Verify parser extracted tags (check parsed JSON in logs)

---

### 4. Root Node Creation Failed

**Symptom**: Tags not visible, device status may be "Running"

**Cause**: `createRootNode()` failed silently

**Debug**:
```java
// Check logs for:
"Created root node: {nodePath}"
// If missing, node creation failed
```

**Fix**:
- Check OPC-UA server status
- Verify device name doesn't contain invalid characters
- Restart Gateway if OPC-UA server is unresponsive

---

### 5. Address Space Build Incomplete

**Symptom**: Some tags visible, others missing

**Cause**: Exceptions during `AddressSpaceBuilder.addTag()`

**Debug**:
```java
// Check logs for:
"Error adding tag {tagName}"
"Created variable: {tagPath}"  // Missing for some tags
```

**Fix**:
- Check for UDT definition issues
- Verify data types are supported
- Look for array dimension parsing errors

---

## Troubleshooting Guide

### Quick Diagnostic Checklist

- [ ] Device exists in **Gateway Config → OPC-UA → Device Connections**
- [ ] Device **Enabled** checkbox is checked
- [ ] **Parser Type** selected (not "Select Parser Type")
- [ ] File uploaded **OR** file path configured
- [ ] File exists on disk (check logs for path)
- [ ] Device status is "**Running**" (not "Ready - Waiting for upload")
- [ ] Gateway logs show "**Building address space**"
- [ ] Gateway logs show "**Created variable: {tagName}**" entries
- [ ] No "**WARNING: Zero tags created**" in logs
- [ ] OPC-UA browser shows **[DeviceName]** folder

### Common Issues & Solutions

#### Issue: "Device not visible in dropdown"

**Solution**:
1. Verify `LogixEmulatorExtensionPoint` registered in `SimulatorModuleHook.startup()`
2. Check module installed and enabled
3. Restart Gateway

#### Issue: "Upload button doesn't work"

**Solution**:
1. Check authentication (must be logged in as admin)
2. Verify device name correct (case-sensitive)
3. Check browser console for JavaScript errors
4. Try REST API directly: `POST /data/logixemulator/upload?device=DeviceName`

#### Issue: "Tags appear then disappear"

**Solution**:
1. Check hot reload not triggering unnecessarily
2. Verify file not being modified externally
3. Check simulation engine not causing exceptions
4. Review Gateway logs for device restart messages

#### Issue: "UDT members not expanding"

**Solution**:
1. Verify UDT definition in "udts" array of parsed JSON
2. Check "udt_members" array in tag definition
3. Confirm data types of members are valid
4. Look for `expandUdt()` errors in logs

#### Issue: "File upload returns error"

**Security-related errors (v5.4.9)**:
- "Invalid device name" → Device name contains special characters
- "Invalid filename" → Filename contains path traversal (../ or \)
- "File too large" → File exceeds 50MB limit
- "Unauthorized" → Not logged in or session expired

#### Issue: "Parser type selection doesn't work"

**Solution**:
1. Clear browser cache
2. Verify device config saved properly
3. Check `LogixEmulatorConfig.parserType()` returns correct value
4. Try setting parser type via device config form

### Debug Commands

#### Check Device Status (REST API)
```bash
curl http://localhost:8088/data/logixemulator/device/{deviceName}/status
```

#### List All Devices
```bash
curl http://localhost:8088/data/logixemulator/devices
```

#### Upload File
```bash
curl -X POST \
  -H "Content-Type: text/plain" \
  --data-binary @program.l5k \
  "http://localhost:8088/data/logixemulator/upload?device=MyDevice"
```

#### Check Gateway Logs
```bash
# Look for these patterns:
grep "Building address space" /var/lib/ignition/logs/wrapper.log
grep "Created variable" /var/lib/ignition/logs/wrapper.log
grep "WARNING: Zero tags" /var/lib/ignition/logs/wrapper.log
grep "Failed to parse" /var/lib/ignition/logs/wrapper.log
```

### Log Messages Reference

| Log Message | Meaning | Action Required |
|-------------|---------|-----------------|
| "Building address space with X global tags" | Starting tag creation | Normal operation |
| "Created variable: {tagPath}" | Tag successfully created | Normal operation |
| "WARNING: Zero tags created" | No tags extracted from file | Check file content |
| "Failed to parse file" | Parser error | Verify file format |
| "No file configured" | File path null | Upload file |
| "File not found: {path}" | File doesn't exist | Check file path |
| "Device registered: {name}" | Device startup successful | Normal operation |
| "Error adding tag {name}" | Tag creation failed | Check data type/UDT |

---

## File Upload Flow (v5.4.9 Security Hardened)

### REST API Upload

```
User/System → POST /data/logixemulator/upload?device={deviceName}
      ↓
FileUploadRoutes.handleFileUpload():101
      ↓
[Security Layer - NEW in v5.4.9]
├─ Authentication check (SecurityContext validation)
├─ Device name sanitization (alphanumeric only)
├─ Filename sanitization (no path traversal)
├─ File size validation (max 50MB)
└─ Content-Length header check
      ↓
[File Processing]
├─ Read request body content
├─ Validate file format (L5K/L5X/JSON/CSV)
├─ Save to disk: {dataDir}/logix-emulator/{deviceName}_{fileName}
└─ Update device configuration
      ↓
[Device Reload]
├─ updateDeviceFilePath() via reflection
├─ reloadDevice() triggers handleFileChange()
└─ Device re-runs onStartup() flow
      ↓
[Result]
└─ Device status changes to "Running"
```

### Security Features (v5.4.9)

**Authentication**:
- Uses Ignition SecurityContext
- No anonymous access
- Session validation

**Input Sanitization**:
- Device name: Alphanumeric + underscore + hyphen only
- Filename: No path traversal (.., /, \), null bytes, or excessive length
- Canonical path validation prevents symlink attacks

**File Size Protection**:
- Content-Length header validation before reading
- Streaming read with size enforcement (prevents DoS)
- Configurable limit (default 50MB)

**Format Validation**:
- L5K files must contain CONTROLLER/TAG/PROGRAM markers
- L5X files must contain `<RSLogix5000Content>` and be at least 1000 bytes
- JSON files must start with `{` or `[`
- CSV files must contain delimiters

---

## Version History & Changes

### v5.4.9 (2025-11-22)
- ✅ Removed Python parser service dependency (pure Java)
- ✅ Added comprehensive security to file upload (XXE prevention, path traversal, auth)
- ✅ Added 40 unit tests including security tests
- ✅ Enhanced L5XParser with 6 XXE prevention features
- ✅ FileUploadRoutes security hardening

### v3.0.0 (2025-11-14)
- Added comprehensive Rockwell predefined type support (22 types)
- Enhanced UDT expansion for complex structures

### v2.0.0 (2025-11-11)
- **BREAKING**: Removed Python parser service
- Migrated to pure Java parsers (L5KParser, L5XParser, JsonPLCParser, CsvParser)
- Improved performance and reliability

### v1.x (Historical)
- Initial implementation with Python parser service
- Basic L5K support

---

## Further Reading

- **[ARCHITECTURE.md](ARCHITECTURE.md)** - Complete technical architecture
- **[SECURITY.md](SECURITY.md)** - Security best practices
- **[TESTING.md](TESTING.md)** - Testing procedures
- **[BUILD.md](BUILD.md)** - Building the module
- **[CHANGELOG.md](../CHANGELOG.md)** - Full version history

---

**For questions or issues, consult the Gateway logs and use the troubleshooting checklist above.**
