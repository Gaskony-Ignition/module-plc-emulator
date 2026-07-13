# Logix PLC Emulator - Ignition Module

## Why this exists

Ignition projects are built against the tag structures of real PLCs — but the
real PLC is usually unavailable: it's running a plant, it's on a customer site,
or it doesn't exist yet. Generic simulators don't help, because the hard part
isn't producing changing values; it's matching the **exact** tag tree the
project will bind to — UDTs, AOIs, arrays and all.

**This module turns the PLC's own program export into the test PLC.** Feed it
the L5K/L5X file from Studio 5000 and it emulates that controller's complete
tag structure through Ignition's OPC-UA server, with configurable simulated
values and hot-reload — develop, demonstrate, and acceptance-test with zero
PLC hardware and zero risk to a running plant.

The full purpose, definition of done, and permanent won't-do list live in
[docs/PROJECT_CHARTER.md](docs/PROJECT_CHARTER.md) — the charter drives every
release decision.

## Current Status

**Version**: v10.1.0
**Status**: Production Ready
**Requires**: Ignition 8.3+ | Java 17

## Tag Addressing

The emulator's OPC-UA NodeIds are **swap-compatible with Ignition's native
Allen-Bradley Logix driver** — a tag binding developed against the emulator
works unchanged when the device is swapped for the real PLC (charter §2.2).
Controller-scoped tags are addressed by the bare tag name (`SystemClock`),
program-scoped tags use `Program:<ProgramName>.<Tag>`, arrays fully expand to
their elements, and BOOL arrays are DWORD-packed (`Tag[word].bit`) exactly as
the real driver requires. The full, DOC-CONFIRMED/INFERRED-tagged grammar for
every construct (UDT/AOI members, multi-dimensional arrays, predefined types,
module I/O tags, External Access) is normative in
[docs/plans/ADDRESSING.md](docs/plans/ADDRESSING.md); §4 there covers the v10
migration for bindings created against a pre-v10 emulator device.

## Supported Formats

- **Rockwell L5X** (Allen-Bradley Studio 5000 XML export) - **Primary format.**
  Full UDT/AOI expansion, driver-matching NodeIds, module I/O tags, corpus- and
  fidelity-tested.
- **Rockwell L5K** - Best-effort. Parsed where possible, but fails loudly with
  a clear error on export text it cannot parse rather than silently
  substituting a demo tag structure.
- **JSON** - Generic JSON tag definitions
- **CSV** - Variable lists

### Rockwell Logix Features

- Driver-matching NodeIds for every construct (see [Tag Addressing](#tag-addressing) above)
- Complete predefined type coverage (22 types including TIMER, COUNTER, PID, PIDE), corrected against Rockwell reference manuals and real exports
- Full UDT (User Defined Type) expansion with nested members, including array-of-UDT and array members inside a UDT
- AOI (Add-On Instruction) support, including `EnableIn`/`EnableOut`
- Hierarchical browse structure matching real CompactLogix/ControlLogix PLCs
- Process Control types: PID, PIDE, ALARM_ANALOG, ALARM_DIGITAL
- Motion Control types: AXIS_CIP_DRIVE, AXIS_VIRTUAL, MOTION_GROUP, CAM (large motion/coordinate member sets are an [incomplete, documented limitation](docs/KNOWN_ISSUES.md))

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
   - **Parser Type**: Rockwell (auto-detects L5X/L5K by extension — L5X is the fully-verified primary format), JSON, or CSV
   - **File Content** (Optional): Click "Upload PLC File" or paste content
4. Save - tags are automatically created and browseable via OPC-UA

### Web UI Pages

- **Connection Browser**: `/data/logixemulator/connection-browser` (requires authentication) - Browse tags and manage PLC files
- **Edit Program**: `/data/logixemulator/edit-program` (requires authentication)

## Features

- **Device Driver Architecture**: Appears as standard device connection in Ignition
- **OPC-UA Integration**: Tags automatically available via Ignition's OPC-UA server, with driver-matching NodeIds (see [Tag Addressing](#tag-addressing))
- **Hierarchical Browse Structure**: Preserves PLC program organization (Controller/Program scopes) for navigation, independent of the NodeId identifier
- **Simulation Engine**: 5 patterns (STATIC, RAMP, SINE, RANDOM, TOGGLE) with per-tag control. Simulation must be enabled on the device (`Enable Simulation`, off by default); per-tag and bulk-by-scope simulation requests return `409 Conflict` while the device-level flag is off
- **Bulk Simulation**: Enable/disable simulation by scope or for all tags
- **SINT Support**: Simulates Short values in -128 to 127 range
- **Hot Reload**: Automatic tag update when PLC file changes on disk, including array and nested-member value changes
- **Incremental Updates**: Smart detection of structural vs value-only changes
- **Connection Browser UI**: Unified tag exploration, live values, write support, and file upload
- **File Versioning**: Last 5 versions kept; list and revert to a previous version via REST (`GET`/`POST /device/:name/versions[/revert]`) or the web UI
- **Security**: Path traversal prevention, rate limiting, authentication on all routes, `ExternalAccess`-based read-only enforcement on every write path

## API Endpoints

All routes require authentication (Gateway login).

| Method | Endpoint | Description |
| -------- | ---------- | ------------- |
| POST | `/data/logixemulator/upload` | Upload PLC file to device |
| GET | `/data/logixemulator/devices` | List all emulated devices |
| GET | `/data/logixemulator/device/:name/status` | Device status and file info |
| GET | `/data/logixemulator/device/:name/tags` | Get tag tree (paginated) |
| GET | `/data/logixemulator/device/:name/tags/live` | Live tag values |
| POST | `/data/logixemulator/device/:name/tag/write` | Write tag value |
| POST | `/data/logixemulator/device/:name/tag/simulate` | Toggle per-tag simulation |
| POST | `/data/logixemulator/device/:name/simulation/scope` | Bulk enable/disable by scope |
| POST | `/data/logixemulator/device/:name/simulation/all` | Enable/disable all simulation |
| GET | `/data/logixemulator/device/:name/versions` | List retained file versions (last 5) |
| POST | `/data/logixemulator/device/:name/versions/revert` | Revert to a previous file version |
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

MIT License - see [LICENSE](LICENSE) for details
