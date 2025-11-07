# Edit Program Guide - Enhanced PLC Simulator

Similar to the original Programmable Device Simulator, the Enhanced PLC Simulator provides a dedicated interface for importing and managing PLC files.

## Access Methods

### Method 1: Edit Program Page (Recommended)

Access the dedicated Edit Program interface:

**URL**: `http://localhost:9088/res/plcsimulator/edit-program.html`

**Features**:
- 📁 Drag-and-drop file import
- 📋 Instructions and workflow guidance
- 🔗 Quick links to device configuration
- 💡 Tips and best practices

**Workflow**:
1. Navigate to the URL above
2. Follow the on-screen instructions
3. Use drag-and-drop to upload PLC files
4. Files are imported with visual feedback

### Method 2: Device Configuration (Direct)

Use the built-in file upload button:

1. Go to **Config → Devices** in Gateway
2. Find your Enhanced PLC Simulator device
3. Click to edit the device
4. Look for **"📁 Upload PLC File"** button above file content field
5. Click and select your file (L5K, JSON, CSV, XML)
6. File uploads automatically

## Comparison with Original Simulator

### Original Programmable Device Simulator

1. Create device connection
2. Device appears in list
3. Click 3-dot menu → "Edit Program"
4. Import dialog opens
5. Upload file

### Enhanced PLC Simulator (v1.3.0)

**Option A - Edit Program Page**:
1. Create device connection (no file needed)
2. Navigate to Edit Program page URL
3. Drag-and-drop file import
4. File uploads with feedback

**Option B - Direct Upload**:
1. Create device connection (no file needed)
2. Edit device configuration
3. Click "📁 Upload PLC File" button (auto-appears)
4. Select file and it uploads instantly

## Enhanced Features

The Enhanced PLC Simulator improves on the original with:

✅ **No file required at device creation** - Create device first, add files later
✅ **Auto-injected upload button** - Appears automatically in config form
✅ **Drag-and-drop support** - Modern file import interface
✅ **Multiple upload methods** - Choose what works best for your workflow
✅ **Visual feedback** - See file size, loading status, success/error states
✅ **Multi-vendor support** - Rockwell, Siemens, Schneider, Beckhoff, JSON

## Workflow Examples

### Quick Start (No File)

```
1. Config → Devices → Create New Device
2. Select: "Enhanced PLC Simulator"
3. Enter: Device Name = "MyPLC"
4. Select: Parser Type = "Rockwell L5K"
5. Click: Save
6. Device Status: "Ready - Waiting for file upload"
```

### Add File Later

```
Option A: Edit Program Page
1. Open: http://localhost:9088/res/plcsimulator/edit-program.html
2. Follow instructions
3. Drag-and-drop L5K file
4. File imports

Option B: Device Edit
1. Config → Devices → Edit "MyPLC"
2. Click: "📁 Upload PLC File" button
3. Select file
4. File uploads automatically
5. Click: Save
```

### Complete Setup (With File)

```
1. Config → Devices → Create New Device
2. Select: "Enhanced PLC Simulator"
3. Enter: Device Name = "MyPLC"
4. Select: Parser Type = "Rockwell L5K"
5. Click: "📁 Upload PLC File"
6. Select: DemoWWTP.L5K
7. File uploads (content and filename auto-populate)
8. Click: Save
9. Device Status: "Running"
10. Tags appear in OPC-UA browser
```

## Troubleshooting

### Upload Button Not Appearing

**Symptom**: No "📁 Upload PLC File" button in device config

**Solutions**:
1. Refresh the page (Ctrl+F5 or Cmd+Shift+R)
2. Clear browser cache
3. Use the Edit Program page instead: `/res/plcsimulator/edit-program.html`
4. Check module version is v1.3.0 or higher

### Edit Program Page Not Loading

**Symptom**: 404 error when accessing edit-program.html

**Solutions**:
1. Verify module is installed: Config → System → Modules
2. Check version is v1.3.0 or higher
3. Restart Gateway if just upgraded
4. URL should be: `http://localhost:9088/res/plcsimulator/edit-program.html`

### File Not Importing

**Symptom**: File uploads but device doesn't update

**Solutions**:
1. Check file format matches Parser Type (L5K for Rockwell, etc.)
2. Verify file content is valid (not empty/corrupted)
3. Check Gateway logs for parsing errors
4. Try smaller file first to test
5. Ensure device is enabled

## Supported File Formats

| Parser Type | File Extensions | Description |
|------------|----------------|-------------|
| Rockwell L5K | .l5k, .L5K | Allen-Bradley Logix 5000 exports |
| JSON Format | .json, .JSON | Generic JSON tag definitions |
| Siemens TIA | .xml, .XML | Siemens TIA Portal exports |
| Schneider | .csv, .CSV | Schneider Unity Pro exports |
| Beckhoff | .xml, .XML | Beckhoff TwinCAT project files |

## Tips & Best Practices

💡 **Create devices first, add files later** - More flexible workflow
💡 **Use descriptive device names** - Makes management easier
💡 **Test with small files first** - Verify setup before large imports
💡 **Bookmark the Edit Program page** - Quick access for frequent uploads
💡 **Check device status after import** - Should show "Running" when successful
💡 **Browse tags in OPC-UA** - Verify tags were created correctly

## See Also

- [README.md](README.md) - Project overview
- [FILE_UPLOAD_GUIDE.md](FILE_UPLOAD_GUIDE.md) - Technical file upload details
- [KNOWN_ISSUES.md](KNOWN_ISSUES.md) - Current limitations
- [CHANGELOG.md](CHANGELOG.md) - Version history
