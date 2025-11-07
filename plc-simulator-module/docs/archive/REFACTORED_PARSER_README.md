# PLC Simulator Refactored - Python Parser (Archived)

## Location
`/modules/ignition-plc-simulator/plc-simulator-refactored/`

## What Is This?

This is a **separate Python-based PLC parser and exporter project** that was developed alongside the Ignition module. It is NOT part of the Ignition module itself.

## Why Two Separate Projects?

### Enhanced PLC Simulator (Ignition Module)
- **Language**: Java
- **Purpose**: Ignition device driver module
- **Architecture**: Device driver appearing in Gateway device connections
- **Integration**: Native Ignition module (.modl file)
- **Location**: `/modules/ignition-plc-simulator/plc-simulator-module/`
- **Status**: Active development (v1.1.0)

### PLC Simulator Refactored (Python Parser)
- **Language**: Python 3
- **Purpose**: Standalone PLC file parser and exporter
- **Architecture**: Modular parser system with protocol abstraction
- **Integration**: Command-line tool, can run independently
- **Location**: `/modules/ignition-plc-simulator/plc-simulator-refactored/`
- **Status**: Reference implementation

## Key Differences

| Aspect | Ignition Module | Python Parser |
|--------|----------------|---------------|
| **Integration** | Native Ignition module | Standalone CLI tool |
| **UI** | Gateway device configuration | Command-line |
| **Parsing** | Java-based (in module) | Python-based |
| **Output** | Direct tag creation in Ignition | CSV/JSON exports |
| **Use Case** | Live simulation in Ignition | Offline tag generation |
| **Distribution** | .modl file | Python package |

## Architecture of Python Parser

The refactored Python parser uses a layered architecture:

```
PLC Files → Parsers → Common Model → Protocols → Exporters → Ignition Files
```

**Components:**
- **Parsers**: Rockwell L5K, Siemens, Schneider, Beckhoff, JSON
- **Common Model**: Universal tag representation
- **Protocols**: OPC-UA, Modbus, EtherNet/IP, Memory
- **Exporters**: Ignition UDT JSON, Tag JSON, Simulator CSV

See `/modules/ignition-plc-simulator/plc-simulator-refactored/ARCHITECTURE.md` for details.

## Why Keep Both?

**Python Parser Advantages:**
- Multi-protocol support (OPC-UA, Modbus, EtherNet/IP)
- Extensible parser framework
- Can be used outside Ignition
- Easier to add new parsers (Python vs Java)
- Generates files for import rather than live simulation

**Ignition Module Advantages:**
- Native integration with Gateway
- Live device simulation
- No external dependencies
- Standard device driver UX
- Direct tag creation (no import step)

## Status of Python Parser

**Fully Implemented:**
- ✅ Rockwell L5K parser
- ✅ JSON format parser
- ✅ OPC-UA, Modbus, EtherNet/IP protocol support
- ✅ Ignition UDT/tag JSON export
- ✅ Simulator CSV export
- ✅ Multi-vendor architecture

**Planned (Templates Provided):**
- 🚧 Siemens TIA Portal parser
- 🚧 Schneider Electric parser
- 🚧 Beckhoff TwinCAT parser

## Can These Be Integrated?

**Theoretically, yes**. The Java Ignition module could:
1. Call Python parser via subprocess
2. Use parsed output to create tags

**Why not currently integrated:**
- Adds Python runtime dependency to module
- Increases module complexity
- Current Java parsing is sufficient
- Distribution becomes more complex

**For future v2.0:**
- Consider embedding Python parser as subprocess
- Or port Python parser logic to Java
- Or use Jython to run Python code in JVM

## Using the Python Parser

**Location**: `/modules/ignition-plc-simulator/plc-simulator-refactored/`

**Basic Usage:**
```python
from parsers.rockwell_l5k_parser import RockwellL5KParser
from exporters.ignition_exporter import IgnitionExporter

# Parse L5K file
parser = RockwellL5KParser()
project = parser.parse("myplc.L5K")

# Export to Ignition formats
exporter = IgnitionExporter(protocol="opcua", device_name="PLC1")
udts = exporter.export_udts(project)
tags = exporter.export_tags(project)
simulator_csv = exporter.export_simulator_csv(project)

# Save to files
with open("udts.json", "w") as f:
    f.write(udts)
with open("tags.json", "w") as f:
    f.write(tags)
with open("simulator.csv", "w") as f:
    f.write(simulator_csv)
```

**Import to Ignition:**
1. Designer → Tags → UDT Definitions → Import Tags → `udts.json`
2. Designer → Tags → Default → Import Tags → `tags.json`
3. Gateway → Devices → Programmable Device Simulator → Upload `simulator.csv`

## Documentation

Full documentation for the Python parser:
- **README.md**: Overview, features, quick start
- **ARCHITECTURE.md**: System design, data flow, components
- **IMPLEMENTATION_SUMMARY.md**: Development notes
- **TEST_RESULTS.md**: Test results and validation

## When to Use Each

**Use Ignition Module When:**
- You want live device simulation in Gateway
- You want standard device driver UX
- You don't need multi-protocol support
- You want native Ignition integration

**Use Python Parser When:**
- You need offline tag generation
- You want multi-protocol addressing (OPC-UA, Modbus, etc.)
- You need to generate files for import
- You want to add custom parsers easily
- You need to process files outside Ignition

## Future Considerations

**Option 1: Keep Separate**
- Maintain both projects independently
- Each serves different use cases
- No integration complexity

**Option 2: Integrate**
- Embed Python parser in Java module
- Use Python for parsing, Java for Ignition integration
- Requires bundling Python runtime

**Option 3: Port to Java**
- Rewrite Python parser logic in Java
- Fully native Java module
- No external dependencies

**Current Decision**: Keep separate for v1.x. Revisit for v2.0.

---

**Archived**: 2025-11-07
**Reason**: Reference implementation, not part of current Ignition module
**Status**: Functional but not actively developed
**Documentation**: Complete in original location
