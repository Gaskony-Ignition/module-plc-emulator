# Known Issues and Limitations

## Current Version: v1.1.0

### Critical Issues

#### File Upload Feature Not Working

**Status**: Not Implemented
**Affects**: v1.1.0
**Severity**: Medium (Workaround available)

**Problem**:
The file upload button/feature attempted in v1.1.0 is not functional. The implementation required web resource mounting capabilities that are not available when using `AbstractDeviceModuleHook` as the base class.

**Root Cause**:
- `AbstractDeviceModuleHook` does not support `mountRouteHandlers()` method
- Web resource mounting requires `AbstractGatewayModuleHook` as base class
- Switching to `AbstractGatewayModuleHook` would lose device driver functionality
- Device drivers and web resources require different module hook base classes

**Workaround**:
Users must manually copy/paste file content into the textarea field:
1. Open PLC export file in text editor
2. Select all content (Ctrl+A / Cmd+A)
3. Copy (Ctrl+C / Cmd+C)
4. Paste into "File Content" textarea in device configuration
5. Save device configuration

**Browser Console Alternative** (Advanced Users):
A JavaScript snippet can be pasted into browser console to add an upload button. See `/docs/archive/FILE_UPLOAD_INSTRUCTIONS.md` for details. This is client-side only and must be re-added after each page refresh.

**Future Solutions**:

**Option 1: Hybrid Module Architecture**
- Create two separate module hooks in same module
- `AbstractGatewayModuleHook` for web resources
- `AbstractDeviceModuleHook` for device driver
- Estimated effort: 2-3 days
- Risk: Complex, requires coordination between hooks

**Option 2: Custom React UI Panel**
- Implement full custom React UI like built-in Programmable Device Simulator
- Requires React/TypeScript frontend + Java backend
- Requires webpack build pipeline
- Estimated effort: 1-2 weeks
- Risk: High complexity, maintenance burden
- See `/docs/archive/REACT_UI_ATTEMPT.md` for previous attempt details

**Option 3: Keep Current Approach**
- Copy/paste workflow is simple and works reliably
- Minimal code complexity
- Focus development effort on parser functionality
- Recommended for v1.x series

**Recommendation**: Stick with copy/paste (Option 3) for v1.x releases. Consider Option 1 for v2.0 if there's strong user demand.

---

### Minor Issues

#### Parser Type "Planned" Status

**Status**: Feature Incomplete
**Affects**: All versions
**Severity**: Low

**Problem**:
Several parser types show in dropdown but are not implemented:
- Siemens TIA Portal
- Schneider Electric
- Beckhoff TwinCAT

**Workaround**:
Use only implemented parsers:
- Rockwell L5K (fully functional)
- JSON Format (fully functional)

**Plan**:
Parsers will be implemented when sample export files become available for testing.

---

#### Large File Performance

**Status**: Known Limitation
**Affects**: All versions
**Severity**: Low

**Problem**:
Very large PLC export files (>5MB) may cause slow Gateway UI response when pasting into textarea.

**Workaround**:
- For files >5MB, consider splitting into multiple devices
- Use browser with good JavaScript performance (Chrome/Edge recommended)
- Be patient - file will be accepted even if UI freezes briefly

**Technical Details**:
Browser textarea performance limitation, not module issue. File content is stored as plain text in Gateway database.

---

## Version-Specific Issues (Historical)

### v1.0.5-v1.0.8: i18n Display Names

**Status**: Fixed in v1.0.9
**Problem**: Display names showed as `?EnhancedSimulator.Meta.DisplayName?`
**Solution**: Fixed resource bundle registration in `BundleUtil.addBundle()`

### v1.0.5-v1.0.8: Enum Dropdown Display

**Status**: Fixed in v1.0.10
**Problem**: Parser type dropdown showed `ROCKWELL` instead of "Rockwell L5K (Allen-Bradley)"
**Solution**: Implemented `toString()` method on ParserType enum

---

## Reporting New Issues

If you encounter issues not listed here:

1. Check Gateway logs (`wrapper.log`) for error messages
2. Note your Ignition version, module version, and exact steps to reproduce
3. Report via project repository with:
   - Description of problem
   - Expected behavior
   - Actual behavior
   - Log excerpts (if applicable)
   - Sample file that triggers issue (if applicable)

---

## Future Enhancements

See `/docs/archive/FUTURE_ENHANCEMENTS.md` for planned features and enhancement ideas.

---

**Last Updated**: Version 1.1.0
**Next Review**: When v1.2.0 development begins
