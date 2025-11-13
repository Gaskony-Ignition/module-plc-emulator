# Tag Creation Flow - Executive Summary

## Quick Reference

### 1. WHERE DOES TAG CREATION START?

**File:** `/modules/ignition-plc-simulator/plc-simulator-module/gateway/src/main/java/com/inductiveautomation/plcsimulator/gateway/device/EnhancedSimulatorDevice.java`

**Method:** `onStartup()` at **Line 110**

**Triggers:**
- Gateway startup
- Device creation/enable
- File upload via web interface
- File change (if hot reload enabled)

### 2. JAVA-TO-PYTHON COMMUNICATION

**Parser Service Location:** `localhost:5000` (or Docker IP `172.17.0.1:5000`)

**Communication Method:**
```
HTTP GET /parse/{endpoint}?file={filePath}
```

**Parser Type Mapping:**
- `"rockwell"` → `/parse/l5k`
- `"json"` → `/parse/json`
- `"siemens"` → `/parse/siemens`
- `"schneider"` → `/parse/schneider`
- `"beckhoff"` → `/parse/beckhoff`

**Key Code:** `EnhancedSimulatorDevice.java:374-444`

**Fallback:** If service unavailable, uses built-in Java parsers (L5XParser, JsonPLCParser, CsvParser)

### 3. PARSER → OPC-UA NODE CONVERSION

**Builder Class:** `AddressSpaceBuilder.java:69-149`

**Three Main Methods:**
1. `buildAddressSpace()` - Orchestrates the entire process
2. `addTag()` - Processes each tag (detects type: UDT, Array, or Atomic)
3. `addAtomicTag()` - Creates final variable nodes

**Key Transformation Steps:**
```
JSON Tag Object
    ├─ Validate: has "name" and "data_type"
    ├─ Map data type: DINT → Int32, BOOL → Boolean, etc.
    ├─ Extract initial value or use default
    ├─ Create UaVariableNode with:
    │   ├─ NodeId
    │   ├─ BrowseName
    │   ├─ DisplayName
    │   ├─ DataType (OpcUaDataType)
    │   ├─ AccessLevel: READ_WRITE
    │   └─ Initial value
    └─ ADD TO NODE MANAGER (Line 280)
       └─ TAG VISIBLE IN OPC-UA
```

### 4. CONDITIONS FOR TAGS TO APPEAR

**All 10 must be TRUE:**

| # | Condition | Critical? |
|---|-----------|-----------|
| 1 | Device created in Gateway Config | YES |
| 2 | Device enabled | YES |
| 3 | Parser Type selected | YES |
| 4 | File uploaded or path provided | YES |
| 5 | File exists on disk | YES |
| 6 | File parseable | YES |
| 7 | Parsed JSON has tags | YES |
| 8 | Root node created | YES |
| 9 | Node manager accepts nodes | YES |
| 10 | Device status = "Running" | YES |

**Dependency Chain:**
```
Device Exists → Device Enabled → prepareFile() → parseFile() 
→ createRootNode() → buildAddressSpace() → nodeAdder.accept() 
→ Status = "Running" → TAGS VISIBLE
```

### 5. PARSER TYPE DEPENDENCY

**YES - CRITICAL**

**Field:** `EnhancedSimulatorConfig.ParserType` enum at **Line 156**

**Valid Values:**
- `ROCKWELL` (key: "rockwell") → Rockwell L5K/L5X files
- `JSON` (key: "json") → JSON PLC config
- `SIEMENS` (key: "siemens") → Siemens TIA Portal
- `SCHNEIDER` (key: "schneider") → Schneider Electric
- `BECKHOFF` (key: "beckhoff") → Beckhoff TwinCAT

**Impact if Wrong:**
- Wrong parser endpoint called on parser service
- Built-in parser fails to recognize file format
- Tags may not parse correctly
- Device shows error or empty address space

**Key Code Path:**
```
EnhancedSimulatorDevice.parseFile() [Line 348]
├─ config.parser().parserType() ◄─── USED HERE
├─ parserType.getKey() → endpoint determination
└─ Determines parser service URL or fallback parser
```

**File Upload Note:**
- Parser type is **NOT** auto-detected from file
- User must select correct type before/after upload
- Changing parser type after upload requires re-upload

---

## 8-STEP STARTUP SEQUENCE

```
[1] SimulatorModuleHook.registerDevice()
    └─ Device added to registry for file upload routes

[2] prepareFile()
    └─ Locate/create file on disk
    └─ IF NULL: Status = "Ready - Waiting for file upload" (EARLY RETURN)

[3] parseFile(currentFilePath) ◄─── PARSER TYPE USED
    ├─ Try parser service (optional)
    └─ Fallback to built-in parser (required)
    └─ IF NULL: Status = "Error: Failed to parse file" (RETURN)

[4] createRootNode()
    └─ Create OPC-UA folder for device

[5] buildAddressSpace()
    ├─ Process global_tags → Create Controller:Global folder
    ├─ Process programs → Create Programs folder structure
    └─ For each tag: addTag() → addAtomicTag() → nodeAdder.accept()
    └─ IF ZERO TAGS: Log WARNING "Zero tags created"

[6] initializeSimulation() (optional, if enabled)
    └─ Start OpcUaSimulationEngine

[7] setupFileWatcher() (optional, if hot reload enabled)
    └─ Monitor file for changes

[8] Status = "Running"
    └─ TAGS VISIBLE IN OPC-UA
```

---

## Critical File Locations

| Component | File | Key Method | Line |
|-----------|------|-----------|------|
| **Main Device Class** | EnhancedSimulatorDevice.java | onStartup() | 110 |
| **Configuration** | EnhancedSimulatorConfig.java | ParserType enum | 20 |
| **Address Space** | AddressSpaceBuilder.java | buildAddressSpace() | 69 |
| **File Upload** | FileUploadRoutes.java | handleFileUpload() | 101 |
| **Parser Service** | ParserService.java | start() | 47 |
| **Parser Factory** | ParserFactory.java | getParserByType() | 56 |
| **Module Hook** | SimulatorModuleHook.java | startup() | 43 |
| **Extension Point** | EnhancedSimulatorExtensionPoint.java | createDevice() | 52 |

---

## Top 3 Reasons Tags Don't Appear

### 1. Parser Type Not Selected or Wrong (40% of issues)
**Symptom:** Device error or empty address space
**Fix:** Edit device config → Select correct parser type → Re-upload file

### 2. File Not Uploaded (30% of issues)
**Symptom:** Status = "Ready - Waiting for file upload"
**Fix:** Use file upload interface to upload PLC file

### 3. File Format Doesn't Match Parser Type (20% of issues)
**Symptom:** Status = "Error: Failed to parse file"
**Fix:** Verify file format matches parser type or select different parser

---

## Status Messages & Meanings

| Status | Meaning | Action |
|--------|---------|--------|
| "Initializing" | Device just created | Wait for completion |
| "Starting" | onStartup() running | Wait for completion |
| "Ready - Waiting for file upload" | Device started, no file | Upload file via UI |
| "Running" | Device ready, tags visible | Tags available in OPC-UA |
| "Reloading" | Hot reload in progress | Wait for completion |
| "Error: ..." | Device failed to start | Check logs, fix issue |

---

## Monitoring & Debugging

### Check Device Status
```
GET /main/data/plcsimulator/device/{deviceName}/status
```

### Monitor Logs for Keywords
- "Building address space" → Address space build started
- "Created variable" → Tag successfully created
- "WARNING: Zero tags created" → No tags found in file
- "Parser service not available" → Using fallback parser
- "Failed to parse file" → Parser error

### Verify Node Creation
```
EnhancedSimulatorDevice.java:280
└─ nodeAdder.accept(variableNode) ◄─ This must execute
```

---

## Expected JSON Structure from Parser

```json
{
  "controller": "ControllerName",
  "vendor": "rockwell",
  "global_tags": [
    {
      "name": "TagName",
      "data_type": "DINT",
      "initial_value": 100
    }
  ],
  "programs": [
    {
      "name": "ProgramName",
      "tags": [
        {
          "name": "LocalTag",
          "data_type": "BOOL",
          "initial_value": false
        }
      ]
    }
  ]
}
```

**Required Fields per Tag:**
- `"name"` - Tag identifier
- `"data_type"` - Data type string (DINT, BOOL, REAL, STRING, etc.)

**Optional Fields:**
- `"initial_value"` - Starting value (defaults to 0/false/"" if missing)
- `"description"` - Tag description
- `"udt_members"` - Array for UDT instances
- `"isArray"` - Boolean for array tags
- `"dimensions"` - Array size specification

---

## For More Details

See:
- **TAG_CREATION_FLOW.md** - Complete 10,000+ word documentation
- **FLOW_DIAGRAM.txt** - Visual ASCII diagrams of the process
- **Source files** - Each component has detailed Javadoc comments

---

Generated: 2025-11-13
Based on: PLC Simulator v2.2.0
Path: `/modules/ignition-plc-simulator/`

