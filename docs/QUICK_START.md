# Quick Start Guide - Logix PLC Emulator

## How to Upload PLC Files (3 Methods)

### Method 1: Edit Program Page (RECOMMENDED)
**Direct drag-and-drop interface**

1. **Navigate to the Edit Program page:**
   ```
   http://localhost:8088/res/logixemulator/edit-program.html
   ```
   *Replace `localhost:8088` with your Gateway address*

2. **Drag and drop** your L5K, JSON, CSV, or XML file onto the upload zone

3. **Done!** The file will be processed and tags created

**Bookmark this URL for easy access!**

---

### Method 2: Device Configuration Form
**Upload button in device settings**

1. Go to **Gateway → Config → OPC UA → Device Connections**
2. Click **"Edit"** on your Logix PLC Emulator device
3. Look for the **"📁 Upload PLC File"** button above the "PLC File Content" field
4. Click the button and select your file
5. File content auto-populates the textarea
6. Click **"Save"** to apply

**Note:** If you don't see the upload button, the JavaScript may not have loaded. Use Method 1 or 3 instead.

---

### Method 3: Copy & Paste
**Traditional method - always works**

1. Open your PLC file (L5K, JSON, CSV, XML) in a text editor
2. Select all content (**Ctrl+A** / **Cmd+A**)
3. Copy (**Ctrl+C** / **Cmd+C**)
4. Go to device configuration
5. Paste into the **"PLC File Content"** textarea (**Ctrl+V** / **Cmd+V**)
6. Enter the filename in **"File Name"** field (e.g., `myplc.L5K`)
7. Click **"Save"**

---

## Why "Edit Program" Isn't in the Device Menu

**Technical Limitation:** The Ignition SDK **does not provide a public API** to add custom menu items to the device connection dropdown (the three-dot menu).

The original "Programmable Device Simulator" has an "Edit Program" menu item because it's a **built-in Ignition module** with access to internal/private APIs that third-party modules cannot use.

**Workaround:** We provide the Edit Program page as a standalone URL that you can bookmark.

---

## Supported File Formats

| Format | Extension | Description |
|--------|-----------|-------------|
| **Rockwell L5K** | `.l5k`, `.L5K` | Allen-Bradley RSLogix 5000 / Studio 5000 export files |
| **JSON** | `.json`, `.JSON` | Custom JSON tag definition format |
| **CSV** | `.csv`, `.CSV` | Comma-separated tag list |
| **Rockwell L5X** | `.l5x`, `.L5X` | Allen-Bradley Studio 5000 XML export files |

---

## Step-by-Step: First Time Setup

### 1. Install the Module
- Navigate to **Gateway → Config → System → Modules**
- Click **"Install or Upgrade a Module"**
- Upload `LogixPLCEmulator-9.2.2.modl`
- Restart Gateway if prompted

### 2. Create Device(s)
- Navigate to **Gateway → Config → OPC UA → Device Connections**
- Click **"Create Device Connection"**
- Select **"Logix PLC Emulator"** from the dropdown
- Enter a device name (e.g., `Building1_PLC`, `TestRig_Simulator`)
- Click **"Create New Device"**

**💡 Multiple Devices:** You can create multiple Logix PLC Emulator instances!
- Each device is independent
- Give them meaningful names (e.g., `MainBuilding_PLC`, `EmergencyGenerator_PLC`)
- Each can simulate a different PLC with different tags
- Perfect for testing multi-device integrations

### 3. Upload a PLC File
Choose one of the three methods above.

**Recommended:** Bookmark the Edit Program page URL for quick access:
```
http://YOUR_GATEWAY:8088/res/logixemulator/edit-program.html
```

### 4. Apply File to Specific Device
After uploading a file:
- Go to **Config → OPC UA → Device Connections**
- Click **"Edit"** on the device you want to update
- Paste file content into **"PLC File Content"** field
- Save the device

**For multiple devices:** Repeat step 4 for each device, using the same or different files.

### 5. Verify Tags Were Created
- Navigate to **Gateway → Config → OPC UA → Device Connections**
- Your device should show status: **"Running"** with a tag count
- Tags are available at: `[ns=1;s=DeviceName]/Controller:Global/TagName`

---

## Troubleshooting

### "Upload PLC File" Button Not Appearing

**Cause:** JavaScript injection may not be working in your Ignition version.

**Solutions:**
1. Use **Method 1** (Edit Program page) - Always works
2. Use **Method 3** (Copy & Paste) - Always works
3. Check browser console for errors (F12 → Console tab)
4. Verify JavaScript is loading: Check Network tab for `/res/logixemulator/plc-file-upload.js` (should be 200 OK)

### Edit Program Page Shows 404

**Cause:** Module not installed or resources not mounted properly.

**Solutions:**
1. Verify module is installed: **Config → System → Modules**
2. Restart Gateway
3. Check URL is correct: `/res/logixemulator/edit-program.html` (note lowercase)

### Device Status Shows "READY - WAITING FOR FILE UPLOAD"

**This is normal!** The device is ready but doesn't have a PLC file yet.

**Solution:** Upload a file using any of the three methods above. Status will change to "Running" after file is loaded.

### No Tags Appear After Upload

**Possible causes:**
1. File format doesn't match selected parser type
2. File is empty or malformed
3. Parser encountered errors

**Solutions:**
1. Check **Gateway → Status → Logs** for error messages
2. Verify **Parser Type** matches your file format (e.g., "Rockwell L5K" for .L5K files)
3. Try with a different/smaller file to test
4. Check that file content was actually saved (edit device and check textarea)

---

## Quick Reference

| Task | Method | URL / Location |
|------|--------|----------------|
| Upload files & browse tags | Connection Browser | `/data/logixemulator/connection-browser` |
| Upload files | Edit Program page | `/res/logixemulator/edit-program.html` |
| Configure device | Gateway Config | `Config → OPC UA → Device Connections` |
| View tags | OPC Browser | Designer → Tools → OPC Browser |
| Check logs | Gateway Status | `Status → Logs → Gateway` |
| Landing page | Gateway | `/res/logixemulator/` |

---

## Need Help?

**Documentation:**
- `README.md` - Module overview
- `docs/ARCHITECTURE.md` - Technical architecture
- `docs/SECURITY.md` - Security best practices
- `docs/TAG_CREATION_FLOW.md` - How tags are created from files
- `KNOWN_ISSUES.md` - Known limitations
- `CHANGELOG.md` - Version history

**Forum:**
- Inductive Automation Forum - Exchange section

**Issues:**
- GitHub Issues (if available)

---

## Version

**Module Version:** 9.2.2
**Ignition Compatibility:** 8.3.0+
