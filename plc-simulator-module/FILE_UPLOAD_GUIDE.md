# File Upload Feature - v1.2.0

The Enhanced PLC Simulator v1.2.0 includes a working file upload feature that automatically appears when configuring devices.

## 🎉 What's New

The file upload UI is automatically injected into device configuration pages via mounted JavaScript resources. No manual activation required!

## How It Works

### Automatic Injection

When you navigate to a device configuration page for an Enhanced PLC Simulator device, the JavaScript file (`plc-file-upload.js`) is automatically loaded from:

```
/res/plcsimulator/plc-file-upload.js
```

This script:
1. Detects the "PLC File Content" textarea
2. Injects an "📁 Upload PLC File" button above it
3. Provides file selection and reading functionality

### Manual Activation (if needed)

If the upload button doesn't appear automatically, you can manually activate it by visiting:

```
http://localhost:9088/res/plcsimulator/enable-file-upload.html
```

This page will:
1. Load the file upload JavaScript
2. Attempt to inject it into any open device configuration windows
3. Provide instructions

## Using the File Upload Feature

1. **Navigate to Device Configuration**:
   - Go to `Config → Devices`
   - Click "Create new device" or edit an existing one
   - Select "Enhanced PLC Simulator" as the device type

2. **Look for the Upload Button**:
   - Above the "PLC File Content" textarea, you should see a blue button: **📁 Upload PLC File**

3. **Upload a File**:
   - Click the "📁 Upload PLC File" button
   - Select your PLC file (L5K, JSON, CSV, or XML)
   - The file content will automatically populate the textarea
   - The filename will be populated in the "File Name" field (if empty)

4. **Visual Feedback**:
   - ⏳ "Reading file..." - File is being loaded
   - ✅ "Loaded: filename.L5K (250.3 KB)" - Success!
   - ❌ "Error reading file" - Something went wrong

5. **Save the Device**:
   - Click "Save" to create/update the device configuration
   - The file will be saved to the Gateway filesystem at:
     ```
     /usr/local/bin/ignition/data/plc-simulator/{deviceName}/
     ```

## Supported File Types

- **Rockwell L5K** - `.l5k`, `.L5K`
- **JSON** - `.json`, `.JSON`
- **CSV** - `.csv`, `.CSV`
- **XML** - `.xml`, `.XML`

## Technical Details

### Web Resources

The module mounts web resources at `/res/plcsimulator/` via:
- `getMountedResourceFolder()` → `"mounted"`
- `getMountPathAlias()` → `"plcsimulator"`

### HTTP Endpoints

File upload routes are mounted at `/main/data/plcsimulator/`:
- **POST** `/main/data/plcsimulator/upload` - File upload endpoint
- **GET** `/main/data/plcsimulator/health` - Health check endpoint

### JavaScript Implementation

The `plc-file-upload.js` script:
- Uses `FileReader` API for client-side file reading
- Automatically finds the textarea by label text
- Dispatches `change` and `input` events to notify Ignition
- Auto-populates the "File Name" field if empty
- Provides visual feedback for loading/success/error states

## Troubleshooting

### Upload Button Not Appearing

**Symptom**: No "📁 Upload PLC File" button above the textarea

**Solutions**:
1. Check browser console (F12) for errors
2. Verify JavaScript is loading:
   ```
   http://localhost:9088/res/plcsimulator/plc-file-upload.js
   ```
   Should return JavaScript code, not a 404 error

3. Manually activate via:
   ```
   http://localhost:9088/res/plcsimulator/enable-file-upload.html
   ```

4. Check Gateway logs for route mounting:
   ```
   docker logs ignition-gateway | grep "File upload routes"
   ```
   Should show: "File upload routes mounted at /main/data/plcsimulator/"

### File Upload Failing

**Symptom**: Click upload button, select file, but nothing happens

**Solutions**:
1. Check browser console (F12) for JavaScript errors
2. Verify file size (extremely large files may cause issues)
3. Check file encoding (should be UTF-8 text)

### Web Resources Not Accessible

**Symptom**: 404 errors when accessing `/res/plcsimulator/*`

**Solutions**:
1. Verify module version is v1.2.0 or higher:
   - Go to `Config → System → Modules`
   - Find "Enhanced PLC Simulator"
   - Version should be 1.2.0

2. Check Gateway logs for mounting errors:
   ```
   docker logs ignition-gateway | grep -i "mount\|resource"
   ```

3. Restart the Gateway:
   ```
   docker restart ignition-gateway
   ```

## Version History

### v1.2.0 (Current)
- ✅ **File upload working** - Client-side JavaScript injection
- ✅ Web resources properly mounted
- ✅ HTTP routes for file upload endpoints
- ✅ Automatic UI injection on device config pages

### v1.1.0 (Previous)
- ❌ File upload attempted but not functional
- ❌ Web resource mounting not supported in AbstractDeviceModuleHook

### v1.0.10
- ✅ Fixed parser dropdown enum display
- ✅ Display name showing correctly

## Architecture

```
SimulatorModuleHook (AbstractDeviceModuleHook)
├── mountRouteHandlers() ────→ FileUploadRoutes
│   └── /main/data/plcsimulator/upload
│   └── /main/data/plcsimulator/health
├── getMountedResourceFolder() ─→ "mounted/"
│   ├── plc-file-upload.js
│   └── enable-file-upload.html
└── getMountPathAlias() ────────→ "plcsimulator"
    └── /res/plcsimulator/*
```

## For Developers

### Adding New Routes

Edit `FileUploadRoutes.java`:

```java
routes.newRoute("/your-route")
    .handler(this::yourHandler)
    .mount();
```

### Adding New Web Resources

1. Place file in `gateway/src/main/resources/mounted/`
2. Access at `/res/plcsimulator/your-file.js`

### Testing Routes

```bash
# Health check
curl http://localhost:9088/main/data/plcsimulator/health

# Response: {"status":"ok","service":"plc-file-upload"}
```

## See Also

- [README.md](README.md) - Project overview
- [DEVELOPMENT.md](DEVELOPMENT.md) - Development guide
- [KNOWN_ISSUES.md](KNOWN_ISSUES.md) - Current limitations
- [CHANGELOG.md](CHANGELOG.md) - Version history
