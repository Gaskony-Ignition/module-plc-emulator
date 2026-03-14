# Known Issues and Limitations

## Current Version: v9.2.5

### Known Limitations

#### 1. Parser Support - Rockwell Logix Focus

**Status**: By Design (v8.0.0+)
**Severity**: N/A

**Supported PLC File Parsers:**
- **Rockwell Automation** - L5K/L5X (Allen-Bradley Studio 5000)
- **JSON Format** - Generic JSON tag definitions
- **CSV Format** - Generic CSV variable lists

**Note:** Multi-vendor parsers (Siemens, Schneider, Beckhoff, ABB, Mitsubishi, Omron) were removed in v8.0.0 to focus the module on Rockwell Logix emulation. The module is now named "Logix PLC Emulator" to reflect this scope.

**File Format Examples:**
- Rockwell: `.l5k`, `.l5x`
- JSON: `.json`
- CSV: `.csv`

**Parser auto-detection** is based on file extension and content analysis.

---

#### 2. Hot Reload Performance
**Status**: Known Behavior
**Severity**: Low

Hot reload performs full address space rebuild when PLC file changes. For very large files (1000+ tags), this may cause brief OPC-UA disconnection (typically <2 seconds).

**Impact**: Active subscriptions may experience brief data gap during reload.

**Workaround**: For production systems, pause critical operations during file updates or disable hot reload.

---

#### 3. Simulation Engine - Fully Implemented in v5.5.0
**Status**: **FULLY IMPLEMENTED**
**Severity**: N/A

**Current State:**
- Simulation engine fully functional
- All 5 patterns working: STATIC, RAMP, SINE, RANDOM, TOGGLE
- Tag values update according to configured pattern
- Configurable update interval (default 1000ms, minimum 100ms)

**Usage**: Enable simulation in device configuration and select desired pattern. Tag values will automatically update at the configured interval.

**Note**: Simulation applies to all numeric and boolean tags. String tags remain static.

---

## Reporting New Issues

If you encounter issues not listed here:

1. **Check Gateway Logs**: `Status > Logs > Gateway` (filter for "logixemulator")
2. **Verify Module Version**: Config > Modules (should show v9.2.5)
3. **Check Device Status**: Config > Devices > Edit device (status field shows current state)

**Report Issues With:**
- Ignition version (e.g., 8.3.2)
- Module version (e.g., v9.2.5)
- Parser type being used
- Steps to reproduce
- Relevant log excerpts

**GitHub Issues**: https://github.com/Gaskony-Ignition/ignition-module-plc-emulator/issues

---

## Resolved Issues

The following issues have been **completely resolved**:

### v8.0.0 Fixes (MAJOR RENAME & REFOCUS):
- Renamed module from "Enhanced PLC Simulator" to "Logix PLC Emulator"
- Removed unused multi-vendor parsers to reduce complexity
- Added SINT simulation support

### v5.5.0 Fixes:
- Simulation engine non-functional -> **FIXED in v5.5.0** (all 5 patterns now working)
- No rate limiting protection -> **FIXED in v5.5.0** (dual user+IP rate limits)

### v2.0.0 Fixes:
- File upload not working -> **FIXED in v2.0.0** (automatic device update)
- Manual copy/paste required -> **FIXED in v2.0.0** (drag-and-drop upload)
- Python parser dependency -> **FIXED in v2.0.0** (pure Java parsers)
- No simulation support -> **FIXED in v2.0.0** (engine infrastructure added)
- No hot reload -> **FIXED in v2.0.0** (file watcher implemented)
- No version history -> **FIXED in v2.0.0** (5 versions kept)

For historical v1.x issues, see `docs/archive/KNOWN_ISSUES_v1.x.md`.
