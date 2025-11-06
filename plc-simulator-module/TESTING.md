# PLC Simulator Module - Testing Checklist

## Pre-Installation Testing

### ✓ Build Verification
- [ ] Module builds without errors: `./gradlew clean build`
- [ ] Module file exists: `build/PLCSimulator-1.0.0.unsigned.modl`
- [ ] Module size is ~12MB
- [ ] Build output shows no warnings

### ✓ Module Structure
- [ ] Unzip .modl and verify contents:
  ```bash
  unzip -l build/PLCSimulator-1.0.0.unsigned.modl
  ```
- [ ] Verify `module.xml` exists
- [ ] Verify `gateway.jar` contains embedded parser: `bin/plc-parser-service`
- [ ] Verify `designer.jar` exists
- [ ] Verify `common.jar` exists

## Installation Testing

### ✓ Gateway Installation
- [ ] Gateway is running (version 8.3.0+)
- [ ] Navigate to Config > System > Modules
- [ ] Click "Install or Upgrade a Module"
- [ ] Select `PLCSimulator-1.0.0.unsigned.modl`
- [ ] Installation starts without errors
- [ ] Gateway prompts for restart
- [ ] Gateway restarts successfully

### ✓ Post-Installation Verification
- [ ] Module appears in Modules list with "RUNNING" status
- [ ] Module info shows:
  - Name: "PLC Simulator"
  - Version: "1.0.0"
  - License: "Free"
  - Scopes: "GD" (Gateway + Designer)

## Module Startup Testing

### ✓ Log Verification
Check `wrapper.log` for successful startup:

- [ ] `PLC Simulator module setup`
- [ ] `Registered PLCSimSettings persistent record`
- [ ] `ManagedTagProvider 'PLCSimulator' created/retrieved`
- [ ] `Parser service started on localhost:5000`
- [ ] `Parser service started successfully`
- [ ] `Tag provider 'PLCSimulator' is ready`
- [ ] `Created sample tags`
- [ ] `Registered 9 write handlers`
- [ ] `Simulation engine started with 9 simulations`
- [ ] `PLC Simulator module started successfully`

**Expected log snippet:**
```
INFO  [PLCSimulator] PLC Simulator module setup
INFO  [PLCSimulator] Registered PLCSimSettings persistent record
INFO  [PLCSimulator] Loaded PLC Simulator settings from database
INFO  [PLCSimulator] ManagedTagProvider 'PLCSimulator' created/retrieved
INFO  [PLCSimulator] Parser service started on localhost:5000
INFO  [Parser] * Running on http://127.0.0.1:5000
INFO  [PLCSimulator] Parser service started successfully
INFO  [PLCSimulator] Tag provider 'PLCSimulator' is ready
INFO  [PLCSimulator] Creating sample PLC tag structure
INFO  [PLCSimulator] Sample tags created successfully
INFO  [PLCSimulator] Configured 9 simulations
INFO  [PLCSimulator] Registered 9 write handlers
INFO  [PLCSimulator] Simulation engine started with 9 simulations (update interval: 1000ms)
INFO  [PLCSimulator] PLC Simulator module started successfully
```

### ✓ Database Record Creation
- [ ] Check internal database for PLCSimSettings record:
  ```sql
  SELECT * FROM PLCSimSettings;
  ```
- [ ] Verify default values:
  - ParserHost: "localhost"
  - ParserPort: 5000
  - PersistTags: false
  - AllowTagCustomization: true
  - AutoStartSimulations: true
  - SimulationUpdateInterval: 1000
  - CreateSampleTags: true

## Tag Provider Testing

### ✓ Sample Tags Creation
Navigate to Gateway Config > Tags > Tag Browser

- [ ] Tag provider `[PLCSimulator]` exists
- [ ] Folder `Controller` exists
- [ ] Folder `Controller/Global` exists with 4 tags:
  - [ ] Motor1_Speed (Float4)
  - [ ] Motor1_Running (Boolean)
  - [ ] Tank1_Level (Float4)
  - [ ] Conveyor_Position (Int4)

- [ ] Folder `Program` exists
- [ ] Folder `Program/MainProgram` exists with 3 tags:
  - [ ] Counter (Int4)
  - [ ] Timer_Elapsed (Float4)
  - [ ] Alarm_Active (Boolean)

- [ ] Folder `Program/SafetyProgram` exists with 2 tags:
  - [ ] EmergencyStop (Boolean)
  - [ ] DoorOpen (Boolean)

**Total:** 9 sample tags

### ✓ Tag Value Updates
Watch tags in Tag Browser for 30 seconds:

- [ ] Motor1_Speed changes (sine wave, 0-100)
- [ ] Motor1_Running toggles every 10 seconds
- [ ] Tank1_Level increases linearly (0-100, 60s cycle)
- [ ] Conveyor_Position changes randomly (0-1000)
- [ ] Counter increases linearly (0-100, 20s cycle)
- [ ] Timer_Elapsed oscillates (sine wave, 0-10)
- [ ] Alarm_Active pulses briefly every 30 seconds
- [ ] EmergencyStop pulses briefly every 60 seconds
- [ ] DoorOpen toggles every 20 seconds

## Parser Service Testing

### ✓ Parser Health Check
From Script Console:

```python
status = system.plcsim.isParserRunning()
print("Parser running:", status)
```

- [ ] Returns `True`

### ✓ L5K File Parsing (if sample file available)
```python
result = system.plcsim.loadL5K("/path/to/test.L5K")
print(result)
```

Expected result:
- [ ] Returns success message: `"Success: Loaded test.L5K and created X tags"`
- [ ] New tags appear in Tag Browser under corresponding folders
- [ ] Log shows: `Created X tags`

### ✓ JSON File Parsing
```python
result = system.plcsim.loadJSON("/path/to/test.json")
print(result)
```

Expected result:
- [ ] Returns success message
- [ ] New tags created

### ✓ Error Handling - File Not Found
```python
result = system.plcsim.loadL5K("/nonexistent/file.L5K")
print(result)
```

- [ ] Returns: `"Error: File not found: /nonexistent/file.L5K"`
- [ ] Log shows warning (not error)

### ✓ Error Handling - Empty File
```python
result = system.plcsim.loadL5K("/path/to/empty.L5K")
print(result)
```

- [ ] Returns: `"Error: File is empty: /path/to/empty.L5K"`
- [ ] Log shows warning

## Simulation Engine Testing

### ✓ Add Sine Simulation
```python
result = system.plcsim.addSineSimulation("TestTag/Sine", 0.0, 100.0, 30.0)
print(result)
```

- [ ] Returns success message
- [ ] Create tag manually if needed
- [ ] Tag value oscillates between 0-100 over 30 seconds
- [ ] Log shows: `Added sine simulation to tag: TestTag/Sine`

### ✓ Add Ramp Simulation
```python
result = system.plcsim.addRampSimulation("TestTag/Ramp", 0.0, 100.0, 20.0)
print(result)
```

- [ ] Returns success message
- [ ] Tag value ramps from 0-100 over 20 seconds
- [ ] Value resets to 0 and ramps again

### ✓ Add Toggle Simulation
```python
result = system.plcsim.addToggleSimulation("TestTag/Toggle", 10.0)
print(result)
```

- [ ] Returns success message
- [ ] Boolean tag toggles every 5 seconds (50% duty cycle)

### ✓ Remove Simulation
```python
result = system.plcsim.removeSimulation("Controller/Global/Motor1_Speed")
print(result)
```

- [ ] Returns: `"Success: Removed simulation from tag 'Controller/Global/Motor1_Speed'"`
- [ ] Motor1_Speed stops changing
- [ ] Log shows: `Successfully removed simulation from tag`

### ✓ Clear All Simulations
```python
result = system.plcsim.clearAllSimulations()
print(result)
```

- [ ] Returns: `"Success: Cleared X simulations"`
- [ ] All tag values stop changing
- [ ] Log shows: `Cleared all simulations`

### ✓ Simulation Input Validation
```python
# Invalid tag path
result = system.plcsim.addSineSimulation("", 0, 100, 10)
```
- [ ] Returns: `"Error: Tag path cannot be null or empty"`

```python
# Invalid range (min >= max)
result = system.plcsim.addSineSimulation("TestTag", 100, 0, 10)
```
- [ ] Returns: `"Error: Minimum value must be less than maximum value"`

```python
# Invalid period (<=0)
result = system.plcsim.addSineSimulation("TestTag", 0, 100, 0)
```
- [ ] Returns: `"Error: Period must be greater than 0"`

## Write Handler Testing

### ✓ Manual Tag Write (with simulation active)
```python
# Write to tag with active simulation
system.tag.writeBlocking("[PLCSimulator]Controller/Global/Motor1_Speed", 50.0)

# Wait 2 seconds
system.util.sleep(2000)

# Read value
value = system.tag.readBlocking("[PLCSimulator]Controller/Global/Motor1_Speed")[0].value
print("Value after 2s:", value)
```

- [ ] Value is NOT 50.0 (simulation overwrote it)
- [ ] Log shows: `Tag write: [PLCSimulator]Controller/Global/Motor1_Speed = 50.0`
- [ ] Log shows: `Tag Controller/Global/Motor1_Speed has active simulation`

### ✓ Manual Tag Write (after removing simulation)
```python
# Remove simulation
system.plcsim.removeSimulation("Controller/Global/Motor1_Speed")

# Write value
system.tag.writeBlocking("[PLCSimulator]Controller/Global/Motor1_Speed", 75.0)

# Wait 2 seconds
system.util.sleep(2000)

# Read value
value = system.tag.readBlocking("[PLCSimulator]Controller/Global/Motor1_Speed")[0].value
print("Value after 2s:", value)
```

- [ ] Value is 75.0 (write persisted)
- [ ] Log shows write handler executed

## OPC-UA Testing

### ✓ OPC-UA Server Configuration
- [ ] Gateway Config > OPC UA > Server Settings
- [ ] OPC-UA server is enabled
- [ ] Endpoint: `opc.tcp://[gateway-ip]:62541/discovery`

### ✓ OPC-UA Client Browsing (UaExpert or similar)
- [ ] Connect OPC-UA client to Gateway endpoint
- [ ] Browse to Namespace 2 (or PLCSimulator namespace)
- [ ] Verify folder structure matches:
  ```
  PLCSimulator/
  ├── Controller/
  │   └── Global/
  │       ├── Motor1_Speed
  │       ├── Motor1_Running
  │       ├── Tank1_Level
  │       └── Conveyor_Position
  └── Program/
      ├── MainProgram/
      │   ├── Counter
      │   ├── Timer_Elapsed
      │   └── Alarm_Active
      └── SafetyProgram/
          ├── EmergencyStop
          └── DoorOpen
  ```

### ✓ OPC-UA Value Monitoring
- [ ] Subscribe to `PLCSimulator/Controller/Global/Motor1_Speed`
- [ ] Verify values update every 1 second
- [ ] Values match Tag Browser

### ✓ OPC-UA Write Operation
- [ ] Write value to `PLCSimulator/Controller/Global/Motor1_Speed` via OPC-UA client
- [ ] Verify write succeeds
- [ ] Check Gateway logs for write handler execution

## Persistence Testing

### ✓ Settings Persistence
1. Modify settings in database (if accessible):
   ```sql
   UPDATE PLCSimSettings SET CreateSampleTags = 0;
   ```

2. Restart Gateway

- [ ] Module loads with updated settings
- [ ] Sample tags are NOT created (CreateSampleTags = false)

### ✓ Last Loaded File Tracking
```python
# Load a file
system.plcsim.loadL5K("/data/test.L5K")

# Check database
# SELECT LastLoadedFile FROM PLCSimSettings;
```

- [ ] Database record updated with `/data/test.L5K`

### ✓ Restart Persistence
1. Restart Gateway
2. Check Tag Browser

- [ ] Tags still exist (if PersistTags = true)
- [ ] Simulations restart automatically
- [ ] Configuration persists

## Error Handling Testing

### ✓ Parser Service Failure
1. Kill parser service process:
   ```bash
   pkill -f plc-parser-service
   ```

2. Try to load L5K file:
   ```python
   result = system.plcsim.loadL5K("/data/test.L5K")
   print(result)
   ```

- [ ] Returns: `"Error: Parser service is not running"`
- [ ] Log shows ERROR level message
- [ ] Module doesn't crash

### ✓ Invalid L5K Content
Create a file with invalid content and try to load it

- [ ] Returns error message (not crash)
- [ ] Log shows parse error
- [ ] Module remains functional

### ✓ Tag Creation Failure
Attempt to create duplicate tags or invalid tag paths

- [ ] Error is logged
- [ ] Operation fails gracefully
- [ ] Module continues operating

## Performance Testing

### ✓ Large File Loading
Load an L5K file with 1000+ tags:

```python
import time
start = time.time()
result = system.plcsim.loadL5K("/data/large-plc.L5K")
elapsed = time.time() - start
print("Loaded in %.2f seconds" % elapsed)
```

- [ ] Completes in reasonable time (< 10 seconds for 1000 tags)
- [ ] All tags created successfully
- [ ] Gateway remains responsive

### ✓ High Simulation Count
Add 100 simulations:

```python
for i in range(100):
    system.plcsim.addSineSimulation("TestTag/Sim_%d" % i, 0, 100, 10 + i)
```

- [ ] All simulations run
- [ ] CPU usage remains reasonable (< 10%)
- [ ] Gateway remains responsive
- [ ] Simulations update at correct interval

### ✓ Memory Usage
Monitor Gateway memory before/after:
1. Initial memory usage
2. Load 1000 tags
3. Add 100 simulations
4. Run for 10 minutes

- [ ] Memory usage increases but stabilizes
- [ ] No memory leaks (memory doesn't continuously grow)

## Scripting API Testing

### ✓ getStatus()
```python
status = system.plcsim.getStatus()
print(status)
```

Expected output:
```
PLC Simulator Status:
  Tag Provider: [PLCSimulator]
  Tag Count: 0
  Parser Running: True
```

- [ ] Returns status string
- [ ] Parser status is correct

### ✓ All Scripting Functions Documented
- [ ] loadL5K
- [ ] loadJSON
- [ ] getStatus
- [ ] getTagCount
- [ ] isParserRunning
- [ ] removeSimulation
- [ ] addSineSimulation
- [ ] addRampSimulation
- [ ] addToggleSimulation
- [ ] clearAllSimulations

## Uninstallation Testing

### ✓ Clean Uninstall
1. Stop any active simulations
2. Navigate to Gateway Config > Modules
3. Select PLC Simulator module
4. Click "Uninstall"
5. Restart Gateway

- [ ] Module uninstalls without errors
- [ ] Parser service stops
- [ ] Tags are removed (if not persisted)
- [ ] Database records remain (for reinstall)
- [ ] No errors in wrapper.log

### ✓ Reinstallation
1. Reinstall module
2. Restart Gateway

- [ ] Module installs successfully
- [ ] Previous settings are restored from database
- [ ] Parser service starts
- [ ] Sample tags created (if enabled)

## Documentation Testing

### ✓ README.md Accuracy
- [ ] All features listed in README actually work
- [ ] All code examples execute without errors
- [ ] API reference matches actual function signatures
- [ ] Troubleshooting steps resolve common issues

### ✓ BUILD.md Accuracy
- [ ] Build instructions work as written
- [ ] Module builds successfully following steps
- [ ] Signing instructions are accurate (if tested)

## Final Checklist

### ✓ Production Readiness
- [ ] No ERROR level logs during normal operation
- [ ] All features tested and working
- [ ] Performance is acceptable
- [ ] Error handling is comprehensive
- [ ] Documentation is complete and accurate
- [ ] Module can be cleanly installed/uninstalled
- [ ] Settings persist across restarts
- [ ] OPC-UA integration works correctly

### ✓ Known Issues
Document any issues found:
- Issue 1: ...
- Issue 2: ...

### ✓ Testing Sign-Off
- Tester Name: _______________
- Date: _______________
- Gateway Version: _______________
- Module Version: _______________
- Test Result: PASS / FAIL (circle one)

## Notes
_Use this space for additional testing notes, edge cases, or observations:_
