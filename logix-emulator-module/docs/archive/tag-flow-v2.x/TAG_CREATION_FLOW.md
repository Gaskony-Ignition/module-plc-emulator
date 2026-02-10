# PLC Simulator Tag Creation and Display Flow

## Executive Summary

The tag creation flow follows a multi-stage pipeline from device configuration through parser service to OPC-UA address space rendering. This document traces the exact sequence of method calls, identifies critical failure points, and explains the dependencies that must be satisfied for tags to appear.

---

## 1. WHERE DOES THE TAG CREATION PROCESS START?

### Primary Triggers

1. **Device Startup** - When the Ignition Gateway starts or a device is created
2. **Configuration Save** - When a user modifies device settings in Gateway Config UI
3. **File Upload** - When a user uploads a PLC file via the web interface
4. **Hot Reload** - When file content changes (if hot reload is enabled)

### Starting Point Code Flow

```
Ignition Gateway
    ↓
EnhancedSimulatorExtensionPoint.createDevice()
    [File: /modules/ignition-plc-simulator/plc-simulator-module/gateway/src/main/java/com/inductiveautomation/plcsimulator/gateway/device/EnhancedSimulatorExtensionPoint.java, Line 52-58]
    ↓
EnhancedSimulatorDevice constructor()
    [File: EnhancedSimulatorDevice.java, Line 67-78]
    ↓
Lifecycle Manager registers onStartup() callback
    ↓
onStartup() method executes
    [File: EnhancedSimulatorDevice.java, Line 110-169]
```

### Critical Initial Steps in onStartup()

| Line | Action | Purpose | Critical? |
|------|--------|---------|-----------|
| 117 | `SimulatorModuleHook.registerDevice()` | Registers device so web upload routes can find it | YES - Without this, file uploads fail |
| 120 | `prepareFile()` | Locates or creates the PLC file on disk | YES - Must return valid file path |
| 132 | `parseFile()` | Parses PLC file into JSON structure | YES - Parser failure = no tags |
| 141 | `createRootNode()` | Creates OPC-UA folder for device | YES - Tags attach to this |
| 144 | `buildAddressSpace()` | Converts JSON into OPC-UA nodes | YES - This creates visible tags |
| 147-149 | `initializeSimulation()` | Starts value simulation (optional) | NO - Tags visible without this |
| 152-154 | `setupFileWatcher()` | Enables hot reload (optional) | NO - Tags visible without this |

---

## 2. HOW DOES JAVA MODULE COMMUNICATE WITH PYTHON PARSER SERVICE?

### Communication Architecture

```
EnhancedSimulatorDevice.parseFile()
    ↓
tryParserService()
    [File: EnhancedSimulatorDevice.java, Line 374-444]
    ↓
HTTP GET Request to Parser Service
    Protocol: HTTP/1.1
    Format: GET /parse/{endpoint}?file={filePath}
    URL: http://172.17.0.1:5000/parse/{endpoint}?file=/path/to/file.l5k
    ↓
Parser Service (Python)
    Processes Request
    Returns JSON with parsed tags
    ↓
Java parses response as JsonObject
    ↓
Returns parsed data or NULL on failure
```

### Parser Service Integration Points

#### Starting the Parser Service

**File:** `/modules/ignition-plc-simulator/plc-simulator-module/gateway/src/main/java/com/inductiveautomation/plcsimulator/gateway/SimulatorModuleHook.java`

```java
startup() method (Line 43-66):
├─ Create ParserService instance
│   └─ new ParserService(logger, "localhost", 5000) [Line 57]
├─ Start the service
│   └─ parserService.start() [Line 58]
│       └─ Extracts embedded Python executable
│       └─ Starts subprocess with ProcessBuilder
│       └─ Waits 3000ms for service to initialize
│       └─ Verifies with health check
└─ Exception handling
    └─ Logs warning but continues (parser is OPTIONAL)
```

#### HTTP Call to Parser Service

**File:** `EnhancedSimulatorDevice.java, Lines 374-444`

```java
tryParserService(String filePath, String parserKey)
├─ Determine endpoint based on parser type:
│   ├─ "rockwell" → "l5k"
│   ├─ "json" → "json"
│   ├─ "siemens" → "siemens"
│   ├─ "schneider" → "schneider"
│   └─ "beckhoff" → "beckhoff"
├─ Build URL: http://172.17.0.1:5000/parse/{endpoint}?file={filePath}
├─ Create HttpURLConnection
│   ├─ Method: GET
│   ├─ Connection timeout: 2000ms
│   └─ Read timeout: 10000ms
├─ Check response code 200
├─ Parse JSON response
├─ Check for error field in response
└─ Return JsonObject or NULL
```

**⚠️ CRITICAL NETWORKING NOTE:**
- Uses Docker bridge IP `172.17.0.1` to reach host from container
- This ONLY works when running in Docker
- For non-Docker deployments, connection will fail and fallback to built-in parser
- See Line 391-392 for the IP configuration

### Fallback to Built-in Parser

If parser service unavailable:
```
parseFile() [Line 354]
    ↓ (returns null)
parseFileBuiltIn() [Line 362, 450-489]
    ↓
ParserFactory.getParserByType(parserType.getKey())
    [File: ParserFactory.java, Line 56-72]
    ↓
Returns: L5XParser, JsonPLCParser, or CsvParser
    ↓
parser.parse(filePath)
    [File: L5XParser.java, Line 38-46 for example]
    ↓
Returns JsonObject with tag data
```

---

## 3. HOW ARE TAGS FROM PARSER CONVERTED TO OPC-UA NODES?

### Address Space Build Pipeline

**File:** `EnhancedSimulatorDevice.java, Lines 552-572`

```
buildAddressSpace()
    ↓
Create AddressSpaceBuilder instance
    [Line 558-562]
    ├─ Pass nodeManager::addNode as callback
    ├─ Pass device name
    └─ Pass logger for diagnostics
    ↓
Create NodeContext
    [Line 564-567]
    ├─ Pass OPC-UA node context
    └─ Pass Ignition device context
    ↓
builder.buildAddressSpace(parsedData, rootNode, nodeContext)
    [AddressSpaceBuilder.java, Line 69-149]
```

### Detailed Tag Processing in AddressSpaceBuilder

**File:** `AddressSpaceBuilder.java, Lines 69-149`

```
buildAddressSpace()
├─ Log: "Building address space for device: {deviceName}"
├─ Process Global Tags [Line 77-96]
│   ├─ Check if "global_tags" exists in parsed JSON
│   ├─ Create "Controller:Global" folder
│   │   └─ UaFolderNode created and added to node manager
│   └─ For each tag in global_tags:
│       └─ Call addTag()
├─ Process Programs [Line 99-139]
│   ├─ Check if "programs" exists in parsed JSON
│   ├─ Create "Programs" parent folder
│   └─ For each program:
│       ├─ Create individual program folder
│       └─ For each tag in program.tags:
│           └─ Call addTag()
└─ Validation [Line 142-149]
    ├─ Count total tags created
    └─ Log warning if zero tags
```

### Tag Type Processing: addTag() Method

**File:** `AddressSpaceBuilder.java, Lines 157-234`

```
addTag(JsonObject tag, UaFolderNode parentFolder, ...)
├─ VALIDATE INPUT [Line 164-171]
│   ├─ Check "name" field exists
│   ├─ Check "data_type" field exists
│   └─ Skip tag if either missing (log warning)
├─ DETECT TAG TYPE
│   ├─ Check "udt_members" [Line 177-197]
│   │   └─ CREATE FOLDER + MEMBER VARIABLES
│   │       └─ For each member: addAtomicTag()
│   ├─ Check "isArray" [Line 201-230]
│   │   └─ CREATE ARRAY ELEMENT NODES
│   │       └─ Parse dimensions, create indexed nodes
│   └─ Otherwise [Line 232]
│       └─ Treat as atomic tag
└─ Return
```

### Atomic Tag Creation: addAtomicTag() Method

**File:** `AddressSpaceBuilder.java, Lines 239-284`

```
addAtomicTag(JsonObject tag, UaFolderNode parentFolder, ...)
├─ VALIDATE [Line 246-253]
│   ├─ Check "name" exists
│   └─ Check "data_type" exists
├─ MAP DATA TYPE [Line 259]
│   ├─ Call mapDataType(dataType string)
│   │   [Line 301-310]
│   │   └─ Returns OpcUaDataType
│   │       (Boolean, Int16, Int32, Float, String, etc.)
├─ GET INITIAL VALUE [Line 262]
│   ├─ Call getInitialValue(tag, dataType)
│   │   [Line 316-339]
│   │   └─ Returns typed initial value
│   │       (0, 0.0f, false, "", etc.)
├─ CREATE VARIABLE NODE [Line 265-274]
│   ├─ UaVariableNode.build() with:
│   │   ├─ NodeId: context.nodeId(pathPrefix + "/" + tagName)
│   │   ├─ BrowseName: context.qualifiedName(tagName)
│   │   ├─ DisplayName: LocalizedText(tagName)
│   │   ├─ DataType: mapped OpcUaDataType
│   │   ├─ TypeDefinition: BaseDataVariableType
│   │   ├─ AccessLevel: READ_WRITE
│   │   └─ UserAccessLevel: READ_WRITE
├─ SET INITIAL VALUE [Line 277]
│   └─ variableNode.setValue(new DataValue(new Variant(initialValue)))
├─ ADD TO ADDRESS SPACE [Line 280-281]
│   ├─ nodeAdder.accept(variableNode)  ← THIS ADDS TO NODE MANAGER
│   └─ parentFolder.addOrganizes(variableNode)
└─ Log debug message
```

### JSON Structure Expected from Parser

```json
{
  "controller": "MainController",
  "vendor": "rockwell",
  "global_tags": [
    {
      "name": "Speed",
      "data_type": "DINT",
      "initial_value": 100,
      "description": "Motor speed RPM"
    },
    {
      "name": "Motor1",
      "data_type": "Motor_UDT",
      "udt_members": [
        {
          "name": "Running",
          "data_type": "BOOL",
          "initial_value": false
        },
        {
          "name": "Speed",
          "data_type": "INT",
          "initial_value": 0
        }
      ]
    },
    {
      "name": "Speeds",
      "data_type": "INT",
      "isArray": true,
      "dimensions": "10",
      "initial_value": 0
    }
  ],
  "programs": [
    {
      "name": "MainProgram",
      "tags": [
        {
          "name": "Counter",
          "data_type": "DINT",
          "initial_value": 0
        }
      ]
    }
  ]
}
```

---

## 4. WHAT CONDITIONS MUST BE MET FOR TAGS TO APPEAR?

### Critical Conditions - ALL MUST BE TRUE

| # | Condition | File Reference | Line | Impact if Failed |
|---|-----------|-----------------|------|------------------|
| 1 | Device created in Gateway Config | EnhancedSimulatorExtensionPoint.java | 52-58 | Device never starts |
| 2 | Device enabled in config | EnhancedSimulatorConfig.java | 114 | onStartup() not called |
| 3 | Parser Type selected/saved | EnhancedSimulatorConfig.java | 156 | Device won't know how to parse |
| 4 | File uploaded OR file path provided | EnhancedSimulatorDevice.java | 272-337 | prepareFile() returns null |
| 5 | File path exists on disk | EnhancedSimulatorDevice.java | 454 | Parser has nothing to read |
| 6 | File content valid/parseable | EnhancedSimulatorDevice.java | 346-368 | parseFile() returns null |
| 7 | Parsed JSON has proper structure | AddressSpaceBuilder.java | 77-139 | No tags added to address space |
| 8 | Root node created successfully | EnhancedSimulatorDevice.java | 525-547 | Tags have nowhere to attach |
| 9 | Node manager accepts nodes | AddressSpaceBuilder.java | 280 | Tags created but not registered |
| 10 | Device reaches "Running" status | EnhancedSimulatorDevice.java | 156 | Device in error state |

### Condition Dependency Chain

```
Condition 1 (Device exists)
    ↓ [MUST BE TRUE]
Condition 2 (Device enabled)
    ↓ [MUST BE TRUE]
onStartup() executes
    ↓
Condition 3 (Parser Type saved)
    ↓ [MUST BE TRUE]
Condition 4 (File provided)
    ↓ [MUST BE TRUE]
Condition 5 (File exists on disk)
    ↓ [MUST BE TRUE]
Condition 6 (File parseable)
    ↓ [MUST BE TRUE]
Condition 7 (JSON has tags)
    ↓ [MUST BE TRUE]
Condition 8 (Root node created)
    ↓ [MUST BE TRUE]
buildAddressSpace() executes
    ↓
Condition 9 (Node manager adds nodes)
    ↓ [MUST BE TRUE]
Condition 10 (Status = "Running")
    ↓
TAGS VISIBLE IN OPC-UA
```

### Status Field Tracking

```
"Initializing"
    ↓ [Line 113]
"Starting"
    ↓ [Line 113]
"Ready - Waiting for file upload" OR "Running"
    ↓ [Line 123 or 156]
If error at any point:
    ↓ [Line 166]
"Error: {message}"
```

**Check device status with:** `/main/data/plcsimulator/device/{deviceName}/status` endpoint

---

## 5. IS THERE ANY DEPENDENCY ON PARSER TYPE BEING SAVED CORRECTLY?

### YES - CRITICAL DEPENDENCY

**File:** `EnhancedSimulatorConfig.java, Lines 150-156`

```java
@Label("Parser Type")
@FormField(FormFieldType.SELECT)
@Description("Select the PLC vendor/format")
@DefaultValue("ROCKWELL")
@Required
ParserType parserType
```

### Parser Type Flow

```
Device Configuration
    ↓
parserType field (Line 156)
    ├─ Enum: ROCKWELL, JSON, SIEMENS, SCHNEIDER, BECKHOFF
    ├─ Must match one of these exactly
    └─ Has getKey() method that returns lowercase string
    ↓
EnhancedSimulatorDevice.parseFile() [Line 346-368]
    ├─ Gets parserType: config.parser().parserType() [Line 348]
    ├─ Calls: parserType.getKey() [Line 349]
    │   └─ Returns: "rockwell", "json", "siemens", "schneider", "beckhoff"
    ├─ Logs: "Parsing file: {} with parser: {}" [Line 351]
    └─ Uses key to determine parser service endpoint [Line 380-387]
    ↓
Case 1: Parser Service Available
    ├─ Maps key to endpoint: "rockwell" → "l5k"
    ├─ Calls: tryParserService(filePath, parserKey) [Line 354]
    ├─ Builds URL: http://172.17.0.1:5000/parse/{endpoint}?file={filePath}
    └─ Parser service must support this endpoint
    ↓
Case 2: Parser Service Unavailable
    ├─ Calls: parseFileBuiltIn(filePath, parserType) [Line 362]
    ├─ Calls: ParserFactory.getParserByType(parserType.getKey()) [Line 462]
    │   [File: ParserFactory.java, Line 56-72]
    ├─ Matches key against registered parsers
    │   ├─ "rockwell" → L5XParser
    │   ├─ "json" → JsonPLCParser
    │   ├─ "csv" → CsvParser
    │   └─ Unknown → NULL (ERROR)
    └─ Parser executes parse(filePath)
    ↓
Result
    ├─ JsonObject with tags OR
    └─ NULL (parses to demo structure [Line 457])
```

### Parser Type Mapping Details

**File:** `EnhancedSimulatorConfig.java, Lines 20-56`

```java
ParserType enum:
├─ ROCKWELL("rockwell", "Rockwell L5K (Allen-Bradley)")
├─ JSON("json", "JSON Format")
├─ SIEMENS("siemens", "Siemens TIA Portal")
├─ SCHNEIDER("schneider", "Schneider Electric")
└─ BECKHOFF("beckhoff", "Beckhoff TwinCAT")

Methods:
├─ getKey() → String (lowercase key)
├─ getDisplayName() → String (UI display)
└─ fromKey(String key) → ParserType (lookup by key)
```

### What Happens If Parser Type Wrong/Missing?

| Scenario | Behavior | Result |
|----------|----------|--------|
| Parser Type NOT selected | Defaults to ROCKWELL (Line 154) | Device tries L5X parser |
| Parser Type = ROCKWELL, file is JSON | Service endpoint mismatch or built-in parser fails | Tags might not parse correctly |
| Parser Type = UNKNOWN (corrupted config) | ParserFactory.getParserByType() returns NULL | Fallback: demo structure created |
| Parser Type changed after file uploaded | Old parser type → wrong endpoint | Tags might parse incorrectly |

### File Upload Interaction with Parser Type

**File:** `FileUploadRoutes.java, Line 160`

```
User uploads file
    ↓
updateDeviceConfig(device, fileContent, filename)
    ├─ Saves file to disk
    ├─ Updates device's currentFilePath (via reflection)
    └─ Does NOT change parser type
    ↓
reloadDevice(device)
    ├─ Calls handleFileChange() method
    ├─ Re-parses with EXISTING parser type
    └─ Builds new address space
```

**IMPORTANT:** Parser type is NOT automatically detected from file. User must select correct parser type BEFORE uploading, or change it AFTER upload.

---

## COMPLETE SEQUENCE DIAGRAM

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      IGNITION GATEWAY STARTUP                               │
└─────────────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ SimulatorModuleHook.startup() [SimulatorModuleHook.java:43]                │
│  └─ Start ParserService (optional) on localhost:5000                       │
│     ParserService.start() [ParserService.java:47]                          │
│      ├─ Extract Python executable                                          │
│      ├─ Start subprocess                                                    │
│      ├─ Wait 3000ms                                                         │
│      └─ Verify with health check                                            │
│  └─ Register resource bundle                                                │
└─────────────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ EnhancedSimulatorExtensionPoint.createDevice() [Line 52]                   │
│  └─ Called for each device in Gateway Config                               │
└─────────────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ EnhancedSimulatorDevice.constructor() [EnhancedSimulatorDevice.java:67]    │
│  ├─ Store context and config                                               │
│  ├─ Create subscription model                                               │
│  └─ Register lifecycle callbacks (onStartup, onShutdown)                   │
└─────────────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ DEVICE LIFECYCLE: onStartup() [EnhancedSimulatorDevice.java:110]          │
│                                                                              │
│ [1] SimulatorModuleHook.registerDevice() [Line 117]                        │
│     └─ Add to deviceRegistry map                                            │
│                                                                              │
│ [2] prepareFile() [Line 120] ◄─── CRITICAL FIRST CHECK                    │
│     [File: EnhancedSimulatorDevice.java:272]                               │
│     ├─ Check config.parser().fileContent()                                 │
│     ├─ Check config.parser().fileName()                                     │
│     ├─ Create storage dir if needed                                         │
│     ├─ If fileContent provided: Write to disk                              │
│     ├─ If fileName provided: Verify exists                                 │
│     └─ Return file path or NULL                                             │
│     ⚠️  IF NULL: Status = "Ready - Waiting for file upload", RETURN EARLY  │
│                                                                              │
│ [3] parseFile(currentFilePath) [Line 132] ◄─── CRITICAL PARSE STEP        │
│     [File: EnhancedSimulatorDevice.java:346]                               │
│     └─ config.parser().parserType() ◄─── PARSER TYPE USED HERE             │
│        ├─ Case 1: Parser Service Available                                 │
│        │   tryParserService(filePath, parserKey) [Line 354]                │
│        │   ├─ Build URL with parserKey (rockwell→l5k, etc.)               │
│        │   ├─ HTTP GET to http://172.17.0.1:5000/parse/{endpoint}         │
│        │   └─ Return JsonObject or NULL                                     │
│        │                                                                     │
│        └─ Case 2: Service Unavailable or Error → Fallback                  │
│            parseFileBuiltIn(filePath, parserType) [Line 362]               │
│            ├─ ParserFactory.getParserByType(key)                           │
│            │   ├─ Map key to L5XParser, JsonPLCParser, or CsvParser        │
│            │   └─ Return parser or NULL                                     │
│            ├─ parser.parse(filePath)                                        │
│            │   └─ Parse XML/JSON/CSV → JsonObject                          │
│            └─ Return JsonObject or createDemoStructure()                    │
│     ⚠️  IF NULL: Status = "Error: Failed to parse file", RETURN            │
│                                                                              │
│ [4] createRootNode() [Line 141] ◄─── CREATE OPC-UA FOLDER                 │
│     [File: EnhancedSimulatorDevice.java:525]                               │
│     ├─ Create UaFolderNode with device name                                │
│     ├─ Add to node manager                                                  │
│     └─ Add reference to root "Devices" folder                              │
│     ⚠️  IF FAILS: Address space incomplete                                 │
│                                                                              │
│ [5] buildAddressSpace() [Line 144] ◄─── CREATE TAG NODES                  │
│     [File: AddressSpaceBuilder.java:69]                                    │
│     ├─ AddressSpaceBuilder.buildAddressSpace(parsedData, rootNode, ctx)   │
│     │                                                                        │
│     ├─ PROCESS GLOBAL TAGS                                                 │
│     │  ├─ Check parsedData.has("global_tags")                              │
│     │  ├─ Create "Controller:Global" folder                                │
│     │  └─ For each tag in global_tags:                                     │
│     │      └─ addTag(tag, folder, context, "Controller:Global")            │
│     │          ├─ Check tag.has("name") && tag.has("data_type")            │
│     │          ├─ Detect type: UDT / Array / Atomic                        │
│     │          │                                                             │
│     │          │  ┌─────────────────────────────────────────┐              │
│     │          │  │ FOR ATOMIC TAG:                         │              │
│     │          │  │ addAtomicTag()                          │              │
│     │          │  ├─ mapDataType(dataType) → OpcUaDataType │              │
│     │          │  ├─ getInitialValue(tag) → typed value    │              │
│     │          │  ├─ UaVariableNode.build(...)             │              │
│     │          │  │  ├─ NodeId                             │              │
│     │          │  │  ├─ BrowseName                         │              │
│     │          │  │  ├─ DisplayName                        │              │
│     │          │  │  ├─ DataType: OpcUaDataType            │              │
│     │          │  │  ├─ AccessLevel: READ_WRITE            │              │
│     │          │  │  └─ ...                                │              │
│     │          │  ├─ setValue(new DataValue(Variant(val))) │              │
│     │          │  ├─ nodeAdder.accept(variableNode)        │              │
│     │          │  │  └─ ADD TO NODE MANAGER ◄─ KEY STEP  │              │
│     │          │  └─ parentFolder.addOrganizes(varNode)    │              │
│     │          │  └─ Log debug message                     │              │
│     │          │  └─ RETURN                                │              │
│     │          │  └─ TAG VISIBLE IN OPC-UA ADDRESS SPACE  │              │
│     │          └─────────────────────────────────────────┘              │
│     │                                                                        │
│     ├─ PROCESS PROGRAMS                                                     │
│     │  └─ Similar to global tags structure                                 │
│     │                                                                        │
│     └─ VALIDATE                                                             │
│        └─ countTotalTags(plcData)                                           │
│           └─ If count = 0: Log WARNING                                      │
│                                                                              │
│ [6] initializeSimulation() [Line 147] (Optional - if enabled)              │
│     └─ OpcUaSimulationEngine.start()                                        │
│                                                                              │
│ [7] setupFileWatcher() [Line 152] (Optional - if hot reload enabled)       │
│     └─ FileWatcher monitors currentFilePath for changes                    │
│        └─ On change: handleFileChange() → Full reload                      │
│                                                                              │
│ [8] Status = "Running" [Line 156]                                          │
│     └─ Device ready, tags visible in OPC-UA browser                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ TAGS NOW VISIBLE IN OPC-UA ADDRESS SPACE                                   │
│                                                                              │
│ [DeviceName]/                                                               │
│   ├── Controller:Global/                                                    │
│   │   ├── Tag1 (variable node, readable/writable)                          │
│   │   ├── Tag2 (variable node)                                             │
│   │   └── UDTInstance/                                                      │
│   │       ├── Member1                                                       │
│   │       └── Member2                                                       │
│   └── Programs/                                                             │
│       └── MainProgram/                                                      │
│           └── Counter                                                       │
│                                                                              │
│ Each node is:                                                               │
│ - Addressable via OPC-UA node ID                                            │
│ - Readable (AccessLevel.READ)                                               │
│ - Writable (AccessLevel.WRITE)                                              │
│ - Has proper OPC-UA data type                                               │
│ - Has initial value from parser                                             │
│ - Subscribed to by simulation engine (if enabled)                           │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## TROUBLESHOOTING: WHERE THINGS BREAK

### 1. Device Never Starts (Status = "Initializing")

**Likely Cause:** Device not registered or disabled

```
Check: Config → OPC-UA → Device Connections
├─ Device exists?
├─ Device type = "Enhanced PLC Simulator"?
└─ Device enabled = true?

If not visible:
└─ Check Gateway logs for EnhancedSimulatorExtensionPoint errors
```

### 2. No File Upload Options

**Likely Cause:** File upload routes not mounted

```
Check: /main/data/plcsimulator/ endpoints
├─ Check Gateway logs for "ROUTE MOUNTING DEBUG"
├─ Look for "Failed to mount file upload routes"
└─ Verify:
   └─ SimulatorModuleHook.mountRouteHandlers() called
      └─ Check if context/routes null

Files involved:
├─ SimulatorModuleHook.java:120
└─ FileUploadRoutes.java:49
```

### 3. Status = "Ready - Waiting for file upload"

**Expected Status:** Means device correctly started but needs file

```
Action: Upload file via /main/data/plcsimulator/upload
├─ POST to /upload?device={deviceName}
├─ Header: X-Filename: {filename}
├─ Body: file content
└─ Response: Check for success:true

Monitor: /main/data/plcsimulator/device/{deviceName}/status
└─ Should change to "Running" after upload
```

### 4. Status = "Error: Failed to parse file"

**Root Cause:** Parser failed or returned null

```
Check logs for:
├─ "Parser service not available" → OK, will use fallback
├─ "No parser found for type" → WRONG PARSER TYPE
├─ "Parser returned null" → FILE CONTENT INVALID
│   └─ File format doesn't match parser type
│   └─ File corrupted or malformed
└─ "File does not exist" → FILE PATH WRONG

Debug steps:
1. Check device file path: EnhancedSimulatorDevice.currentFilePath
2. Verify file exists on disk
3. Verify file format matches parser type
4. Try uploading file again
5. Check parser logs at http://172.17.0.1:5000/health
```

### 5. Tags Created But Not Visible in OPC-UA

**Root Cause:** Node manager didn't add nodes

```
Check logs for:
├─ "Address space built successfully" → Nodes created
├─ "WARNING: Zero tags created" → Parser returned empty

Possible causes:
1. Parsed JSON missing "global_tags" or "programs"
   └─ Fix: Re-upload file or change parser type
   
2. Tags missing "name" or "data_type" fields
   └─ Fix: Verify file format valid

3. Root node not created
   └─ Fix: Check logs for "Created root node"

4. Node manager callback not working
   └─ Fix: Check Milo framework logs
```

### 6. Parser Type Mismatch

**Symptoms:** Wrong tags created or parse errors

```
Verify: EnhancedSimulatorConfig.parser().parserType()
├─ Must be: ROCKWELL, JSON, SIEMENS, SCHNEIDER, or BECKHOFF
├─ Case-sensitive enum
└─ getKey() returns lowercase string

If wrong:
1. Edit device config
2. Select correct parser type
3. Re-upload file
4. Save config
```

### 7. Parser Service Connection Fails

**Symptoms:** "Parser service unavailable" in logs

```
Check: Parser service running on localhost:5000
├─ Test: curl http://127.0.0.1:5000/health
├─ Docker container: curl http://172.17.0.1:5000/health
└─ Logs: Check ParserService startup in SimulatorModuleHook.startup()

If failed:
├─ Parser executable not bundled
├─ Port 5000 in use
├─ Python environment not set up
└─ Device will fall back to built-in parsers (L5X, JSON, CSV)
```

### 8. File Upload Not Applied to Device

**Symptoms:** File uploaded but device still uses old file

```
Check: FileUploadRoutes.handleFileUpload()
├─ updateDeviceConfig() [Line 342]
│   └─ Updates device's currentFilePath (via reflection)
├─ reloadDevice() [Line 387]
│   └─ Calls handleFileChange() to reload

If fails:
├─ Check device not null
├─ Check reflection fields match current code
└─ Monitor device status endpoint
```

---

## FILE LOCATIONS SUMMARY

| Component | File Path | Key Methods | Lines |
|-----------|-----------|------------|-------|
| Module Hook | SimulatorModuleHook.java | startup(), mountRouteHandlers() | 43, 120 |
| Device | EnhancedSimulatorDevice.java | onStartup(), parseFile(), buildAddressSpace() | 110, 346, 552 |
| Config | EnhancedSimulatorConfig.java | ParserType enum, ParserSettings record | 20, 128 |
| Extension Point | EnhancedSimulatorExtensionPoint.java | createDevice() | 52 |
| Address Space Builder | AddressSpaceBuilder.java | buildAddressSpace(), addTag(), addAtomicTag() | 69, 157, 239 |
| File Upload Routes | FileUploadRoutes.java | handleFileUpload(), updateDeviceConfig() | 101, 342 |
| Parser Service | ParserService.java | start(), isRunning() | 47, 120 |
| Parser Factory | ParserFactory.java | getParserByType(), getParser() | 56, 33 |
| L5X Parser | L5XParser.java | parse(), parseContent() | 38, 49 |

---

## CRITICAL DEPENDENCIES CHECKLIST

For tags to appear, VERIFY ALL:

- [ ] Device created in Gateway Config (Config → OPC-UA → Device Connections)
- [ ] Device enabled (checkbox checked)
- [ ] Device type = "Enhanced PLC Simulator"
- [ ] Parser Type selected and saved (ROCKWELL, JSON, SIEMENS, SCHNEIDER, or BECKHOFF)
- [ ] File uploaded OR file path provided in config
- [ ] File exists on disk at path in config
- [ ] File content is valid for the selected parser type
- [ ] No errors in device startup logs
- [ ] Device status = "Running" (not "Initializing", "Starting", or "Error: ...")
- [ ] Parsed JSON contains "global_tags" or "programs" array
- [ ] Each tag has "name" and "data_type" fields
- [ ] OPC-UA root node created (check logs for "Created root node")
- [ ] Address space builder executed (check logs for "Building address space")
- [ ] Total tags counted > 0 (not "WARNING: Zero tags created")

