# Ignition PLC Simulator - Project Repository

This repository contains multiple related projects for PLC simulation in Inductive Automation's Ignition platform.

## Repository Structure

```
ignition-plc-simulator/
├── plc-simulator-module/          # MAIN PROJECT: Ignition device driver module
│   ├── gateway/                   # Gateway-side code
│   ├── designer/                  # Designer-side code
│   ├── common/                    # Shared code
│   ├── docs/                      # Documentation
│   └── README.md                  # Module documentation
│
├── plc-simulator-refactored/      # Python-based parser (standalone tool)
│   ├── parsers/                   # Multi-vendor parsers
│   ├── exporters/                 # Ignition format exporters
│   ├── protocols/                 # Protocol address providers
│   └── README.md                  # Parser documentation
│
├── archive/                       # Old implementation (archived)
│   └── old-implementation/        # Previous versions
│
├── claude.md                      # Project history and context
├── example_plc.json               # Sample PLC definition
├── simple_plc.json                # Simple test PLC
├── *.L5K                          # Sample Rockwell export files
└── *.png                          # Reference screenshots
```

## Main Projects

### 1. Enhanced PLC Simulator (Ignition Module) ⭐ PRIMARY

**Location**: `/plc-simulator-module/`
**Type**: Ignition Gateway Module (.modl)
**Language**: Java 17
**Status**: Active Development (v1.1.0)

**Description**:
Native Ignition device driver that appears in the Gateway device connections dropdown. Simulates PLCs by parsing export files and creating OPC-UA accessible tags.

**Key Features**:
- Device driver architecture
- Multi-vendor parser support
- Direct tag creation in Ignition
- OPC-UA integration
- i18n support

**Documentation**: [plc-simulator-module/README.md](plc-simulator-module/README.md)

**Quick Start**:
```bash
cd plc-simulator-module
./gradlew clean build
# Install build/EnhancedPLCSimulator-1.1.0.modl in Ignition
```

---

### 2. PLC Simulator Refactored (Python Parser)

**Location**: `/plc-simulator-refactored/`
**Type**: Standalone Python CLI Tool
**Language**: Python 3
**Status**: Reference Implementation (Functional)

**Description**:
Extensible Python-based parser that converts PLC exports to Ignition-compatible formats (JSON/CSV). Supports multiple protocols and vendors.

**Key Features**:
- Multi-vendor parsers (Rockwell, JSON, Siemens*, Schneider*, Beckhoff*)
- Multi-protocol support (OPC-UA, Modbus, EtherNet/IP)
- Export to Ignition UDT/Tag JSON and Simulator CSV
- Offline tag generation

**Documentation**: [plc-simulator-refactored/README.md](plc-simulator-refactored/README.md)

**Quick Start**:
```bash
cd plc-simulator-refactored
python3 example_usage.py
# Generates udts.json, tags.json, simulator.csv
```

---

## Project History

This repository evolved through several phases:

1. **Initial L5K Parser** - Python script to parse Rockwell L5K files
2. **Refactored Architecture** - Multi-vendor parser with protocol abstraction
3. **Ignition Module** - Java-based device driver module (current focus)
4. **OPC-UA Server** - Standalone Python OPC-UA server (experimental)

See [claude.md](claude.md) for detailed project history and development notes.

---

## Which Project Should I Use?

### Use Ignition Module If:
- ✅ You want native Ignition integration
- ✅ You want device driver in Gateway UI
- ✅ You want live simulation with minimal setup
- ✅ You don't need offline processing

### Use Python Parser If:
- ✅ You need offline tag generation
- ✅ You want multi-protocol addressing
- ✅ You need to process files outside Ignition
- ✅ You want to add custom parsers easily

### Use Both If:
- You need different use cases served by each
- You want maximum flexibility

---

## Documentation

### Ignition Module Documentation
- [README.md](plc-simulator-module/README.md) - Overview and quick start
- [BUILD.md](plc-simulator-module/BUILD.md) - Build instructions
- [DEVELOPMENT.md](plc-simulator-module/DEVELOPMENT.md) - Development guide
- [KNOWN_ISSUES.md](plc-simulator-module/KNOWN_ISSUES.md) - Limitations and issues
- [CHANGELOG.md](plc-simulator-module/CHANGELOG.md) - Version history
- [TESTING.md](plc-simulator-module/TESTING.md) - Testing procedures
- [SIGNING.md](plc-simulator-module/SIGNING.md) - Module signing

### Python Parser Documentation
- [README.md](plc-simulator-refactored/README.md) - Overview and usage
- [ARCHITECTURE.md](plc-simulator-refactored/ARCHITECTURE.md) - System design
- [IMPLEMENTATION_SUMMARY.md](plc-simulator-refactored/IMPLEMENTATION_SUMMARY.md) - Development notes
- [TEST_RESULTS.md](plc-simulator-refactored/TEST_RESULTS.md) - Test results

### Archived Documentation
- [docs/archive/](plc-simulator-module/docs/archive/) - Historical documentation
- [docs/archive/README.md](plc-simulator-module/docs/archive/README.md) - Archive guide

---

## Sample Files

Repository includes sample PLC files for testing:

- **DemoWWTP-sample-a.L5K** (5.5MB) - Real Rockwell PLC export with 9,886 tags
- **example_plc.json** - Comprehensive JSON example with pumps, tanks, motors
- **simple_plc.json** - Minimal JSON example for testing

Screenshots:
- **RealPLCstructure1.png** - Real PLC OPC-UA structure (top level)
- **RealPLCstructure2.png** - Real PLC Controller:Global tags
- **SimulatorStructure.png** - Simulator OPC-UA structure
- **example1.png, import.png, importexample.png** - UI examples

---

## Current Status

### Ignition Module (v1.1.0)
**What Works**: ✅
- Device driver registration
- Multi-vendor parser dropdowns
- i18n display names
- Tag creation from parsed files

**What Doesn't Work**: ❌
- File upload button (requires redesign)

**Current Focus**:
- Stabilizing v1.1.x with copy/paste workflow
- Planning v2.0 with file upload feature

### Python Parser
**Status**: Functional and complete
- All parsers implemented (Rockwell, JSON)
- Template parsers provided (Siemens, Schneider, Beckhoff)
- Tested with large files (9,886 tags)
- Not actively developed (stable)

---

## Contributing

### To Ignition Module:
See [plc-simulator-module/DEVELOPMENT.md](plc-simulator-module/DEVELOPMENT.md)

### To Python Parser:
See [plc-simulator-refactored/README.md](plc-simulator-refactored/README.md#contributing)

---

## License

This project is built for use with Inductive Automation's Ignition platform.

- **Ignition Module**: Free module (`isFreeModule = true`)
- **Python Parser**: Open source reference implementation

---

## Project Context

For detailed project context, development history, and technical decisions, see:
- **[claude.md](claude.md)** - Comprehensive project history, OPC-UA server development, design decisions

---

## Support

- **Issues**: Report via project repository
- **Documentation**: See respective README files in each project folder
- **Known Issues**: See [plc-simulator-module/KNOWN_ISSUES.md](plc-simulator-module/KNOWN_ISSUES.md)

---

**Repository Created**: 2025-11-02
**Last Updated**: 2025-11-07
**Primary Maintainer**: Development team
**Ignition SDK Version**: 8.1.x
