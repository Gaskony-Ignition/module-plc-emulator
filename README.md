# Logix PLC Emulator - Ignition Module

A device driver module for Inductive Automation's Ignition platform that emulates Rockwell Logix PLC tag structures. Parse L5K/L5X export files from Studio 5000 and create OPC-UA accessible tag structures for development and testing without a physical PLC.

## Current Status

**Version**: v9.1.1
**Status**: Production Ready
**Requires**: Ignition 8.3+ | Java 17

## Supported Formats

- **Rockwell L5K** (Allen-Bradley Studio 5000) - Full UDT expansion, AOI support, 22 predefined types
- **Rockwell L5X** (XML exports) - Same capabilities as L5K in XML format
- **JSON** - Generic JSON tag definitions
- **CSV** - Variable lists

### Rockwell Logix Features

- Complete predefined type coverage (22 types including TIMER, COUNTER, PID, PIDE)
- Full UDT (User Defined Type) expansion with nested members
- AOI (Add-On Instruction) support
- Hierarchical tag structure matching real CompactLogix/ControlLogix PLCs
- Process Control types: PID, PIDE, ALARM_ANALOG, ALARM_DIGITAL
- Motion Control types: AXIS_CIP_DRIVE, AXIS_VIRTUAL, MOTION_GROUP, CAM

## Quick Start

### Installation

1. Download `LogixPLCEmulator-{version}.modl` from releases
2. Navigate to Gateway Config > System > Modules
3. Click "Install or Upgrade a Module"
4. Select the module file and install

### Creating an Emulated Device

#### Quick Create (No File Required)
1. Go to Config > OPC UA > Device Connections > Create New Device
2. Select device type: **Logix PLC Emulator**
3. Enter **Device Name** and select **Parser Type**
4. Click **Save** - device will show "Ready - Waiting for file upload" status
5. Add files later via the Connection Browser or Edit Program page

#### Create with File
1. Go to Config > OPC UA > Device Connections > Create New Device
2. Select device type: **Logix PLC Emulator**
3. Configure:
   - **Device Name**: Unique name for this emulator
   - **Parser Type**: Rockwell L5K or JSON
   - **File Content** (Optional): Click "Upload PLC File" or paste content
4. Save - tags are automatically created and browseable via OPC-UA

### Web UI Pages

- **Connection Browser**: `/data/logixemulator/connection-browser` (requires authentication) - Browse tags and manage PLC files
- **Edit Program**: `/data/logixemulator/edit-program` (requires authentication)

## Features

- **Device Driver Architecture**: Appears as standard device connection in Ignition
- **OPC-UA Integration**: Tags automatically available via Ignition's OPC-UA server
- **Hierarchical Structure**: Preserves PLC program organization (Controller/Program scopes)
- **Simulation Engine**: 5 patterns (STATIC, RAMP, SINE, RANDOM, TOGGLE) with per-tag control
- **Bulk Simulation**: Enable/disable simulation by scope or for all tags
- **SINT Support**: Simulates Short values in -128 to 127 range
- **Hot Reload**: Automatic tag update when PLC file changes on disk
- **Incremental Updates**: Smart detection of structural vs value-only changes
- **Connection Browser UI**: Unified tag exploration, live values, write support, and file upload
- **File Versioning**: Last 5 versions kept with rollback support
- **Security**: Path traversal prevention, rate limiting, authentication on all routes

## API Endpoints

All routes require authentication (Gateway login).

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/data/logixemulator/upload` | Upload PLC file to device |
| GET | `/data/logixemulator/devices` | List all emulated devices |
| GET | `/data/logixemulator/device/:name/status` | Device status and file info |
| GET | `/data/logixemulator/device/:name/tags` | Get tag tree (paginated) |
| GET | `/data/logixemulator/device/:name/tags/live` | Live tag values |
| POST | `/data/logixemulator/device/:name/tag/write` | Write tag value |
| POST | `/data/logixemulator/device/:name/tag/simulate` | Toggle per-tag simulation |
| POST | `/data/logixemulator/device/:name/simulation/scope` | Bulk enable/disable by scope |
| POST | `/data/logixemulator/device/:name/simulation/all` | Enable/disable all simulation |
| DELETE | `/data/logixemulator/device/:name/delete` | Delete device file |
| GET | `/data/logixemulator/health` | Health check (public) |

## Documentation

- **[QUICK_START.md](docs/QUICK_START.md)** - Quick start guide
- **[BUILD.md](docs/BUILD.md)** - Building and packaging
- **[SIGNING.md](docs/SIGNING.md)** - Module signing configuration
- **[TESTING.md](docs/TESTING.md)** - Testing procedures
- **[DEVELOPMENT.md](docs/DEVELOPMENT.md)** - Development guide
- **[CHANGELOG.md](CHANGELOG.md)** - Version history
- **[SECURITY.md](docs/SECURITY.md)** - Security documentation

## Building

```bash
cd logix-emulator-module
chmod +x gradlew
./gradlew clean build
```

The signed module will be at `build/LogixPLCEmulator-{version}.modl`.

## License

Free / Open Source - Gaskony
