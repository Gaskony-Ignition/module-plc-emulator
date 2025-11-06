# Architecture Overview

## System Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                         INPUT: PLC FILES                         │
├──────────────┬─────────────┬──────────────┬──────────────────────┤
│ Rockwell     │ Siemens     │ Schneider    │ Beckhoff             │
│ .L5K         │ .XML, .CSV  │ .XEF, .CSV   │ .TSPROJ, .XML        │
└──────┬───────┴──────┬──────┴──────┬───────┴──────┬───────────────┘
       │              │              │              │
       ▼              ▼              ▼              ▼
┌──────────────────────────────────────────────────────────────────┐
│                    PARSERS (PLC-Specific)                        │
├──────────────┬─────────────┬──────────────┬──────────────────────┤
│ Rockwell     │ Siemens     │ Schneider    │ Beckhoff             │
│ L5K Parser   │ TIA Parser  │ Unity Parser │ TwinCAT Parser       │
│ ✅ Complete  │ 🚧 Template │ 🚧 Template  │ 🚧 Template          │
└──────┬───────┴──────┬──────┴──────┬───────┴──────┬───────────────┘
       │              │              │              │
       └──────────────┴──────────────┴──────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│              COMMON TAG MODEL (Universal Format)                 │
├──────────────────────────────────────────────────────────────────┤
│  PLCProject                                                      │
│    ├── UDTs (User-Defined Types)                                │
│    │    └── Members (Tags)                                      │
│    ├── Global Tags                                              │
│    └── Programs                                                 │
│         └── Program Tags                                        │
│                                                                  │
│  Tag: name, data_type, description, array, initial_value, ...   │
│  DataType: BOOL, INT1, INT2, INT4, FLOAT4, STRING, UDT          │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ├────────────────┬────────────────┐
                           │                │                │
                           ▼                ▼                ▼
┌──────────────────────────────────────────────────────────────────┐
│                    PROTOCOL LAYER                                │
├──────────────┬──────────────┬──────────────┬───────────────────┤
│ OPC-UA       │ Modbus       │ EtherNet/IP  │ Memory/Simulator  │
│ AddressProvider              │ AddressProvider                  │
├──────────────┴──────────────┴──────────────┴───────────────────┤
│ Generates protocol-specific addresses for each tag:             │
│  • OPC-UA:     ns=1;s=[Device]Tag.Path                          │
│  • Modbus:     [Device]HR40001                                  │
│  • EtherIP:    [Device]Program:Main.Tag                         │
│  • Memory:     (no address, internal tags)                      │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                    IGNITION EXPORTER                             │
├──────────────────────────────────────────────────────────────────┤
│  Converts common model + protocol addresses to:                 │
│   1. UDT Definitions (JSON)                                     │
│   2. Tag Instances (JSON)                                       │
│   3. Simulator Program (CSV)                                    │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                    OUTPUT: IGNITION FILES                        │
├──────────────────────────────────────────────────────────────────┤
│  udts.json       → Import to Designer (UDT Definitions)         │
│  tags.json       → Import to Designer (Tag Instances)           │
│  simulator.csv   → Upload to Programmable Device Simulator      │
└──────────────────────────────────────────────────────────────────┘
```

## Data Flow Example

### Input: Rockwell L5K File
```
TAG
  Tank1_Level : REAL := 0.0 (Description := "Tank 1 level sensor");
  Pump1 : PumpControl;
END_TAG

DATATYPE PumpControl
  Running : BOOL;
  Speed : INT;
END_DATATYPE

PROGRAM MainProgram
  TAG
    LocalTimer : TIMER;
  END_TAG
END_PROGRAM
```

### Step 1: Parser → Common Model
```python
PLCProject(
  name="MyController",
  plc_type="Rockwell Logix 5000",
  udts=[
    UDT(name="PumpControl", members=[
      Tag(name="Running", data_type=DataType.BOOL),
      Tag(name="Speed", data_type=DataType.INT2)
    ])
  ],
  global_tags=[
    Tag(name="Tank1_Level", data_type=DataType.FLOAT4,
        description="Tank 1 level sensor"),
    Tag(name="Pump1", data_type="PumpControl")
  ],
  programs=[
    Program(name="MainProgram", tags=[
      Tag(name="LocalTimer", data_type="TIMER")
    ])
  ]
)
```

### Step 2: Protocol Layer → Addresses
```python
# With OPC-UA protocol:
Tag("Tank1_Level") → "ns=1;s=[SimDevice]Tank1_Level"
Tag("Pump1.Running") → "ns=1;s=[SimDevice]Pump1.Running"
Tag("LocalTimer.DN") → "ns=1;s=[SimDevice]MainProgram.LocalTimer.DN"

# With Modbus protocol (same tags):
Tag("Tank1_Level") → "[SimDevice]HR40001"
Tag("Pump1.Running") → "[SimDevice]HR40003"
Tag("LocalTimer.DN") → "[SimDevice]HR40010"
```

### Step 3: Exporter → Ignition Files

**udts.json**:
```json
{
  "tags": [
    {
      "name": "PumpControl",
      "tagType": "UdtType",
      "parameters": {
        "DeviceName": {"dataType": "String"},
        "TagPrefix": {"dataType": "String"}
      },
      "tags": [
        {
          "name": "Running",
          "tagType": "AtomicTag",
          "dataType": "Boolean",
          "opcItemPath": {
            "bindType": "parameter",
            "binding": "ns=1;s=[{DeviceName}]{TagPrefix}.Running"
          }
        }
      ]
    }
  ]
}
```

**tags.json**:
```json
{
  "tags": [
    {
      "name": "Tank1_Level",
      "tagType": "AtomicTag",
      "dataType": "Float4",
      "opcItemPath": "ns=1;s=[SimDevice]Tank1_Level",
      "valueSource": "opc"
    },
    {
      "name": "Pump1",
      "tagType": "UdtInstance",
      "typeId": "PumpControl",
      "parameters": {
        "DeviceName": {"value": {"binding": "SimDevice"}},
        "TagPrefix": {"value": {"binding": "Pump1"}}
      }
    },
    {
      "name": "Program MainProgram",
      "tagType": "Folder",
      "tags": [/* LocalTimer tags */]
    }
  ]
}
```

**simulator.csv**:
```csv
Time Interval,Browse Path,Value Source,Data Type
0,Tank1_Level,0.0,Float
0,Pump1.Running,false,Boolean
0,Pump1.Speed,0,Int16
0,MainProgram.LocalTimer.DN,false,Boolean
```

## Component Responsibilities

### Models (`models/tag_model.py`)
- **Responsibility**: Universal data representation
- **Key Classes**: `PLCProject`, `Tag`, `UDT`, `Program`, `DataType`
- **Design**: Protocol-agnostic, PLC-agnostic
- **Role**: "Language" that all components speak

### Parsers (`parsers/`)
- **Responsibility**: Read PLC-specific files → Common Model
- **Interface**: `PLCParser` abstract base class
- **Implementation**: One parser per PLC type
- **Design Pattern**: Strategy Pattern (interchangeable parsers)

### Protocols (`protocols/address_provider.py`)
- **Responsibility**: Generate protocol-specific addresses
- **Interface**: `AddressProvider` abstract base class
- **Implementations**: OPC-UA, Modbus, EtherNet/IP, Memory, Simulator
- **Design Pattern**: Strategy Pattern (interchangeable protocols)

### Exporters (`exporters/ignition_exporter.py`)
- **Responsibility**: Common Model + Protocol → Ignition formats
- **Outputs**: UDT JSON, Tag JSON, Simulator CSV
- **Features**: Folder structure support, parameterized UDTs

## Extension Points

### Adding a New PLC Parser

```python
from parsers.base_parser import PLCParser
from models.tag_model import PLCProject, Tag, DataType

class MyPLCParser(PLCParser):
    def can_parse(self, file_path, file_content=None):
        return file_path.endswith('.myext')

    def parse(self, file_path, options=None):
        # Read file
        # Extract tags, UDTs, programs
        # Return PLCProject
        return PLCProject(...)

    def get_supported_extensions(self):
        return ['.myext']

    def get_plc_type_name(self):
        return "My PLC Brand"

# Register
from parsers.base_parser import register_parser
register_parser(MyPLCParser())
```

### Adding a New Protocol

```python
from protocols.address_provider import AddressProvider

class MyProtocolProvider(AddressProvider):
    def get_tag_address(self, tag, tag_path):
        return f"myprotocol://{self.device_name}/{tag_path}"

    def get_value_source(self):
        return "myprotocol"

    def get_server_name(self):
        return "My Protocol Server"

    def supports_hierarchy(self):
        return True

# Use
from protocols.address_provider import create_address_provider
# Add to factory function in address_provider.py
```

## Key Design Decisions

### 1. Why Common Model?
- **Problem**: Each PLC has different terminology (UDT vs. Struct vs. DFB)
- **Solution**: Universal representation that works for all
- **Benefit**: Write exporters once, support N PLCs

### 2. Why Protocol Abstraction?
- **Problem**: Hard-coded OPC-UA paths in original
- **Solution**: Pluggable address providers
- **Benefit**: Same tags, multiple protocols

### 3. Why Folder Structure Option?
- **Problem**: Original appeared "flat" in simulator
- **Solution**: Add Ignition folder tags option
- **Benefit**: Better organization while maintaining backward compatibility

### 4. Why Templates for Incomplete Parsers?
- **Problem**: Don't have sample files for all PLC types yet
- **Solution**: Provide skeleton implementations
- **Benefit**: Clear path to completion once samples available

## Performance Considerations

### Memory
- **Lazy Loading**: Parsers read file once, build in-memory model
- **No Caching**: Each parse creates fresh objects
- **Trade-off**: Simplicity over optimization (fine for typical file sizes)

### Speed
- **Regex Parsing**: Fast for text-based formats (L5K, CSV)
- **XML Parsing**: Uses ElementTree (C-optimized)
- **Bottleneck**: File I/O, not processing

### Scalability
- **File Size**: Tested with 80K+ line L5K (5.5 MB)
- **Tag Count**: Handles thousands of tags
- **Limitation**: Memory-bound (entire project in memory)

## Future Enhancements

### Potential Additions
1. **Streaming Parser**: For very large files (100K+ tags)
2. **Validation**: Schema validation for exports
3. **Diff Tool**: Compare two PLC projects
4. **Merge Tool**: Combine multiple PLC projects
5. **Web UI**: Browser-based converter
6. **CLI Tool**: Command-line interface
7. **Ignition Module**: Native Ignition integration

### Other SCADA Platforms
The architecture is exporteр-agnostic. Could add:
- Wonderware exporter
- FactoryTalk View exporter
- AVEVA System Platform exporter

## Testing Strategy

### Unit Tests (Recommended)
```python
# Test parser
def test_l5k_parser():
    parser = RockwellL5KParser()
    project = parser.parse("test.L5K")
    assert project.plc_type == "Rockwell Logix 5000"
    assert len(project.global_tags) > 0

# Test protocol
def test_opcua_addressing():
    provider = OPCUAAddressProvider("Device1")
    tag = Tag("Temperature", DataType.FLOAT4)
    address = provider.get_tag_address(tag, "Temperature")
    assert address == "ns=1;s=[Device1]Temperature"

# Test exporter
def test_ignition_export():
    project = PLCProject(...)
    exporter = IgnitionExporter(protocol="opcua")
    json = exporter.export_tags(project)
    assert "ns=1;s=" in json
```

### Integration Tests
1. Parse real L5K file
2. Export all three formats
3. Validate JSON structure
4. Verify CSV format

## Summary

This architecture provides:
- ✅ **Separation of Concerns**: Parser, Model, Protocol, Exporter
- ✅ **Extensibility**: Add parsers/protocols without touching core
- ✅ **Testability**: Each component testable independently
- ✅ **Maintainability**: Clear responsibilities, minimal coupling
- ✅ **Reusability**: Common model works for any PLC/protocol
- ✅ **Flexibility**: Mix and match parsers, protocols, exporters

The refactored system transforms a **single-vendor, single-protocol** tool into a **multi-vendor, multi-protocol platform** while maintaining backward compatibility and improving code organization.
