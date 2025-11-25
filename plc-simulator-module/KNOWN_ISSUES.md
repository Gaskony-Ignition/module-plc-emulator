# Known Issues and Limitations

## Current Version: v6.5.0

### Known Limitations

#### 1. Multi-Vendor Parser Support - ✅ FULLY IMPLEMENTED in v6.5.0
**Status**: **COMPREHENSIVE SUPPORT**
**Severity**: N/A

**Supported PLC Vendors:**
- ✅ **Rockwell Automation** - L5K/L5X (Allen-Bradley Studio 5000)
- ✅ **Siemens** - TIA Portal XML exports (S7-1200/1500/1500T)
- ✅ **Schneider Electric** - Unity Pro/EcoStruxure (M340, M580, Quantum)
- ✅ **Beckhoff** - TwinCAT 2/3 XML exports
- ✅ **Mitsubishi Electric** - GX Works 2/3 CSV exports (iQ-Platform: Q, L, F series)
- ✅ **ABB** - Automation Builder / Control Builder Plus (.apj, .xml)
- ✅ **JSON Format** - Generic JSON tag definitions
- ✅ **CSV Format** - Generic CSV variable lists

**Market Coverage:** Now supports 6 of the top 6 PLC vendors globally, covering ~85% of industrial automation market share.

**File Format Examples:**
- Rockwell: `.l5k`, `.l5x`
- Siemens: `.xml` (TIA Portal exports containing "siemens", "tia", or "s7-")
- Schneider: `.xml`, `.csv`, `.xef` (Unity Pro exports)
- Beckhoff: `.xml`, `.xti`, `.tpy`, `.tsm` (TwinCAT exports)
- Mitsubishi: `.csv`, `.gxw`, `.gpj`, `.gpa` (GX Works exports)
- ABB: `.xml`, `.apj` (Automation Builder/Control Builder Plus)

**Note:** Parser auto-detection based on file extension and content analysis.

---

#### 2. Hot Reload Performance
**Status**: Known Behavior
**Severity**: Low

Hot reload performs full address space rebuild when PLC file changes. For very large files (1000+ tags), this may cause brief OPC-UA disconnection (typically <2 seconds).

**Impact**: Active subscriptions may experience brief data gap during reload.

**Workaround**: For production systems, pause critical operations during file updates or disable hot reload.

---

#### 3. Simulation Engine - ✅ RESOLVED in v5.5.0
**Status**: **FULLY IMPLEMENTED**
**Severity**: N/A

**Current State:**
- ✅ Simulation engine fully functional
- ✅ All 5 patterns working: STATIC, RAMP, SINE, RANDOM, TOGGLE
- ✅ Tag values update according to configured pattern
- ✅ Configurable update interval (default 1000ms, minimum 100ms)

**Usage**: Enable simulation in device configuration and select desired pattern. Tag values will automatically update at the configured interval.

**Note**: Simulation applies to all numeric and boolean tags. String tags remain static.

---

## Reporting New Issues

If you encounter issues not listed here:

1. **Check Gateway Logs**: `Status → Logs → Gateway` (filter for "plcsimulator")
2. **Verify Module Version**: Config → Modules (should show v6.0.0)
3. **Check Device Status**: Config → Devices → Edit device (status field shows current state)

**Report Issues With:**
- Ignition version (e.g., 8.3.2)
- Module version (e.g., v6.0.0)
- Parser type being used
- Steps to reproduce
- Relevant log excerpts

**GitHub Issues**: https://github.com/nigelgwork/ignition-plc-simulator/issues

---

## Resolved Issues

The following issues have been **completely resolved**:

### v6.5.0 Fixes (EXTENDED VENDOR COVERAGE):
- ✅ Missing Mitsubishi support → **ADDED in v6.5.0** (GX Works 2/3 CSV parsing)
- ✅ Missing ABB support → **ADDED in v6.5.0** (Automation Builder/Control Builder Plus)
- ✅ Single-column CSV not supported → **FIXED in v6.5.0** (enhanced CSV parsing)
- ✅ Device type inference → **ADDED in v6.5.0** (Mitsubishi device code auto-detection)

### v6.0.0 Fixes (MAJOR RELEASE):
- ✅ Limited to Rockwell PLCs only → **FIXED in v6.0.0** (multi-vendor support added)
- ✅ Siemens TIA Portal parser → **ADDED in v6.0.0** (S7-1200/1500 support)
- ✅ Schneider Electric parser → **ADDED in v6.0.0** (Unity Pro/EcoStruxure support)
- ✅ Beckhoff TwinCAT parser → **ADDED in v6.0.0** (TwinCAT 2/3 support)

### v5.5.0 Fixes:
- ✅ Simulation engine non-functional → **FIXED in v5.5.0** (all 5 patterns now working)
- ✅ No rate limiting protection → **FIXED in v5.5.0** (dual user+IP rate limits)

### v2.0.0 Fixes:
- ✅ File upload not working → **FIXED in v2.0.0** (automatic device update)
- ✅ Manual copy/paste required → **FIXED in v2.0.0** (drag-and-drop upload)
- ✅ Python parser dependency → **FIXED in v2.0.0** (pure Java parsers)
- ✅ No simulation support → **FIXED in v2.0.0** (engine infrastructure added)
- ✅ No hot reload → **FIXED in v2.0.0** (file watcher implemented)
- ✅ No version history → **FIXED in v2.0.0** (5 versions kept)

For historical v1.x issues, see `docs/archive/KNOWN_ISSUES_v1.x.md`.
