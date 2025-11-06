```markdown
# Multi-Protocol PLC Simulator for Ignition

A refactored, extensible PLC tag parser and simulator generator that supports multiple PLC brands and protocols for Inductive Automation's Ignition platform.

## 🎯 Overview

This project transforms PLC program exports into Ignition simulator configurations, enabling development and testing without physical hardware. The refactored architecture supports **multiple PLC brands** and **multiple communication protocols**.

### Key Features

✅ **Multi-Protocol Support**
- OPC-UA (with hierarchical browsing)
- Modbus TCP/RTU
- EtherNet/IP
- Memory tags (internal Ignition tags)
- Programmable Device Simulator

✅ **Multi-Vendor Support**
- ✅ **Rockwell** (L5K exports) - Full implementation
- ✅ **Generic OPC-UA CSV** - Full implementation
- 🚧 **Siemens** TIA Portal (XML/CSV) - Template provided
- 🚧 **Schneider Electric** Unity Pro/EcoStruxure - Template provided
- 🚧 **Beckhoff** TwinCAT 2/3 - Template provided

✅ **True Folder Structures**
- Maintains PLC program hierarchy
- Creates Ignition folder tags for organization
- Preserves scope (global vs. program tags)

✅ **UDT Support**
- Converts PLC UDTs to Ignition UDT definitions
- Supports nested UDTs
- Handles arrays of UDTs

## 🏗️ Architecture

### Design Principles

The refactored system uses **abstraction layers** to separate concerns:

```
┌─────────────────┐
│  PLC Files      │  .L5K, .CSV, .XML, .XEF, .tsproj
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Parsers        │  RockwellL5KParser, SiemensTIAParser, etc.
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Common Model   │  PLCProject, Tag, UDT, Program
└────────┬────────┘
         │
         ├──────────────────┬──────────────────┐
         ▼                  ▼                  ▼
┌─────────────────┐ ┌──────────────┐ ┌──────────────┐
│  Exporters      │ │  Protocols   │ │  Simulation  │
│  (Ignition)     │ │  (Addressing)│ │  (CSV)       │
└─────────────────┘ └──────────────┘ └──────────────┘
         │                  │                  │
         ▼                  ▼                  ▼
┌──────────────────────────────────────────────────┐
│  Ignition Gateway                                │
│  • UDT Definitions (JSON)                        │
│  • Tag Instances (JSON)                          │
│  • Simulator Program (CSV)                       │
└──────────────────────────────────────────────────┘
```

### Core Components

#### 1. **Tag Model** (`models/tag_model.py`)
Common representation that works across all PLC types:
- `Tag`: Universal tag with name, data type, description, array support
- `UDT`: User-defined type with members
- `Program`: Organizational unit (programs, functions, data blocks)
- `PLCProject`: Complete project with all components

#### 2. **Parsers** (`parsers/`)
Implement `PLCParser` interface to read PLC-specific formats:
- **Rockwell L5K** (`rockwell_l5k_parser.py`) - ✅ Complete
- **Generic OPC-UA CSV** (`opcua_csv_parser.py`) - ✅ Complete
- **Siemens TIA Portal** (`siemens_tia_parser.py`) - 🚧 Template
- **Schneider Electric** (`schneider_parser.py`) - 🚧 Template
- **Beckhoff TwinCAT** (`beckhoff_parser.py`) - 🚧 Template

#### 3. **Address Providers** (`protocols/address_provider.py`)
Generate protocol-specific addresses for tags:
- **OPCUAAddressProvider**: `ns=1;s=[Device]Tag.Path`
- **ModbusAddressProvider**: `[Device]HR40001`
- **EtherNetIPAddressProvider**: `[Device]Program:Main.Tag`
- **MemoryAddressProvider**: Internal Ignition tags
- **SimulatorAddressProvider**: Flat paths for simulator CSV

#### 4. **Exporters** (`exporters/ignition_exporter.py`)
Convert common model to Ignition formats:
- **UDT Definitions**: Parameterized JSON with `{DeviceName}`, `{TagPrefix}`
- **Tag Instances**: Actual tag configurations with protocol bindings
- **Simulator CSV**: Time interval, browse path, value source, data type

## 🚀 Quick Start

### Basic Usage

```python
from parsers.rockwell_l5k_parser import RockwellL5KParser
from exporters.ignition_exporter import IgnitionExporter

# 1. Parse PLC file
parser = RockwellL5KParser()
project = parser.parse("myplc.L5K", options={
    'select_tags': True,
    'allow_hidden': False,
    'use_stored_values': True
})

# 2. Export to Ignition
exporter = IgnitionExporter(
    protocol="opcua",              # Protocol: opcua, modbus, ethernetip, memory
    device_name="SimulatorDevice", # Device name for OPC paths
    udt_prefix="Rockwell",         # Optional folder for UDTs
    create_folders=True            # TRUE FOLDER STRUCTURES!
)

# 3. Generate exports
udts_json = exporter.export_udts(project)         # UDT definitions
tags_json = exporter.export_tags(project)         # Tag instances
simulator_csv = exporter.export_simulator_csv(project)  # Simulator program

# 4. Save files
with open("udts.json", "w") as f:
    f.write(udts_json)
with open("tags.json", "w") as f:
    f.write(tags_json)
with open("simulator.csv", "w") as f:
    f.write(simulator_csv)
```

### Importing to Ignition

1. **Import UDT Definitions**
   - Designer → Tags → UDT Definitions
   - Right-click → Import Tags
   - Select `udts.json`

2. **Import Tag Instances**
   - Designer → Tags → Default (or your tag provider)
   - Right-click → Import Tags
   - Select `tags.json`

3. **Configure Simulator** (if using Programmable Device Simulator)
   - Create Programmable Device in OPC-UA Device Connections
   - Upload `simulator.csv` as instruction file

## 📋 Supported Formats

### Rockwell Automation (L5K)

**Status**: ✅ Full Support

**File Format**: L5K (Logix 5000 Export)

**Supports**:
- Controller-scoped (global) tags
- Program-scoped tags
- User-defined types (UDTs)
- Add-On Instructions (AOIs) as UDTs
- Built-in types (TIMER, COUNTER, MESSAGE, CONTROL)
- Arrays (single and multi-dimensional)
- Hidden tags and BOOL packing
- Tag descriptions and comments

**Example**:
```python
from parsers.rockwell_l5k_parser import RockwellL5KParser

parser = RockwellL5KParser()
project = parser.parse("CompactLogix_Export.L5K")
```

### Generic OPC-UA CSV

**Status**: ✅ Full Support

**File Format**: CSV with columns: `TagName`, `DataType`, `Description`, `InitialValue`, `Folder`, `ArrayLength`

**Use Cases**:
- Exports from any OPC-UA server
- KEPServerEX tag lists
- Ignition tag exports
- Custom tag lists

**Example CSV**:
```csv
TagName,DataType,Description,InitialValue,Folder
Tank1_Level,REAL,Tank 1 level sensor,0.0,Tanks
Pump_Status,BOOL,Main pump status,false,Pumps
Temperatures,INT,Temperature array,,Sensors,10
```

**Example**:
```python
from parsers.opcua_csv_parser import OPCUACSVParser

parser = OPCUACSVParser()
project = parser.parse("tags.csv")
```

### Siemens TIA Portal

**Status**: 🚧 Template Provided

**File Formats**:
- XML (TIA Portal project export)
- CSV (Symbol table export)

**To Complete**:
1. Provide sample TIA Portal XML export
2. Implement XML parsing based on schema
3. Map Siemens data types (DB, UDT, FB)
4. Handle symbolic addressing

**Template Location**: `parsers/siemens_tia_parser.py`

### Schneider Electric

**Status**: 🚧 Template Provided

**File Formats**:
- XEF (Unity Pro export)
- CSV (Variable list)
- XML (EcoStruxure export)

**To Complete**:
1. Provide sample Unity Pro/EcoStruxure exports
2. Implement XEF/XML parsing
3. Map DFB (Derived Function Blocks) to UDTs
4. Handle located variables (%M, %I, %Q)

**Template Location**: `parsers/schneider_parser.py`

### Beckhoff TwinCAT

**Status**: 🚧 Template Provided

**File Formats**:
- TSPROJ (TwinCAT 3 XML project)
- XML (PLC project export)
- CSV (Variable export)

**To Complete**:
1. Provide sample TwinCAT export
2. Implement XML parsing for POUs, DUTs, GVLs
3. Parse IEC 61131-3 declarations
4. Handle STRUCT and FB instances

**Template Location**: `parsers/beckhoff_parser.py`

## 🔌 Protocol Support

### OPC-UA (Recommended)

**Advantages**:
- ✅ Hierarchical browsing
- ✅ Works with any OPC-UA server
- ✅ Standard protocol

**Address Format**: `ns=1;s=[DeviceName]TagPath`

**Example**:
```python
exporter = IgnitionExporter(protocol="opcua", device_name="PLC1")
```

### Modbus TCP/RTU

**Advantages**:
- ✅ Simple addressing
- ✅ Wide device support

**Limitations**:
- ❌ No hierarchical browsing
- ⚠️ Manual register mapping

**Address Format**: `[DeviceName]HR40001`

**Example**:
```python
exporter = IgnitionExporter(protocol="modbus", device_name="ModbusPLC")
```

### EtherNet/IP (CIP)

**Advantages**:
- ✅ Native for Allen-Bradley PLCs
- ✅ Hierarchical paths

**Address Format**: `[DeviceName]Program:MainProgram.Tag`

**Example**:
```python
exporter = IgnitionExporter(protocol="ethernetip", device_name="ControlLogix")
```

### Memory (Internal Tags)

**Use Case**: Tags that don't connect to external devices

**Example**:
```python
exporter = IgnitionExporter(protocol="memory")
```

## 🎓 Advanced Usage

### Custom Parser Example

```python
from parsers.base_parser import PLCParser
from models.tag_model import PLCProject, Tag, DataType

class MyCustomParser(PLCParser):
    def can_parse(self, file_path, file_content=None):
        return file_path.endswith('.myformat')

    def get_supported_extensions(self):
        return ['.myformat']

    def get_plc_type_name(self):
        return "My Custom PLC"

    def parse(self, file_path, options=None):
        # Your parsing logic here
        tags = [
            Tag("Tag1", DataType.BOOL, description="My tag")
        ]
        return PLCProject(
            name="My Project",
            plc_type=self.get_plc_type_name(),
            global_tags=tags
        )

# Register and use
from parsers.base_parser import register_parser
register_parser(MyCustomParser())
```

### Filtering Tags

```python
# Filter tags before export
project.global_tags = [t for t in project.global_tags if not t.name.startswith("_")]
```

### Custom Initial Values

```python
# Set simulation values
for tag in project.global_tags:
    if tag.data_type == DataType.FLOAT4:
        tag.initial_value = 123.45
```

### Multiple Devices

```python
# Export same project to multiple devices
for device in ["PLC1", "PLC2", "PLC3"]:
    exporter = IgnitionExporter(protocol="opcua", device_name=device)
    tags = exporter.export_tags(project)
    with open(f"tags_{device}.json", "w") as f:
        f.write(tags)
```

## 📁 Project Structure

```
plc-simulator-refactored/
├── models/
│   └── tag_model.py           # Common tag representation
├── parsers/
│   ├── base_parser.py         # Parser interface & registry
│   ├── rockwell_l5k_parser.py # Rockwell L5K (complete)
│   ├── opcua_csv_parser.py    # Generic CSV (complete)
│   ├── siemens_tia_parser.py  # Siemens TIA (template)
│   ├── schneider_parser.py    # Schneider (template)
│   └── beckhoff_parser.py     # Beckhoff (template)
├── protocols/
│   └── address_provider.py    # Protocol abstractions
├── exporters/
│   └── ignition_exporter.py   # Ignition format exporter
├── utils/
│   └── (future utilities)
├── example_usage.py           # Example scripts
└── README.md                  # This file
```

## 🔄 Migration from Original

### Key Improvements

1. **Hierarchy Preserved**
   - Old: Appeared flat due to simulator CSV
   - New: True folder structures + maintains OPC path hierarchy

2. **Protocol Flexibility**
   - Old: Hardcoded OPC-UA
   - New: Pluggable protocols (OPC-UA, Modbus, EtherNet/IP, Memory)

3. **Multi-Vendor**
   - Old: Rockwell L5K only
   - New: Extensible to any PLC brand

4. **Clean Architecture**
   - Old: Monolithic parser
   - New: Separated concerns (parse → model → export)

### Backwards Compatibility

The new system produces **identical output** for L5K files when using:
```python
exporter = IgnitionExporter(
    protocol="opcua",
    device_name="SimulatorDevice",
    create_folders=False  # Disable new folder structure
)
```

## 🤝 Contributing

### Adding a New Parser

1. Create parser class inheriting from `PLCParser`
2. Implement required methods:
   - `can_parse()`: Detect file format
   - `parse()`: Parse file → `PLCProject`
   - `get_supported_extensions()`: File extensions
   - `get_plc_type_name()`: Display name

3. Convert PLC data types to standard `DataType` enum
4. Build `Tag`, `UDT`, `Program` objects
5. Register parser: `register_parser(MyParser())`

### Adding a New Protocol

1. Create class inheriting from `AddressProvider`
2. Implement:
   - `get_tag_address()`: Generate protocol-specific address
   - `get_value_source()`: Return value source type
   - `get_server_name()`: Return server name
   - `supports_hierarchy()`: Boolean for hierarchy support

3. Add to `create_address_provider()` factory

### Testing

Provide sample files when implementing new parsers:
- Small test file (< 1 MB)
- Covers: UDTs, arrays, nested structures, programs
- Include expected output

## ❓ FAQ

**Q: Why does the old system appear to "flatten" tags?**

A: It doesn't! The hierarchy is maintained through OPC path dot notation (`Tag.Member.SubMember`). The simulator CSV appears flat because that's how the Programmable Device Simulator works, but Ignition's tag browser shows proper hierarchy.

**Q: Can I use this without Ignition?**

A: Yes! The parsers and common model are Ignition-independent. You could write exporters for other SCADA systems (e.g., Wonderware, FactoryTalk View).

**Q: How do I handle custom PLC data types?**

A: The parser stores unknown types as strings. When building UDT instances, it looks up the type definition. If not found, creates an empty UDT placeholder.

**Q: Can I mix multiple PLC types in one project?**

A: Yes! Parse each PLC file separately, then merge:
```python
combined = PLCProject(name="Combined")
combined.udts = project1.udts + project2.udts
combined.global_tags = project1.global_tags + project2.global_tags
```

**Q: How do I contribute a Siemens/Schneider/Beckhoff parser?**

A:
1. Fork the repository
2. Provide sample export files (sanitized if needed)
3. Implement parser following templates
4. Add tests and documentation
5. Submit pull request

## 📜 License

This project is provided as-is for use with Inductive Automation's Ignition platform.

## 🙏 Acknowledgments

- Original L5K parser from Ignition Exchange
- Inductive Automation for Ignition platform
- Community contributors

---

**Questions?** Open an issue or discussion on the repository.

**Need Help Implementing a Parser?** Provide sample files and we'll assist!
```
