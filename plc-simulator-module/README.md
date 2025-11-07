# Enhanced PLC Simulator - Ignition Module

A device driver module for Inductive Automation's Ignition platform that simulates PLC operations by parsing vendor-specific export files and creating OPC-UA accessible tag structures.

## Current Status

**Version**: v1.3.0
**Status**: ✅ Fully Functional - Enhanced workflow similar to original simulator!

### What Works ✅
- ✅ **File upload feature** - "📁 Upload PLC File" button automatically appears in device config
- ✅ Device driver appears in device connection dropdown as "Enhanced PLC Simulator"
- ✅ Parser dropdown shows friendly vendor names (e.g., "Rockwell L5K (Allen-Bradley)")
- ✅ Module installs and runs in Ignition Gateway
- ✅ Device driver architecture functional
- ✅ i18n display names working correctly
- ✅ Parser type enum dropdowns functional
- ✅ Web resources properly mounted
- ✅ HTTP routes for file operations

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

- **[BUILD.md](BUILD.md)** - Building and packaging the module
- **[SIGNING.md](SIGNING.md)** - Module signing configuration
- **[TESTING.md](TESTING.md)** - Testing checklist for validation
- **[KNOWN_ISSUES.md](KNOWN_ISSUES.md)** - Current limitations and known problems
- **[CHANGELOG.md](CHANGELOG.md)** - Version history and changes
- **[DEVELOPMENT.md](DEVELOPMENT.md)** - Development workflow and architecture

## Version History

See [CHANGELOG.md](CHANGELOG.md) for detailed version history.

**Recent versions:**
- **v1.1.0** (Current) - Attempted file upload feature (not functional)
- **v1.0.10** - Fixed enum toString() for dropdown display
- **v1.0.9** - Fixed i18n bundle registration
- **v1.0.5-v1.0.8** - Various i18n and display name fixes
- **v1.0.1** - Updated vendor name to Gaskony

## Known Issues

**File Upload Not Working** (v1.1.0):
- Attempted implementation using web resources not compatible with AbstractDeviceModuleHook
- Workaround: Users must copy/paste file content into textarea
- See KNOWN_ISSUES.md for details and future plans

## Architecture

The module is built using Ignition's device driver architecture:

- **AbstractDeviceModuleHook** - Module lifecycle and device registration
- **DeviceType** - Device driver definition with configuration schema
- **AbstractEvaluationDriver** - Core device driver implementation
- **i18n Resource Bundles** - Internationalized display names

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
