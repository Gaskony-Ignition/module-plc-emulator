# Logix PLC Emulator - Development Roadmap

## Overview

This document outlines the development history and future direction of the Logix PLC Emulator module.

## Current State (v10.0.0)

The module is production-ready, focused on Rockwell Logix PLC emulation with the following capabilities:

- **Swap-compatible NodeIds**: OPC-UA NodeId identifiers match Ignition's native Allen-Bradley Logix driver (bare controller tags, `Program:<Prog>.<Tag>` program tags, DWORD-packed BOOL arrays, full array expansion) per `docs/plans/ADDRESSING.md` — a tag binding developed against the emulator survives a swap to the real PLC (charter §2.2)
- **Parsers**: Rockwell L5X (primary, fully verified), L5K (best-effort, fails loudly), JSON, CSV
- **OPC-UA Integration**: Full device driver with hierarchical browse structure; module I/O tags parsed from `<Modules>`; `ExternalAccess` honoured (None omitted, Read Only enforced) on every write path
- **Simulation Engine**: 5 patterns (STATIC, RAMP, SINE, RANDOM, TOGGLE), fully wired to the OPC-UA address space (fixed in v10 — see Known Issues history); per-tag and bulk-by-scope control, gated by a device-level enable flag
- **File Version Manager**: last 5 uploads retained, with REST + web-UI list/revert (wired in v10; previously dead code)
- **Web UI**: React-based Connection Browser with sidebar navigation, dashboard, tags, devices, logs, diagnostics, and simulation views
- **Security**: XXE prevention, path traversal protection, authentication, rate limiting, cross-device file scoping
- **Logging**: SQLite-backed log storage with filtering
- **Test Coverage**: 522 tests in the default suite (100% passing) + 17 `@Tag("fidelity")` tests (run via the `fidelityTest` Gradle task) asserting driver-matching NodeId behaviour against a vendored real-world export corpus

## Completed Phases

### Phase 1: Core Implementation (v1.0.0 - v3.0.0)

- Basic Rockwell L5K support
- Device driver architecture with OPC-UA integration
- Pure Java parsers (L5K, L5X, JSON, CSV)
- Complete Rockwell predefined type support (22 types including TIMER, COUNTER, PID, PIDE)
- UDT expansion with nested members

---

### Phase 2: Stabilization & Security (v5.4.9 - v5.5.0)

- XXE attack prevention
- Path traversal protection
- Authentication bypass fixes
- File size DoS prevention
- Simulation engine completion (all 5 patterns)
- Rate limiting implementation
- Comprehensive test suite

---

### Phase 3: Code Quality (v7.0.0)

- L5KParser refactored: 843 -> 378 lines (55% reduction)
- FileUploadRoutes refactored: 1067 -> 304 lines (72% reduction)
- Extracted helper classes (UDTDefinition, RockwellBuiltInTypes, PathSecurity, AuthenticationHelper, DeviceFileManager)
- Incremental address space updates (smart change detection)
- Tag Browser UI

---

### Phase 4: Rename & Refocus (v8.0.0)

- **Breaking change**: Renamed from "Enhanced PLC Simulator" to "Logix PLC Emulator"
- Removed multi-vendor parsers (Siemens, Schneider, Beckhoff, ABB, Mitsubishi, Omron) that were unused
- SINT simulation support
- Storage directory migrated from `plc-simulator` to `logix-emulator`

---

### Phase 5: UI Unification (v8.1.0 - v8.2.0)

- Unified Connection Browser (merged File Upload + Tag Browser)
- Single navigation entry in Gateway Config
- Restyled to match Ignition 8.3 gateway theme

---

### Phase 6: Modern React UI & Observability (v8.2.11 - v9.0.0)

- Full React + TypeScript rewrite of Connection Browser (replaced monolithic HTML)
- Sidebar-driven multi-view layout (Dashboard, Devices, Tags, Logs, Diagnostics, Simulation)
- SQLite-backed log storage with real-time filtering
- CPU/RAM status bar monitoring
- Catppuccin-inspired neutral charcoal theme
- Incremental test coverage expansion (92 -> 113 tests)
- Dead code removal, dependency cleanup, version consolidation

---

## Future Plans

### Fidelity follow-ups (post-v10, deferred — see `docs/KNOWN_ISSUES.md`)

Accepted maintainer deferrals from the v10.0.0 fidelity work, not blocking
release:

- **Structure/array member initial values**: only a scalar atomic tag's
  initial value is read from the export (C8); nested UDT/AOI/array member
  initial values are not (KNOWN_ISSUES #6).
- **Bit-of-integer addressing** (`Tag.b` on a plain atomic integer, e.g.
  `Status.5`): no emulator support yet, pending bench-confirmed bit-width
  bounds and a pre-create-vs-on-demand design decision (KNOWN_ISSUES #5).
- **Motion/coordinate predefined member sets**: `AXIS_*`/`MOTION_GROUP`/
  `COORDINATE_SYSTEM` expose only the ~10-15 most-referenced members each;
  no public corpus export exists to test full fidelity (KNOWN_ISSUES #4).
- **Version-snapshot same-second granularity**: `FileVersionManager` names
  versions with second-precision timestamps, so two uploads within the same
  second collide (KNOWN_ISSUES #7).
- **Revert/upload concurrency hardening**: no per-device locking between
  concurrent upload and version-revert REST calls (KNOWN_ISSUES #8).

### Short-term

- **Alarm Integration**: Parse PLC alarm definitions, generate Ignition alarms
- **Advanced Simulation**: Custom expression-based simulation, interlinked tag simulation
- **Data Export/Import**: Export tag configuration to CSV/JSON, batch tag value import

### Medium-term

- **Historical Data Simulation**: Pre-populated historical trends
- **Enhanced UI**: Real-time value monitoring improvements, simulation controls

### Long-term

- **Multi-Instance Management**: Centralized device registry, inter-device communication
- **Program Execution Simulation**: Basic ladder logic simulation

---

## Version History

| Version | Key Features |
|---------|--------------|
| v10.0.0 | Driver-canonical/swap-compatible NodeIds (ADDRESSING.md), full array + BOOL-pack expansion, real-world corpus + fidelity test suite, versions list/revert REST + UI |
| v9.0.0 | Modern React UI, SQLite logs, dashboard, simulation view, 113 tests |
| v8.2.0 | Restyled Connection Browser for Ignition 8.3 theme |
| v8.1.0 | Unified Connection Browser (merged File Upload + Tag Browser) |
| v8.0.0 | Renamed to Logix PLC Emulator, removed multi-vendor parsers |
| v7.0.0 | Major code refactoring, incremental updates, Tag Browser |
| v5.5.0 | Simulation engine, rate limiting |
| v5.4.9 | Security hardening (XXE, auth, path traversal) |
| v3.0.0 | Complete Rockwell type support (22 types) |
| v2.0.0 | Device driver architecture, pure Java parsers |
| v1.0.0 | Basic Rockwell L5K support |

---

## Notes

- This roadmap is subject to change based on user feedback and priorities
- Security and stability always take priority over new features
- The module requires Ignition 8.3+

**Last Updated**: 12/07/2026 (v10.0.0 release)
