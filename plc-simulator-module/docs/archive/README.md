# Archived Documentation

This folder contains historical documentation that is no longer current but may be useful for reference.

## Contents

### FILE_UPLOAD_INSTRUCTIONS.md
**Status**: Outdated (v1.1.0 feature not functional)

Browser console JavaScript snippet to add file upload button. This was a client-side workaround for the file upload feature attempted in v1.1.0. The feature is not functional in the module itself.

**Why Archived**:
- File upload feature not implemented in module
- Workaround is complex and temporary
- Users should use copy/paste method instead

**Historical Value**:
- Documents what was attempted
- May be useful if file upload feature is implemented in future
- Shows alternative approach for advanced users

---

### FUTURE_ENHANCEMENTS.md
**Status**: Outdated (written before v1.1.0 attempt)

Planning document for file upload feature implementation. Describes desired UX, technical blockers, and implementation roadmap.

**Why Archived**:
- v1.1.0 attempted implementation and confirmed blockers
- Better documentation now exists in KNOWN_ISSUES.md
- Implementation plans are speculative

**Historical Value**:
- Shows research into file upload approaches
- Documents SDK limitations discovered
- Useful for future v2.0 planning

---

### REACT_UI_ATTEMPT.md
**Status**: Outdated (v1.1.0 attempt failed)

Documents the React UI implementation attempt for file upload. Explains what was built, why it failed, and what code was preserved.

**Why Archived**:
- React UI code is in `/web` directory but non-functional
- SDK API incompatibilities documented
- Approach abandoned for v1.x series

**Historical Value**:
- Documents SDK routing API issues
- Preserves knowledge for future attempts
- Shows what NOT to do with AbstractDeviceModuleHook

---

### INSTALL_INSTRUCTIONS_v1.0.5.md
**Status**: Version-specific (v1.0.5 only)

Installation instructions specific to v1.0.5 addressing i18n display name issues.

**Why Archived**:
- Issue fixed in v1.0.9
- Instructions no longer needed
- General installation covered in README.md

**Historical Value**:
- Documents workaround for v1.0.5-v1.0.8 issues
- May help users still on old versions
- Shows troubleshooting process

---

## When to Use Archived Docs

Use these documents when:
- **Researching file upload implementation** for v2.0
- **Troubleshooting old module versions** (v1.0.5-v1.0.10)
- **Understanding design decisions** and why certain approaches were abandoned
- **Planning major refactoring** that might revisit these features

## Current Documentation

For current module documentation, see:
- `/modules/ignition-plc-simulator/plc-simulator-module/README.md` - Main documentation
- `/modules/ignition-plc-simulator/plc-simulator-module/KNOWN_ISSUES.md` - Current limitations
- `/modules/ignition-plc-simulator/plc-simulator-module/CHANGELOG.md` - Version history
- `/modules/ignition-plc-simulator/plc-simulator-module/DEVELOPMENT.md` - Development guide
- `/modules/ignition-plc-simulator/plc-simulator-module/BUILD.md` - Build instructions
- `/modules/ignition-plc-simulator/plc-simulator-module/TESTING.md` - Testing procedures
- `/modules/ignition-plc-simulator/plc-simulator-module/SIGNING.md` - Module signing

---

**Archive Created**: 2025-11-07
**Last Updated**: 2025-11-07
