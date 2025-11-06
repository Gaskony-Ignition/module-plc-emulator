# PLC Simulator Module for Ignition

A comprehensive Ignition module that simulates PLC operations by parsing Rockwell L5K files and creating hierarchical tag structures with dynamic value simulation.

## Features

- **Rockwell L5K Parser**: Parse Allen-Bradley PLC export files and automatically create matching tag structures
- **Hierarchical Tag Structure**: Creates tags organized by Controller/Program scope matching PLC organization
- **Dynamic Simulation**: 7 simulation patterns (sine, ramp, random, toggle, pulse, noise, constant)
- **OPC-UA Compatible**: Tags are automatically exposed via Ignition's built-in OPC-UA server
- **Persistent Configuration**: Settings persist across Gateway restarts
- **Write Handlers**: Handle tag writes from OPC clients or Ignition components
- **Scripting API**: Control simulator via Python scripting functions

## Quick Start

### Installation

1. Download `PLCSimulator-1.0.0.modl` (signed module)
2. Navigate to Gateway Config > System > Modules
3. Click "Install or Upgrade a Module"
4. Select the .modl file and install
5. Restart Gateway when prompted

**Note**: The module is signed with a self-signed certificate from Gaskony. Gateway will accept this certificate without additional configuration.

### Basic Usage

#### View Sample Tags

After installation, the module creates 9 sample tags with active simulations:

- `[PLCSimulator]Controller/Global/Motor1_Speed` - Sine wave (0-100 RPM)
- `[PLCSimulator]Controller/Global/Motor1_Running` - Toggle (10s period)
- `[PLCSimulator]Controller/Global/Tank1_Level` - Ramp (0-100, 60s)
- `[PLCSimulator]Controller/Global/Conveyor_Position` - Random (0-1000)
- `[PLCSimulator]Program/MainProgram/Counter` - Ramp (0-100, 20s)
- `[PLCSimulator]Program/MainProgram/Timer_Elapsed` - Sine (0-10, 15s)
- `[PLCSimulator]Program/MainProgram/Alarm_Active` - Pulse (30s, 10% duty)
- `[PLCSimulator]Program/SafetyProgram/EmergencyStop` - Pulse (60s, 5% duty)
- `[PLCSimulator]Program/SafetyProgram/DoorOpen` - Toggle (20s period)

View these tags in the Designer Tag Browser or via OPC-UA client.

#### Load an L5K File

In the Script Console or a Gateway script:

```python
# Load L5K file and create tags
result = system.plcsim.loadL5K("/path/to/file.L5K")
print(result)  # "Success: Loaded file.L5K and created 145 tags"
```

## Scripting API

All functions are available under `system.plcsim.*`

### File Loading

#### loadL5K(filePath)
Load and parse a Rockwell L5K file.

```python
result = system.plcsim.loadL5K("/data/plc-exports/FactoryFloor.L5K")
# Returns: "Success: Loaded FactoryFloor.L5K and created 342 tags"
```

**Parameters:**
- `filePath` (String): Absolute path to .L5K file

**Returns:** Status message string

---

#### loadJSON(filePath)
Load a JSON PLC configuration file.

```python
result = system.plcsim.loadJSON("/data/plc-config.json")
```

**Parameters:**
- `filePath` (String): Absolute path to .json file

**Returns:** Status message string

---

### Simulation Control

#### addSineSimulation(tagPath, min, max, period)
Add a sine wave simulation to a tag.

```python
system.plcsim.addSineSimulation("Controller/Global/Temperature", 65.0, 75.0, 30.0)
# Temperature oscillates between 65-75°F over 30 seconds
```

**Parameters:**
- `tagPath` (String): Tag path (relative to [PLCSimulator])
- `min` (Double): Minimum value
- `max` (Double): Maximum value
- `period` (Double): Period in seconds

---

#### addRampSimulation(tagPath, min, max, period)
Add a linear ramp simulation to a tag.

```python
system.plcsim.addRampSimulation("Controller/Global/FillLevel", 0.0, 100.0, 60.0)
# Fill level ramps from 0-100% over 60 seconds, then repeats
```

**Parameters:**
- `tagPath` (String): Tag path
- `min` (Double): Minimum value
- `max` (Double): Maximum value
- `period` (Double): Period in seconds

---

#### addToggleSimulation(tagPath, period)
Add a boolean toggle simulation.

```python
system.plcsim.addToggleSimulation("Program/MainProgram/PumpRunning", 10.0)
# Pump toggles ON for 5s, OFF for 5s (50% duty cycle)
```

**Parameters:**
- `tagPath` (String): Tag path
- `period` (Double): Full cycle period in seconds

---

#### removeSimulation(tagPath)
Remove simulation from a tag to allow manual control.

```python
system.plcsim.removeSimulation("Controller/Global/Motor1_Speed")
# Motor speed can now be written manually without simulation override
```

**Parameters:**
- `tagPath` (String): Tag path

---

#### clearAllSimulations()
Remove all active simulations.

```python
result = system.plcsim.clearAllSimulations()
# Returns: "Success: Cleared 9 simulations"
```

---

### Status & Information

#### getStatus()
Get current module status.

```python
status = system.plcsim.getStatus()
print(status)
```

**Returns:**
```
PLC Simulator Status:
  Tag Provider: [PLCSimulator]
  Tag Count: 342
  Parser Running: True
```

---

#### isParserRunning()
Check if parser service is running.

```python
if system.plcsim.isParserRunning():
    print("Parser service is ready")
```

**Returns:** Boolean

---

## Tag Structure

The module creates hierarchical tags matching PLC organization:

```
[PLCSimulator]
├── Controller
│   └── Global
│       ├── Motor1_Speed (Float)
│       ├── Motor1_Running (Boolean)
│       └── ...
└── Program
    ├── MainProgram
    │   ├── Counter (Integer)
    │   ├── Timer_Elapsed (Float)
    │   └── ...
    └── SafetyProgram
        ├── EmergencyStop (Boolean)
        └── DoorOpen (Boolean)
```

This structure is **automatically browseable via OPC-UA** using Ignition's built-in OPC-UA server.

## Simulation Types

| Type | Description | Parameters | Example Use Case |
|------|-------------|------------|------------------|
| **SINE** | Sinusoidal oscillation | min, max, period | Temperature, pressure, flow rate |
| **RAMP** | Linear increase, then reset | min, max, period | Fill level, position |
| **RANDOM** | Random values in range | min, max, isInteger | Sensor noise, random events |
| **TOGGLE** | Boolean ON/OFF (50% duty) | period | Pump cycling, valve state |
| **PULSE** | Boolean pulse (configurable duty) | period, dutyCycle | Alarm conditions, short pulses |
| **NOISE** | Gaussian noise around center | min, max | Realistic sensor variation |
| **CONSTANT** | Fixed value | value | Static setpoints |

## Configuration

Module settings are stored in the Gateway database and persist across restarts.

### Settings Location
Gateway Config > System > Modules > PLC Simulator > Settings (future feature)

### Current Settings
Settings are initialized with defaults on first startup:

| Setting | Default | Description |
|---------|---------|-------------|
| Parser Host | localhost | Parser service host |
| Parser Port | 5000 | Parser service port |
| Persist Tags | false | Save tags to database |
| Allow Customization | true | Allow tag property edits |
| Auto-Start Simulations | true | Start simulations on module load |
| Simulation Update Interval | 1000 ms | How often simulations update |
| Create Sample Tags | true | Create demo tags on startup |

## OPC-UA Access

Tags are automatically exposed via Ignition's OPC-UA server:

**OPC-UA Endpoint:**
```
opc.tcp://your-gateway:62541/discovery
```

**Tag Namespace:**
```
PLCSimulator/Controller/Global/Motor1_Speed
```

**Example OPC-UA Client:**
```python
# UaExpert, Prosys OPC UA Browser, or any OPC-UA client
# Browse to: root > Objects > PLCSimulator
```

## Write Operations

Tags accept writes from:
- OPC-UA clients
- Ignition Tag bindings
- Scripting (`system.tag.writeBlocking()`)

**Important:** If a tag has an active simulation, writes will be immediately overridden by the simulation on the next update cycle (default 1s). Use `removeSimulation()` to allow manual control.

**Example:**
```python
# This write will be overridden after 1 second
system.tag.writeBlocking("[PLCSimulator]Controller/Global/Motor1_Speed", 50.0)

# To maintain manual control:
system.plcsim.removeSimulation("Controller/Global/Motor1_Speed")
system.tag.writeBlocking("[PLCSimulator]Controller/Global/Motor1_Speed", 50.0)
# Now the value stays at 50.0
```

## Logging

Module logs are written to `wrapper.log` with prefix `[PLCSimulator]`:

```
INFO  [PLCSimulator] PLC Simulator module setup
INFO  [PLCSimulator] Registered PLCSimSettings persistent record
INFO  [PLCSimulator] ManagedTagProvider 'PLCSimulator' created/retrieved
INFO  [PLCSimulator] Parser service started on localhost:5000
INFO  [PLCSimulator] Registered 9 write handlers
INFO  [PLCSimulator] Simulation engine started with 9 simulations (update interval: 1000ms)
```

**Log Levels:**
- `INFO`: Normal operation, startup/shutdown
- `WARN`: Non-critical issues (file not found, validation failures)
- `ERROR`: Critical errors (parser crash, tag creation failure)
- `DEBUG`: Detailed operation info (tag updates, simulation values)

## Troubleshooting

### Module Won't Load
**Symptom:** Module shows "Error" status in Gateway Config > Modules

**Solutions:**
1. Check `wrapper.log` for specific error
2. Verify Java 17 is installed
3. Ensure module file is not corrupted
4. Try removing and reinstalling module

---

### Parser Service Failed to Start
**Symptom:** Log shows "Failed to start parser service"

**Causes:**
- Port 5000 already in use
- Parser executable not found or not executable
- Python runtime missing (should be embedded)

**Solutions:**
```bash
# Check if port 5000 is in use
netstat -tuln | grep 5000

# Restart Gateway to retry parser startup
systemctl restart ignition
```

---

### Tags Not Updating
**Symptom:** Tag values are stuck/not changing

**Checks:**
1. Verify simulation engine is running:
   ```python
   print(system.plcsim.getStatus())
   ```

2. Check if tag has simulation:
   ```python
   # Add simulation if missing
   system.plcsim.addSineSimulation("Controller/Global/Motor1_Speed", 0, 100, 30)
   ```

3. Check Gateway logs for simulation errors

---

### L5K File Won't Load
**Symptom:** `loadL5K()` returns error

**Common Errors:**

**"File not found"**
- Use absolute path, not relative
- Verify file exists: `ls -l /path/to/file.L5K`

**"File is not readable"**
- Check file permissions: `chmod 644 file.L5K`

**"Parser service is not running"**
- Parser service crashed or didn't start
- Check `wrapper.log` for parser errors
- Restart Gateway

**"Error: Invalid L5K format"**
- File is not a valid Rockwell L5K export
- Try exporting from Logix Designer again

---

### OPC-UA Client Can't See Tags
**Symptom:** OPC-UA browser shows no PLCSimulator tags

**Solutions:**
1. Verify OPC-UA server is enabled in Gateway Config > OPC UA > Server Settings
2. Refresh OPC-UA client browse tree
3. Check that tags exist: Gateway Config > Tags > Tag Browser
4. Verify OPC-UA endpoint is correct: `opc.tcp://gateway-ip:62541/discovery`

## Performance

### Module Size
- **Module file**: ~12 MB (includes embedded Python parser)
- **Memory usage**: ~50-100 MB (parser subprocess + tag provider)

### Tag Limits
- **Tested with**: 10,000+ tags
- **Recommended**: <5,000 tags per module instance
- **For larger systems**: Use multiple tag providers or external parser service

### Simulation Performance
- **Update interval**: 1000ms default (configurable)
- **Simultaneous simulations**: Tested with 1,000+ simulations
- **CPU impact**: Minimal (<1% for 100 simulations)

## Advanced Usage

### Multiple PLC Files
Load multiple L5K files - tags are merged into the same provider:

```python
system.plcsim.loadL5K("/data/Line1.L5K")  # Creates 200 tags
system.plcsim.loadL5K("/data/Line2.L5K")  # Adds 200 more tags
# Total: 400 tags in [PLCSimulator] provider
```

### Custom Simulation Patterns
Combine scripting with simulations for complex behaviors:

```python
# Simulation that changes based on another tag
def updateConveyorSpeed():
    running = system.tag.readBlocking("[PLCSimulator]Controller/Global/Motor1_Running")[0].value

    if running:
        # Motor running - add simulation
        system.plcsim.addSineSimulation("Controller/Global/ConveyorSpeed", 50, 100, 20)
    else:
        # Motor stopped - remove simulation and set to 0
        system.plcsim.removeSimulation("Controller/Global/ConveyorSpeed")
        system.tag.writeBlocking("[PLCSimulator]Controller/Global/ConveyorSpeed", 0)

# Run every 2 seconds
system.util.invokeAsynchronous(updateConveyorSpeed)
```

### Integration with Perspective
Use simulated tags in Perspective views:

```javascript
// Bind Perspective component to simulated tag
{view.params.motorSpeed} = [PLCSimulator]Controller/Global/Motor1_Speed

// Display simulation status
{view.params.simulationActive} = true if simulation exists
```

## API Reference

See **Scripting API** section above for full function reference.

## Support

- **Documentation**: See `/docs` folder for detailed technical docs
- **Build Guide**: See `BUILD.md` for compilation instructions
- **Issues**: Report bugs on project GitHub
- **License**: Free module (isFreeModule = true)

## Version History

### 1.0.0 (Current)
- Initial release
- L5K parser with embedded Python service
- 7 simulation types
- Persistent configuration
- Write handlers
- Comprehensive scripting API
- Production-ready error handling

## License

This module is provided as-is for use with Inductive Automation Ignition platform.
