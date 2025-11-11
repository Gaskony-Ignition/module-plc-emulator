# Known Issues and Limitations

## Current Version: v2.0.5

### Known Limitations

#### 1. Parser Support - Partial Implementation
**Status**: In Progress
**Severity**: Low

**Currently Supported:**
- ✅ Rockwell L5X/L5K (Allen-Bradley) - Fully functional
- ✅ JSON Format - Fully functional
- ✅ CSV Format - Fully functional

**Planned Parsers:**
- 🔄 Siemens TIA Portal (S7-1200/1500)
- 🔄 Schneider Electric (Unity Pro/EcoStruxure)
- 🔄 Beckhoff TwinCAT

**Workaround**: Export unsupported PLC formats to JSON or CSV.

---

#### 2. Hot Reload Performance
**Status**: Known Behavior
**Severity**: Low

Hot reload performs full address space rebuild when PLC file changes. For very large files (1000+ tags), this may cause brief OPC-UA disconnection (typically <2 seconds).

**Impact**: Active subscriptions may experience brief data gap during reload.

**Workaround**: For production systems, pause critical operations during file updates or disable hot reload.

---

#### 3. Simulation Engine - Not Fully Implemented
**Status**: Partially Complete
**Severity**: Medium

**Current State:**
- Simulation engine infrastructure exists (`OpcUaSimulationEngine.java`)
- Configuration UI allows enabling simulation patterns
- **However**: Tag value updates not yet implemented

**Impact**: Enabling simulation in device config has no effect on tag values. Tags remain static at initial values.

**Recommendation**: Disable simulation in production until implementation complete (planned for future release).

---

## Reporting New Issues

If you encounter issues not listed here:

1. **Check Gateway Logs**: `Status → Logs → Gateway` (filter for "plcsimulator")
2. **Verify Module Version**: Config → Modules (should show v2.0.5)
3. **Check Device Status**: Config → Devices → Edit device (status field shows current state)

**Report Issues With:**
- Ignition version (e.g., 8.3.2)
- Module version (e.g., v2.0.5)
- Parser type being used
- Steps to reproduce
- Relevant log excerpts

**GitHub Issues**: https://github.com/nigelgwork/ignition-plc-simulator/issues

---

## Resolved Issues (v2.0.0+)

The following issues from v1.x have been **completely resolved**:

- ✅ File upload not working → **FIXED in v2.0.0** (automatic device update)
- ✅ Manual copy/paste required → **FIXED in v2.0.0** (drag-and-drop upload)
- ✅ Python parser dependency → **FIXED in v2.0.0** (pure Java parsers)
- ✅ No simulation support → **FIXED in v2.0.0** (engine infrastructure added)
- ✅ No hot reload → **FIXED in v2.0.0** (file watcher implemented)
- ✅ No version history → **FIXED in v2.0.0** (5 versions kept)

For historical v1.x issues, see `docs/archive/KNOWN_ISSUES_v1.x.md`.
