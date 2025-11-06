# PLC OPC-UA Simulator Server

A standalone Python OPC-UA server that creates hierarchical PLC simulations from code files (L5K, etc.) with proper UDT folder structure matching real PLCs.

## Problem Solved

The Ignition Programmable Device Simulator uses CSV format which creates flat tag structures like:
```
Controller:Global/AI_DOP201_DATA.EnableIn
Controller:Global/AI_DOP201_DATA.Raw
```

This OPC-UA server creates proper hierarchical folders matching real PLCs:
```
Controller:Global/
  └── AI_DOP201_DATA/
      ├── EnableIn
      ├── Raw
      └── ... (all members in folder)
```

## Features

- ✅ Hierarchical UDT folder structure (matches real PLCs)
- ✅ **Multi-vendor parser support**:
  - Rockwell L5K files
  - JSON tag definitions (vendor-agnostic)
  - Extensible for Siemens, Beckhoff, etc.
- ✅ Full OPC-UA browsing support
- ✅ **Dynamic value simulation** (ramp, sine, toggle, random, noise, pulse)
- ✅ **YAML configuration** for multiple PLCs
- ✅ **Hot reload** - watches for file changes
- ✅ **Pattern-based simulation rules** - configure simulation by tag patterns
- ✅ Initial values from PLC code
- ✅ File logging with rotation

## Installation

```bash
cd plc-opcua-server
pip install -r requirements.txt
```

## Quick Start

1. **Create configuration:**
```bash
# Copy example config
cp config.yaml.example config.yaml

# Edit config.yaml to point to your L5K file
```

2. **Start the server:**
```bash
# Using the run script
./run.sh

# Or directly with Python
venv/bin/python opcua_simulator.py
```

3. **Connect from Ignition:**
   - Open Ignition Gateway
   - Config → OPC UA → Connections
   - Add Connection:
     - Name: "Simulated DemoWWTP PLC"
     - Endpoint URL: `opc.tcp://localhost:4840/plc-simulator/`
   - Save and enable
   - Browse to: `Devices/[DemoWWTP]/Controller:Global`

3. **You should see:**
```
[DemoWWTP]/
  ├── [Diagnostics]/
  ├── Controller:Global/
  │   ├── AI_DOP201_DATA/          ← UDT Folder
  │   │   ├── EnableIn
  │   │   ├── EnableOut
  │   │   ├── Raw
  │   │   └── ... (38 members)
  │   ├── AV101/                    ← UDT Folder
  │   │   ├── OpnFb
  │   │   ├── ClsFb
  │   │   └── ... (40 members)
  │   └── ... (all controller tags)
  └── Program:MainProgram/
      └── ... (program tags)
```

## Configuration

Edit `config.yaml` to configure PLCs and simulation:

```yaml
server:
  endpoint: "opc.tcp://0.0.0.0:4840/plc-simulator/"
  name: "PLC Simulator OPC-UA Server"
  hot_reload: true
  reload_interval: 5

plcs:
  - name: "DemoWWTP"
    enabled: true
    file: "../DemoWWTP-sample-a.L5K"
    parser: "rockwell"
    simulation:
      enabled: true
      update_interval: 1.0
      rules:
        # Per-tag simulation
        "AI_DOP201_DATA.PV":
          type: "ramp"
          min: 0
          max: 100
          step: 0.5

        "AI_PLS102_DATA.PV":
          type: "sine"
          min: 0
          max: 100
          period: 60

        # Pattern matching
        "DI_*.Value":
          type: "toggle"
          interval: 10

        "*.Running":
          type: "random_bool"
          probability: 0.7

logging:
  level: "INFO"
  file:
    enabled: true
    path: "opcua_simulator.log"
```

### Simulation Types

1. **static** - No changes (uses initial value)
2. **ramp** - Linear increase/decrease
   - `min`, `max`, `step`, `reverse`
3. **sine** - Sine wave
   - `min`, `max`, `period`, `phase`
4. **toggle** - Boolean toggle
   - `interval` (seconds)
5. **random_bool** - Random boolean
   - `probability` (0 to 1)
6. **random** - Random numbers
   - `min`, `max`
7. **noise** - Gaussian noise
   - `amplitude`, `min`, `max`
8. **pulse** - Square wave
   - `period`, `duty_cycle`

## JSON Tag Definitions

For vendor-agnostic PLC simulation, you can define tags in JSON format:

```json
{
  "name": "MyPLC",
  "udts": [
    {
      "name": "Motor",
      "members": [
        {"name": "Running", "data_type": "BOOL", "initial_value": false},
        {"name": "Speed", "data_type": "REAL", "initial_value": 0.0},
        {"name": "Amps", "data_type": "REAL", "initial_value": 0.0}
      ]
    }
  ],
  "global_tags": [
    {"name": "Temperature", "data_type": "REAL", "initial_value": 25.0},
    {"name": "Pressure", "data_type": "REAL", "initial_value": 14.7},
    {"name": "Motor1", "data_type": "Motor"},
    {"name": "Motor2", "data_type": "Motor"}
  ],
  "programs": [
    {
      "name": "MainProgram",
      "tags": [
        {"name": "Counter", "data_type": "INT", "initial_value": 0}
      ]
    }
  ]
}
```

**Supported Data Types:**
- `BOOL` - Boolean
- `INT`, `INT2` - 16-bit integer
- `DINT`, `INT4` - 32-bit integer
- `REAL`, `FLOAT4` - 32-bit float
- `STRING` - String
- Custom UDT names (e.g., `Motor`)

**Configuration:**
```yaml
plcs:
  - name: "TestPLC"
    enabled: true
    file: "my_plc.json"
    parser: "json"
```

**Use Cases:**
- Quick prototyping without real PLC code
- Testing HMI screens with custom tag structures
- Vendor-agnostic integration testing
- Creating training/demo environments

See `example_plc.json` and `simple_plc.json` for complete examples.

## Architecture

```
PLC Code File (L5K)
    ↓
RockwellL5KParser (existing)
    ↓
PLCProject Model (existing)
    ↓
OPC-UA Address Space Builder (new)
    ↓
Python OPC-UA Server (asyncua)
    ↓
Ignition OPC-UA Connection
```

## Development Status

### Phase 1: Core OPC-UA Server ✅ COMPLETE
- [x] Basic OPC-UA server setup
- [x] Hierarchical address space builder
- [x] RockwellL5KParser integration
- [x] Proof of concept with DemoWWTP.L5K

### Phase 2: Enhanced Features ✅ COMPLETE
- [x] Dynamic value simulation (ramp, sine, toggle, random, noise, pulse)
- [x] Hot reload on file changes
- [x] Multiple PLC instances
- [x] YAML configuration file
- [x] Pattern-based simulation rules
- [x] File logging with rotation

### Phase 3: Multi-Vendor Support ✅ COMPLETE
- [x] JSON input parser
- [x] Example JSON tag definitions
- [x] Multi-vendor configuration support
- [ ] Siemens TIA parser (template exists, needs sample files)
- [ ] Beckhoff parser (template exists, needs sample files)

### Phase 4: Production Deployment (Future)
- [ ] systemd service configuration
- [ ] Docker container
- [ ] Health monitoring endpoints
- [ ] Web dashboard for monitoring

## Testing

Test the server without Ignition:

```bash
# Install OPC-UA client tools
pip install opcua-client

# Browse the server
opcua-client browse opc.tcp://localhost:4840/plc-simulator/
```

## Troubleshooting

**Server won't start:**
- Check port 4840 is not in use: `lsof -i :4840`
- Check Python version: `python --version` (needs 3.8+)

**Ignition can't connect:**
- Verify server is running
- Check firewall allows port 4840
- Try endpoint: `opc.tcp://[server-ip]:4840/plc-simulator/`

**Tags not appearing:**
- Check server logs for errors
- Verify L5K file parsed successfully
- Check namespace index in logs

## Comparison to Current CSV Solution

| Feature | CSV Simulator | OPC-UA Server |
|---------|--------------|---------------|
| UDT Folders | ❌ Flat tags | ✅ Hierarchical |
| Structure Match | ❌ Different | ✅ Exact match |
| HMI Testing | ⚠️ Awkward | ✅ Perfect |
| External Access | ❌ Internal only | ✅ Any OPC client |
| Setup | Easy (CSV import) | Medium (OPC connection) |
| Performance | Fast | Fast |

## License

Same as parent project.

## Credits

Built on top of the existing PLC parser framework in `plc-simulator-refactored/`.
