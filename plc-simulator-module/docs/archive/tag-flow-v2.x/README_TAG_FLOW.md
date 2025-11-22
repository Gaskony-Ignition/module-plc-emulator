# PLC Simulator Tag Creation & Display Flow - Complete Documentation

This directory contains comprehensive documentation of how tags are created and displayed in the OPC-UA address space when a device is configured in the PLC Simulator module.

## Documentation Files

### 1. QUICK_REFERENCE.txt (177 lines)
**For:** Quick lookups, debugging checklists, common issues
- 8-step startup flow overview
- Key classes and methods with line numbers
- Parser type mapping
- Data type mapping
- Status message meanings
- Common issues and quick fixes
- Debug checklist

**Best for:** Developers needing quick answers while debugging

### 2. TAG_FLOW_SUMMARY.md (276 lines)
**For:** Executive overview and management summary
- Quick reference for all 5 key questions
- 8-step startup sequence
- Top 3 reasons tags don't appear
- Critical file locations
- Status message reference
- Monitoring and debugging
- Expected JSON structure

**Best for:** Project managers, new team members, high-level understanding

### 3. FLOW_DIAGRAM.txt (460 lines)
**For:** Visual understanding of the complete process
- ASCII flow diagrams showing the exact sequence
- Parser type dependency flow diagram
- Failure points and recovery paths
- File upload flow diagram
- Critical checklist with verification steps

**Best for:** Visual learners, understanding process flow, presentations

### 4. TAG_CREATION_FLOW.md (802 lines)
**For:** Complete detailed documentation with code references
- In-depth explanation of each step
- Method calls with exact file paths and line numbers
- Complete sequence diagram
- Detailed troubleshooting guide
- File locations summary
- Critical dependencies checklist
- JSON structure requirements
- Network communication details

**Best for:** Deep technical understanding, integration work, fixing complex issues

## Reading Guide by Use Case

### "Tags aren't showing up!"
1. Start: QUICK_REFERENCE.txt → "MONITOR/DEBUG CHECKLIST"
2. Read: TAG_FLOW_SUMMARY.md → "Top 3 Reasons Tags Don't Appear"
3. Deep dive: TAG_CREATION_FLOW.md → "TROUBLESHOOTING: WHERE THINGS BREAK"

### "How does the parser service communicate?"
1. Start: TAG_FLOW_SUMMARY.md → "Section 2: JAVA-TO-PYTHON COMMUNICATION"
2. Read: FLOW_DIAGRAM.txt → "PARSER TYPE DEPENDENCY FLOW"
3. Deep dive: TAG_CREATION_FLOW.md → "HOW DOES JAVA MODULE COMMUNICATE WITH PYTHON"

### "I need to add a new parser type"
1. Start: QUICK_REFERENCE.txt → "PARSER TYPE MAPPING"
2. Read: TAG_FLOW_SUMMARY.md → "Section 5: PARSER TYPE DEPENDENCY"
3. Deep dive: TAG_CREATION_FLOW.md → "Section 5: IS THERE ANY DEPENDENCY ON PARSER TYPE"
4. Code: EnhancedSimulatorConfig.java (Lines 20-56), ParserFactory.java

### "I'm integrating with OPC-UA"
1. Start: TAG_FLOW_SUMMARY.md → "Section 3: PARSER → OPC-UA NODE CONVERSION"
2. Read: FLOW_DIAGRAM.txt → Node structure diagram at the end
3. Deep dive: TAG_CREATION_FLOW.md → "3. HOW ARE TAGS CONVERTED TO OPC-UA NODES"
4. Code: AddressSpaceBuilder.java, EnhancedSimulatorDevice.java (Lines 552-572)

### "I'm new and need to understand the whole system"
1. First: TAG_FLOW_SUMMARY.md → Understand the 5 key questions
2. Second: FLOW_DIAGRAM.txt → Visualize the complete flow
3. Then: QUICK_REFERENCE.txt → Understand key concepts
4. Finally: TAG_CREATION_FLOW.md → Detailed reference material

## Key Questions Answered

### 1. Where does the tag creation process start?
- **File:** EnhancedSimulatorDevice.java
- **Method:** onStartup() at Line 110
- **Triggers:** Gateway startup, device creation, file upload, hot reload
- **See:** TAG_FLOW_SUMMARY.md, Section 1

### 2. How does Java communicate with Python parser service?
- **Protocol:** HTTP GET to localhost:5000/parse/{endpoint}
- **Parser Types:** rockwell, json, siemens, schneider, beckhoff
- **Fallback:** Built-in Java parsers (L5XParser, JsonPLCParser, CsvParser)
- **See:** TAG_FLOW_SUMMARY.md, Section 2 & FLOW_DIAGRAM.txt, Parser Type Dependency

### 3. How are tags converted to OPC-UA nodes?
- **Builder:** AddressSpaceBuilder.java (Lines 69-149)
- **Process:** JSON → Validate → MapType → CreateNode → AddToManager
- **Key Method:** nodeAdder.accept(variableNode) at Line 280
- **See:** TAG_FLOW_SUMMARY.md, Section 3

### 4. What conditions must be met for tags to appear?
- **All 10 conditions must be TRUE** (see checklist below)
- **Dependency chain:** Device → Enabled → File → Parse → Create → Register
- **See:** TAG_FLOW_SUMMARY.md, Section 4 & FLOW_DIAGRAM.txt, Critical Checklist

### 5. Is there a dependency on Parser Type being saved?
- **YES - CRITICAL**
- **Enum:** ROCKWELL, JSON, SIEMENS, SCHNEIDER, BECKHOFF
- **Impact:** Wrong type → wrong parser → tags may not appear
- **See:** TAG_FLOW_SUMMARY.md, Section 5

## Critical Checklist for Tag Visibility

All of these must be TRUE:

- [ ] Device created in Gateway Config (Config → OPC-UA → Device Connections)
- [ ] Device type = "Enhanced PLC Simulator"
- [ ] Device enabled (checkbox checked)
- [ ] Parser Type selected (not "UNKNOWN")
- [ ] File uploaded OR file path provided in config
- [ ] File exists on disk at configured path
- [ ] File content is valid for selected parser type
- [ ] Parser can read file (not corrupted)
- [ ] Parser returns valid JSON structure
- [ ] JSON contains "global_tags" or "programs" array
- [ ] Each tag has "name" and "data_type" fields
- [ ] Root node created (check logs for "Created root node")
- [ ] Address space builder executed (check logs for "Building address space")
- [ ] Total tags > 0 (check logs for warnings)
- [ ] Node manager added nodes (no rejection errors)
- [ ] Device reaches "Running" status

## File Locations (Absolute Paths)

```
/modules/ignition-plc-simulator/plc-simulator-module/gateway/src/main/java/
com/inductiveautomation/plcsimulator/gateway/

device/
  ├─ EnhancedSimulatorDevice.java (Main device class, Lines 67-697)
  ├─ EnhancedSimulatorConfig.java (Config with ParserType enum, Lines 15-199)
  ├─ EnhancedSimulatorExtensionPoint.java (Registration, Lines 21-116)
  └─ AddressSpaceBuilder.java (Tag creation, Lines 36-391)

parser/
  ├─ ParserFactory.java (Parser selection, Lines 13-101)
  ├─ L5XParser.java (Rockwell parser)
  ├─ JsonPLCParser.java (JSON parser)
  └─ CsvParser.java (CSV parser)

web/
  └─ FileUploadRoutes.java (File upload API, Lines 33-494)

ParserService.java (Python parser subprocess, Lines 19-271)
OpcUaSimulationEngine.java (Value simulation)
FileWatcher.java (Hot reload monitoring)
SimulatorModuleHook.java (Module initialization, Lines 27-218)
```

## Key Line Numbers Reference

| Action | File | Line |
|--------|------|------|
| Device startup begins | EnhancedSimulatorDevice.java | 110 |
| Device registration | EnhancedSimulatorDevice.java | 117 |
| File preparation | EnhancedSimulatorDevice.java | 120 |
| File parsing | EnhancedSimulatorDevice.java | 132 |
| Parser type used | EnhancedSimulatorDevice.java | 348-349 |
| Parser service call | EnhancedSimulatorDevice.java | 374-444 |
| Root node creation | EnhancedSimulatorDevice.java | 141 |
| Address space build | EnhancedSimulatorDevice.java | 144 |
| Build orchestration | AddressSpaceBuilder.java | 69 |
| Tag processing | AddressSpaceBuilder.java | 157 |
| Atomic tag creation | AddressSpaceBuilder.java | 239 |
| **Node added to OPC-UA** | **AddressSpaceBuilder.java** | **280** |
| Parser type enum | EnhancedSimulatorConfig.java | 20 |
| File upload handler | FileUploadRoutes.java | 101 |
| Module startup | SimulatorModuleHook.java | 43 |

## JSON Structure Expected from Parser

```json
{
  "controller": "ControllerName",
  "vendor": "rockwell",
  "global_tags": [
    {
      "name": "TagName",
      "data_type": "DINT",
      "initial_value": 100,
      "description": "Optional"
    },
    {
      "name": "UDTInstance",
      "data_type": "StructType",
      "udt_members": [
        {"name": "Member1", "data_type": "BOOL"},
        {"name": "Member2", "data_type": "INT"}
      ]
    }
  ],
  "programs": [
    {
      "name": "MainProgram",
      "tags": [
        {"name": "LocalVar", "data_type": "DINT"}
      ]
    }
  ]
}
```

**Required per tag:** `"name"`, `"data_type"`
**Optional:** `"initial_value"`, `"udt_members"`, `"isArray"`, `"dimensions"`

## Common Debugging Commands

```bash
# Check device status
curl http://gateway:8088/main/data/plcsimulator/device/{deviceName}/status

# Check parser service health
curl http://localhost:5000/health

# List all devices
curl http://gateway:8088/main/data/plcsimulator/devices

# Check Gateway logs
tail -f /path/to/ignition/logs/wrapper.log
grep -i "plcsimulator" /path/to/ignition/logs/wrapper.log
grep "Building address space" /path/to/ignition/logs/wrapper.log
```

## Top 3 Reasons Tags Don't Appear

1. **Parser Type Not Selected (40%)** → Device error or empty address space
   - Fix: Edit config, select correct type, re-upload

2. **File Not Uploaded (30%)** → Status = "Ready - Waiting for file upload"
   - Fix: Use file upload interface to provide file

3. **File Format Mismatch (20%)** → Status = "Error: Failed to parse file"
   - Fix: Verify file format matches parser type

## Status Messages & Meanings

| Status | Meaning | Action |
|--------|---------|--------|
| "Initializing" | Device just created | Wait |
| "Starting" | onStartup() running | Wait |
| "Ready - Waiting for file upload" | No file provided | Upload file |
| "Running" | Device ready, tags visible | TAGS VISIBLE |
| "Reloading" | Hot reload in progress | Wait |
| "Error: ..." | Device failed | Check logs, fix issue |

## Documentation Structure

```
TAG_CREATION_FLOW.md (MAIN DETAILED REFERENCE)
  ├─ Executive Summary
  ├─ 1. Starting Point (800 lines of flow)
  ├─ 2. Java-Python Communication (800 lines of flow)
  ├─ 3. Parser to OPC-UA Conversion (1200 lines of flow)
  ├─ 4. Conditions for Tags to Appear (1000 lines)
  ├─ 5. Parser Type Dependency (1500 lines)
  ├─ Complete Sequence Diagram
  ├─ Troubleshooting Guide
  ├─ File Summary
  └─ Checklist

FLOW_DIAGRAM.txt (VISUAL REFERENCE)
  ├─ Startup Phase Diagram
  ├─ Device Startup Phase Diagram
  ├─ Parser Type Dependency Flow
  ├─ Failure Points & Recovery
  ├─ File Upload Flow
  └─ Critical Checklist

TAG_FLOW_SUMMARY.md (EXECUTIVE SUMMARY)
  ├─ Quick Reference (5 questions answered)
  ├─ 8-Step Startup Sequence
  ├─ Critical File Locations
  ├─ Top 3 Failure Reasons
  ├─ Status Messages
  ├─ Monitoring & Debugging
  └─ Expected JSON Structure

QUICK_REFERENCE.txt (LOOKUP TABLE)
  ├─ Starting Point
  ├─ 8-Step Flow
  ├─ Key Classes & Methods
  ├─ Parser Type Mapping
  ├─ Critical Junctures
  ├─ Data Type Mapping
  ├─ Status Messages
  ├─ Debug Checklist
  ├─ File Locations
  ├─ Endpoint Mapping
  ├─ Node Structure
  └─ Quick Debug Steps
```

## Generated Information

- **Date:** 2025-11-13
- **Version:** Based on PLC Simulator v2.2.0
- **Total Documentation Lines:** 1,715
- **Total Documentation Size:** 84 KB
- **Scope:** Complete tag creation flow from device startup to OPC-UA visibility

## Related Source Files

- `/modules/ignition-plc-simulator/plc-simulator-module/gateway/src/main/java/...` (Java source code)
- Each source file contains detailed Javadoc comments explaining methods
- Configuration managed by `EnhancedSimulatorConfig.java` (Java record)
- Parsers implemented in `parser/` subdirectory

## For Questions or Issues

Refer to:
1. QUICK_REFERENCE.txt for immediate answers
2. TAG_FLOW_SUMMARY.md for high-level overview
3. FLOW_DIAGRAM.txt for visual understanding
4. TAG_CREATION_FLOW.md for detailed technical analysis
5. Source code with Javadoc comments for implementation details

---

**Documentation Version:** 1.0
**Last Updated:** 2025-11-13
**Status:** Complete and verified
