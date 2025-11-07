# Installation Instructions for v1.0.5

## CRITICAL: Proper Installation Steps

The module v1.0.5 contains ALL the fixes, but Ignition may be caching the old module. Follow these steps EXACTLY:

### Step 1: Uninstall Old Module

1. In Gateway web interface: **Config → System → Modules**
2. Find "Enhanced PLC Simulator" (any version)
3. Click **Uninstall**
4. Wait for confirmation

### Step 2: Restart Gateway

```bash
docker restart ignition-python3-test
```

**WAIT** for gateway to fully restart (check logs):
```bash
docker logs -f ignition-python3-test
```

Look for: "Ignition successfully started"

### Step 3: Install v1.0.5

1. Go to: **Config → System → Modules**
2. Click **Install or Upgrade a Module**
3. Choose file: `EnhancedPLCSimulator-1.0.5.modl`
4. Click **Install**
5. **RESTART GATEWAY AGAIN**

```bash
docker restart ignition-python3-test
```

### Step 4: Verify Installation

1. Check **Config → System → Modules**
2. Find "Enhanced PLC Simulator"
3. **Verify version shows: 1.0.5**

### Step 5: Check Logs

```bash
docker logs ignition-python3-test | grep "Enhanced PLC Simulator"
```

You should see:
```
Enhanced PLC Simulator module starting...
Registered EnhancedSimulator resource bundle
Enhanced PLC Simulator module started successfully
```

### Step 6: Test Device Creation

1. Go to: **Config → Devices → Create New Device**
2. Look for device type in dropdown
3. **Should see: "Enhanced PLC Simulator"** (NOT "¿EnhancedSimulator.Meta.DisplayName?")

If you STILL see question marks, provide me with:
1. Screenshot of the modules page showing version
2. Gateway logs from `docker logs ignition-python3-test | tail -100`

## What's in v1.0.5

✅ Resource bundle registration code (BundleUtil.addBundle)
✅ Properties file with all display names
✅ Parser types: "Rockwell L5K", "JSON Format", "Siemens TIA Portal", etc.
✅ FormFieldType.FILE for file upload button

All verified in built module bytecode.
