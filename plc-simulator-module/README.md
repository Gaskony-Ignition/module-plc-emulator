# Enhanced PLC Simulator - Ignition Module

A device driver module for Inductive Automation's Ignition platform that simulates PLC operations by parsing vendor-specific export files and creating OPC-UA accessible tag structures.

## Current Status

**Version**: v6.5.0 🌍
**Status**: ✅ Production Ready - Extended Multi-Vendor Support

### Supported PLC Formats

**Currently Supported (6 of Top 6 Global Vendors - ~85% Market Coverage):**

- ✅ **Rockwell Automation** (~25% market share) - L5K/L5X files (Allen-Bradley Studio 5000)
  - **COMPLETE predefined type coverage** (22 types)
  - Full UDT (User Defined Type) expansion
  - AOI (Add-On Instruction) support
  - Hierarchical tag structure matching real PLCs
  - Process Control: PID, PIDE, ALARM_ANALOG, ALARM_DIGITAL
  - Motion Control: AXIS_CIP_DRIVE, AXIS_VIRTUAL, MOTION_GROUP, CAM
  - Specialty: PHASE, EQUIPMENT_SEQUENCE, COORDINATE_SYSTEM

- ✅ **Siemens** (~30% market share) - TIA Portal XML exports (S7-1200/1500/1500T)
  - Data Blocks (Global DB)
  - Tag Tables (PLC Tags)
  - Full project exports
  - 15+ data type conversions (Bool, Byte, Word, DWord, Int, DInt, Real, String, etc.)
  - Array dimension parsing
  - Multi-language comment support

- ✅ **Schneider Electric** (~10% market share) - Unity Pro/EcoStruxure (M340, M580, Quantum)
  - XML and CSV format support
  - Derived Data Types (DDT/STRUCT)
  - Located variables (%M, %I, %Q, %MW, etc.)
  - Unity Pro project exports
  - EcoStruxure Control Expert exports

- ✅ **Beckhoff** (~3-4% market share) - TwinCAT 2/3 XML exports
  - TwinCAT 3 XTI format (.xti, .xml)
  - TwinCAT 2 TPY/TSM format (.tpy, .tsm)
  - Global Variable Lists (GVL)
  - Program Organization Units (POU)
  - Data Unit Types (DUT/STRUCT)
  - IEC 61131-3 data type support
  - Structured Text declaration parsing
  - PERSISTENT/RETAIN keyword handling

- ✅ **Mitsubishi Electric** (~8% market share) - GX Works 2/3 CSV exports
  - iQ-Platform PLCs (Q series, L series, F series)
  - CSV device label lists
  - Device type inference (M, X, Y, D, T, C registers)
  - Hex value support (H prefix)
  - Data type conversion (BIT, WORD, DWORD, REAL, etc.)

- ✅ **ABB** (~5% market share) - Automation Builder / Control Builder Plus
  - AC800M controller exports (.apj, .xml)
  - IEC 61131-3 compliant variable declarations
  - Global and program variables
  - User-defined data types (structs)
  - Child element and attribute parsing

- ✅ **Generic Formats**
  - JSON format (custom tag definitions)
  - CSV format (variable lists)

**Future Vendor Support:**
- 🔮 **Omron** - CX-Programmer / Sysmac Studio

### What's New in v6.5.0 🌍 (Latest - Extended Vendor Coverage)
- ✅ **ADDITIONAL VENDOR SUPPORT** - Added 2 more major vendors
  - ✅ Mitsubishi Electric parser - GX Works 2/3 CSV exports with device type inference
  - ✅ ABB parser - Automation Builder/Control Builder Plus with IEC 61131-3 support
  - ✅ 85% global market coverage across 6 major vendors
- ✅ **COMPREHENSIVE TESTING** - 64 parser tests (100% passing)
  - 11 Mitsubishi parser tests (GX Works CSV formats)
  - 10 ABB parser tests (Automation Builder XML formats)
  - All previous vendor tests still passing
- ✅ **ENHANCED CSV PARSING** - Single-column CSV support
- ✅ **IMPROVED TYPE INFERENCE** - Mitsubishi device code auto-detection

### What's New in v6.0.0 🚀 (Multi-Vendor Support)
- ✅ **MULTI-VENDOR PARSER SUPPORT** - Major market expansion
  - ✅ Siemens TIA Portal parser - S7-1200/1500 support with data blocks and tag tables
  - ✅ Schneider Electric parser - Unity Pro/EcoStruxure with XML and CSV support
  - ✅ Beckhoff TwinCAT parser - TwinCAT 2/3 with GVL, POU, and DUT support
  - ✅ Parser auto-detection based on file extension and content analysis
  - ✅ 75% global market coverage across 4 major vendors
- ✅ **COMPREHENSIVE TESTING** - 43 parser tests (100% passing)
  - 12 Beckhoff parser tests (TwinCAT 2/3 formats)
  - 12 Rockwell L5X parser tests (XXE security included)
  - 11 Schneider parser tests (XML and CSV formats)
  - 8 Siemens parser tests (TIA Portal formats)
- ✅ **ROBUST ERROR HANDLING** - All parsers include XXE protection and malformed input handling
- ✅ **PRODUCTION READY** - Fully tested and documented for all supported vendors

### What's New in v5.4.9 🔒 (Security & Quality Update)
- ✅ **CRITICAL SECURITY FIXES** - All vulnerabilities resolved
  - ✅ XXE (XML External Entity) attack prevention - L5XParser hardened with 6 security features
  - ✅ Authentication bypass fixed - Proper SecurityContext validation (no more 1-second fallback!)
  - ✅ Path traversal protection - Filename and device name sanitization with canonical path validation
  - ✅ Hardcoded credentials removed - Environment variable support for module signing
  - ✅ File size DoS prevention - Content-Length validation and streaming read enforcement
- ✅ **COMPREHENSIVE TEST COVERAGE** - 40 tests (100% passing)
  - 12 L5XParser tests including XXE prevention
  - 10 FileValidator tests for file validation
  - 18 FileUploadRoutesSecurityTest for security validation
- ✅ **CI/CD AUTOMATION** - GitHub Actions pipeline with automated testing and security scanning
- ✅ **DEPENDENCIES UPDATED** - Gson 2.11.0, Modl plugin 0.5.0
- ✅ **DOCUMENTATION ENHANCED** - ARCHITECTURE.md, SECURITY.md, comprehensive testing docs

### What's New in v3.0.0 (Historic - Comprehensive Type Support)
- ✅ **COMPREHENSIVE Type Support** - ALL 22 Rockwell predefined types expand correctly!
  - Process Control: PID (14 members), PIDE (30 members), ALARM_ANALOG (26 members), ALARM_DIGITAL (18 members)
  - Motion: AXIS_CIP_DRIVE (24 key members), AXIS_VIRTUAL, AXIS_SERVO_DRIVE, MOTION_GROUP, CAM, CAM_PROFILE
  - Specialty: COORDINATE_SYSTEM, PHASE, EQUIPMENT_SEQUENCE, FBD_TIMER, FBD_COUNTER
- ✅ **Enhanced MESSAGE** - Expanded from 7 to 11 members
- ✅ **Industrial Ready** - Full support for process control applications
- ✅ **Motion Capable** - Complete motion axis and coordination support
- ✅ **Future Proof** - Can handle ANY Rockwell L5K file

### What's New in v2.4.x
- ✅ **Automatic File Upload & Apply** - Upload → Device updated → Tags created automatically
- ✅ **Pure Java Parsers** - L5X, JSON, CSV parsing (no Python dependency)
- ✅ **Clickable Program Manager URL** - Full gateway URL with copy button in device config
- ✅ **Simulation Engine** - 5 dynamic patterns (STATIC, RAMP, SINE, RANDOM, TOGGLE)
- ✅ **Hot Reload** - Automatic device update when PLC file changes
- ✅ **File Versioning** - Last 5 versions kept with rollback support
- ✅ **Gateway Dashboard** - Professional device management UI
- ✅ **Gateway Sidebar Menu** - "PLC Simulator" tab in Gateway Config
- ✅ **Proper PLC Structure** - Controller:Global hierarchy matches real PLCs
- ✅ **UDT Expansion** - UDT instances appear as folders with member variables

## Quick Start

### Installation

1. Download the latest `.modl` file from releases
2. Navigate to Gateway Config > System > Modules
3. Click "Install or Upgrade a Module"
4. Select the module file and install
5. Restart Gateway when prompted

### Creating a Simulated Device

#### Quick Create (No File Required)
1. Go to Config > Devices > Create New Device
2. Select device type: **Enhanced PLC Simulator**
3. Enter **Device Name** and select **Parser Type**
4. Click **Save** - device will show "Ready - Waiting for file upload" status
5. Add files later using one of these methods:
   - **Edit Program Page**: `http://localhost:9088/res/plcsimulator/edit-program.html`
   - **Device Edit**: Edit device and click "📁 Upload PLC File" button

#### Create with File
1. Go to Config > Devices > Create New Device
2. Select device type: **Enhanced PLC Simulator**
3. Configure device settings:
   - **Device Name**: Unique name for this simulator
   - **Parser Type**: Select your PLC vendor (Rockwell, Siemens, JSON, etc.)
   - **File Content** (Optional):
     - Click the **"📁 Upload PLC File"** button (appears automatically)
     - Or paste your PLC export file content directly into the textarea
   - **File Name** (Optional): Automatically populated when uploading, or enter manually
4. Save the device
5. Tags are automatically created and browseable via OPC-UA

### Edit Program (Similar to Original Simulator)

Access the dedicated Edit Program interface: **`http://localhost:9088/res/plcsimulator/edit-program.html`**

Features:
- 📁 Drag-and-drop file import
- 📋 Step-by-step instructions
- 🔗 Quick links to device configuration
- 💡 Workflow guidance

See [EDIT_PROGRAM_GUIDE.md](EDIT_PROGRAM_GUIDE.md) for detailed instructions.

**Note**:
- File content and filename are **completely optional** when creating the device
- You can create the device first and add files later
- Multiple methods available for file import (choose what works best for you)

## Supported PLC Formats

- **Rockwell L5K (Allen-Bradley)** - Logix 5000 exports
- **JSON Format** - Generic JSON tag definitions
- **Siemens TIA Portal** - XML/CSV exports (planned)
- **Schneider Electric** - Unity Pro/EcoStruxure (planned)
- **Beckhoff TwinCAT** - XML project exports (planned)

## Features

- **Multi-Vendor Support**: Extensible parser system for different PLC brands
- **Device Driver Architecture**: Appears as standard device connection in Ignition
- **Internationalization**: Proper display names in all UI dropdowns
- **OPC-UA Integration**: Tags automatically available via Ignition's OPC-UA server
- **Hierarchical Structure**: Preserves PLC program organization (Controller/Program scopes)

## Documentation

- **[QUICK_START.md](QUICK_START.md)** - Quick start guide for users
- **[ARCHITECTURE.md](ARCHITECTURE.md)** - Technical architecture and design (NEW in v5.4.9)
- **[SECURITY.md](SECURITY.md)** - Security best practices and credential management (NEW in v5.4.9)
- **[BUILD.md](BUILD.md)** - Building and packaging the module
- **[SIGNING.md](SIGNING.md)** - Module signing configuration
- **[TESTING.md](TESTING.md)** - Testing procedures and validation
- **[KNOWN_ISSUES.md](KNOWN_ISSUES.md)** - Current limitations and known problems
- **[CHANGELOG.md](CHANGELOG.md)** - Complete version history and changes
- **[DEVELOPMENT.md](DEVELOPMENT.md)** - Development workflow and guidelines

## Version History

See [CHANGELOG.md](CHANGELOG.md) for detailed version history.

**Recent versions:**
- **v2.0.5** (Current) - Full URL display with copy button in device config
- **v2.0.4** - Clickable Program Manager URL field
- **v2.0.0** - Major production release with Java parsers and automatic file upload
- **v1.5.1** - Route mounting and UI improvements
- **v1.3.0** - Edit Program page workflow

**Migration Note:** If upgrading from v1.x, see notes in CHANGELOG.md about v2.0.0 breaking changes (module architecture completely refactored).

## Architecture

The module is built using Ignition's device driver architecture:

- **AbstractGatewayModuleHook** - Module lifecycle and manual device registration (v2.0.0+)
- **DeviceExtensionPoint** - Device driver extension point for dropdown registration
- **ManagedAddressSpaceWithLifecycle** - Core device driver implementation
- **Java Parsers** - L5XParser, JsonPLCParser, CsvParser with ParserFactory
- **OPC-UA Integration** - Hierarchical address space with Controller:Global structure
- **Web Routes** - REST API for file upload and device management
- **Gateway UI** - Dashboard and sidebar menu integration

See [DEVELOPMENT.md](DEVELOPMENT.md) for detailed architecture information.

## Building from Source

See [BUILD.md](BUILD.md) for complete build instructions.

Quick build:
```bash
./gradlew clean build
```

Output: `build/EnhancedPLCSimulator-{version}.modl`

## Contributing

This project follows standard Ignition Module SDK conventions. See DEVELOPMENT.md for:
- Project structure
- Development workflow
- Adding new parser types
- Testing procedures

## License

This module uses Ignition Module SDK and is intended for use with Inductive Automation's Ignition platform.

**License**: Free module (`isFreeModule = true`)

## Support

- Report issues via project repository
- See documentation in `/docs` folder
- Check KNOWN_ISSUES.md for common problems

## Acknowledgments

- Built using [Ignition Module SDK](https://github.com/inductiveautomation/ignition-sdk-examples)
- Inspired by the built-in Programmable Device Simulator
- References the [ignition-module-python3-java](https://github.com/nigelgwork/ignition-module-python3-java) project for module structure
