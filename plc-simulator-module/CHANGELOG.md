# Changelog

All notable changes to the Enhanced PLC Simulator module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned
- File upload feature redesign (investigating hybrid module architecture)
- Additional parser implementations (Siemens, Schneider, Beckhoff)
- Enhanced error handling and validation
- Configuration import/export functionality

---

## [1.1.0] - 2025-11-07

### Attempted (Not Successful)
- File upload feature using web resources
  - Attempted to add file browse button to device configuration
  - Implementation blocked by AbstractDeviceModuleHook limitations
  - Feature not functional in this release

### Known Issues
- File upload not working - users must copy/paste file content
- See KNOWN_ISSUES.md for details and workarounds

### Status
- Core functionality working (device driver, parsing, OPC-UA tags)
- i18n and display names functional from v1.0.9/v1.0.10 fixes

---

## [1.0.10] - 2025-11-06

### Fixed
- **Parser type dropdown display**: Implemented `toString()` method on `ParserType` enum
  - Now shows: "Rockwell L5K (Allen-Bradley)" instead of "ROCKWELL"
  - Applies to all enum values in device configuration dropdown

### Technical Details
- Modified `ParserType` enum in `EnhancedSimulatorConfig.java`
- Added `@Override toString()` returning `displayName` field
- Verified in compiled module bytecode

---

## [1.0.9] - 2025-11-06

### Fixed
- **i18n bundle registration**: Fixed resource bundle not being loaded
  - Display names now show correctly: "Enhanced PLC Simulator" instead of "?EnhancedSimulator.Meta.DisplayName?"
  - Fixed in `EnhancedSimulatorExtensionPoint.java`
  - Added `BundleUtil.get().addBundle()` call in module startup

### Changed
- Resource bundle properly registered on module load
- All localized strings now resolve correctly

### Technical Details
- Call to `BundleUtil.get().addBundle()` added to hook startup
- Verified bundle path: `com.inductiveautomation.plcsimulator.gateway.device.EnhancedSimulator`
- Properties file exists at correct location

---

## [1.0.5-1.0.8] - 2025-11-05 to 2025-11-06

### Issues Identified
These versions had the following problems (fixed in v1.0.9 and v1.0.10):
- Display names showing as question-mark strings
- Enum values showing internal names instead of friendly names
- i18n bundle not being registered

### Status
- These versions are deprecated
- Upgrade to v1.0.9+ recommended

---

## [1.0.1] - 2025-11-03

### Changed
- **Vendor name updated**: Changed from previous vendor to "Gaskony"
- Module metadata updated in `module.xml`

### Technical Details
- Version bumped to v1.0.1
- Vendor display name changed across all module metadata

---

## [1.0.0] - 2025-11-02

### Added - Initial Release
- Device driver architecture using `AbstractDeviceModuleHook`
- Multi-vendor parser support:
  - Rockwell L5K (Allen-Bradley) - Fully implemented
  - JSON Format - Fully implemented
  - Siemens TIA Portal - Placeholder
  - Schneider Electric - Placeholder
  - Beckhoff TwinCAT - Placeholder
- Device configuration schema:
  - Device name
  - Parser type selection (dropdown)
  - File content (textarea)
  - Device name in file
- Resource bundle for i18n support
- Module signing with self-signed certificate (development)
- Build configuration using Gradle and Ignition Module SDK

### Features
- Appears in device connection dropdown as new device type
- Parser types shown with friendly names in dropdown
- File content accepts PLC export files via copy/paste
- Tags automatically created from parsed content
- OPC-UA integration via Ignition's built-in OPC server

### Documentation
- Initial BUILD.md, SIGNING.md, TESTING.md created
- Development setup documented

---

## Version Number Scheme

- **Major** (x.0.0): Breaking changes, major architectural changes
- **Minor** (1.x.0): New features, non-breaking changes
- **Patch** (1.0.x): Bug fixes, minor improvements

---

## Git History Notes

The project git history shows:
- `069b882` - Fix: Update vendor name to Gaskony and bump to v1.0.1
- `5b29f0e` - Cleanup: Remove unused files and reduce repository size
- `e5439cb` - Refactor: Convert to device driver appearing in device connection dropdown
- `8e4c0c4` - Initial commit: Ignition PLC Simulator with multi-vendor support

---

## Links

- [Known Issues](KNOWN_ISSUES.md)
- [Build Instructions](BUILD.md)
- [Testing Guide](TESTING.md)
- [Development Guide](DEVELOPMENT.md)
