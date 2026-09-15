# Changelog

All notable changes to the Logix PLC Emulator module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [11.1.2] - 2026-07-30
### Removed
- `wicket-core` 9.8.0 (`compileOnly`) dead dependency removed; it was never shipped and never imported, so removing it has no runtime effect.

## [11.1.1] - 2026-07-30
### Changed
- Gson moved from `compileOnly` 2.11.0 to `modlImplementation` 2.14.0 (now shipped).
- sqlite-jdbc bumped 3.49.1.0 to 3.53.2.1.
- jakarta-servlet bumped 5.0.0 to 6.0.0 (`compileOnly`).
- Test dependencies bumped to junit-jupiter 5.14.4, mockito 5.23.0, awaitility 4.3.0; added an explicit `junit-platform-launcher` 1.14.4 pin.

### Fixed
- No functional code changes; dependency-only audit. Full gateway test suite passes.

## [11.1.0] - 2026-07-30
### Added
- `SimulatorModuleHook` now overrides `isMakerEditionCompatible()` to return `true`, so the module runs on Ignition Maker Edition instead of being silently refused.

## [11.0.0] - 2026-07-27
### Removed
- BREAKING: Rockwell L5X is no longer a supported format. Uploading a `.l5x` is rejected with guidance to export as L5K instead; an existing device whose file is `.l5x` will not reload it after upgrading and must be re-uploaded as L5K.
- `.xml` removed from the legacy device-config uploader's file picker (was offered but never accepted server-side).
- `.txt` removed from `ParserFactory`'s advertised extensions (was unreachable from the upload path anyway).

### Changed
- README, QUICK_START, PROJECT_CHARTER, device-driver metadata and both upload pickers now consistently reference L5K only.
- `L5XParser` is retained internally as the reference implementation for swap-fidelity and corpus tests but is no longer user-facing.

### Security
- XXE attack surface removed entirely: no XML parser is reachable from any upload path.

### Known gap
- Device config's "Max File Size (MB)" field is still ignored; uploads are fixed at 50MB (tracked as `KNOWN_ISSUES.md` #11).

## [10.1.0] - 2026-07-13
### Added
- Statement-oriented, block-stack L5K parser (`L5KParser` rewrite): tag-shaped statements are recognised only inside five whitelisted contexts, with routine/ST/FBD/`MODULE`/`CONFIG` content treated as opaque.
- Loud parse accounting: uploads carry a parse summary and a `structurallyClean` flag; a non-clean parse surfaces a warning instead of a bare success.
- Normative grammar spec `docs/plans/L5K-GRAMMAR.md`.
- Real-file fidelity tests against genuine site exports, a synthetic grammar matrix, and a cross-format L5X/L5K equivalence test.

### Fixed
- BREAKING: L5X AOI instance expansion no longer emits a phantom `InOut` member — `InOut` parameters are references, not backing storage, and any binding to the old phantom node will find it gone.
- L5K statement accumulator now recognises `(* ... *)` block comments instead of merging them into a neighbouring statement.
- L5K statement accumulator no longer silently drops residue after a same-line terminator (e.g. `A : DINT; B : DINT;`); the residue is now counted and flagged.
- L5K UDT/AOI instance expansion now emits a default `initial_value` for atomic leaf members, matching the L5X path.

## [10.0.0] - 2026-07-12
### BREAKING CHANGES
- NodeId scheme now matches Ignition's native Allen-Bradley Logix driver: controller-scoped tags are the bare tag name; program-scoped tags use `Program:<Prog>.<tag>`.
- Removed the pre-v10 duplicate-node "long"/"short" alias scheme; each tag now has exactly one canonical node.
- BOOL arrays are now DWORD-packed (`Tag[word].bit`) instead of one node per element.
- Arrays fully expand: array-of-UDT/predefined instances, multi-dimensional arrays, and array members inside a UDT all emit every element instead of collapsing.
- `ExternalAccess="None"` tags/members are no longer created at all; `Read Only`/`Constant="true"` tags are now created read-only.
- Predefined structured-type member tables corrected against Rockwell references (TIMER's phantom `.ER` removed; CONTROL gains `.UL`/`.IN`/`.FD`; MESSAGE/PID/PIDE/ALARM_ANALOG/ALARM_DIGITAL corrected). Bindings to the old fabricated members break.
- Migration: bindings created against a pre-v10 emulator must be re-pointed to the new paths after re-importing the device's file.

### Fixed
- L5X uploads no longer crash the whole address-space build on one non-numeric `initial_value`; per-tag node creation is now isolated.
- Simulation engine now correctly writes registered OPC-UA nodes, so RAMP/SINE/RANDOM/TOGGLE patterns move browsed values.
- File version manager was inert (`saveVersion` never called, no revert route/UI, unordered restart pickup); it now saves on every upload, restarts pick the most-recently-modified file, and versions beyond 5 are pruned.
- JSON parser output is now normalised to the `global_tags`/`programs` shape `buildAddressSpace` expects (previously produced zero tags).
- Upload and hot-reload endpoints now propagate a genuine parse/build failure as a 4xx/5xx error instead of masking it as HTTP 200 success.
- L5K parser now fails loudly on unparseable input instead of substituting a demo tag.
- Cross-device file scoping now uses an unambiguous dot-separated naming tier; the legacy underscore form is still recognised for pickup but never deleted.
- Hot-reload now diffs on the expanded canonical node ids, fixing partially-applied address spaces after a structural change.
- Read-only enforcement now covers all write paths (OPC-UA, REST, simulation), including packed BOOL-array bits.
- Genuine L5X parse failures now propagate instead of being masked with demo tags; ASCII-radix scalar decoding, NUL/control-byte content handling, and error-body path leakage also fixed.

### Added
- File version list/revert REST routes and a minimal UI.
- Module I/O tags parsed from the L5X `<Modules>` section.
- v32+ unsigned and time atomic types (`USINT`/`UINT`/`UDINT`/`ULINT`, `DT`/`LDT`/`LTIME`/`TIME`) mapped to proper OPC-UA types.
- Initial values now read from the `Value` attribute of a self-closing `DataValue` element.
- Base `STRING` tags gain `.LEN`/`.DATA` members alongside the scalar value.
- AOI `EnableIn`/`EnableOut` parameters exposed on AOI backing tags.
- Real-world export corpus and an `@Tag("fidelity")` test suite (17 tests).
- `AddressPolicy` vendor seam separating Rockwell addressing rules from `AddressSpaceBuilder`.

### Known limitations
- Motion/coordinate predefined member sets remain incomplete; bit-of-integer addressing is not implemented; structure/array member initial values are not read from the export.

## [9.2.1] - 2026-03-07
### Changed
- Added an `allprojects` block for consistent version/group propagation across subprojects.
- Added `allowImportingTsExtensions: false` to `tsconfig.webpack.json`.
- Removed a vestigial `prettier` devDependency.
- Standardised ESLint rule order across modules.

## [9.2.14] - 2026-05-09
### Removed
- `:designer` Gradle subproject and its no-op `DesignerHook` (no Designer-side functionality existed).

### Changed
- `:common` scope mapping changed from `GD` to `G`.
- Removed the unused `ignition-designer-api` library declaration.
- Added a `gradle-check` CI job running `./gradlew check` to gate PRs on JaCoCo/Checkstyle/SpotBugs.

## [9.2.13] - 2026-05-09
### Added
- Accessibility baseline: `prefers-reduced-motion` support, a skip link, an accessible `Modal` primitive (focus trap, Escape, focus restore), and a `jsx-a11y` label rule.

### Changed
- Visibility-aware polling for `StatusBar`, suspending fetches when the tab/panel is hidden.
- Standardised `.gitattributes`/`.gitignore` to the cross-module convention.
- Backfilled CHANGELOG entries for six prior undocumented patches.

### Refactored
- Broke up the `LogixEmulatorDevice` god class from 1054 to 472 lines, extracting eight focused collaborators.

## [9.2.12] - 2026-05-06
### Fixed
- Live `dataItems` snapshot is now consumed by the simulation engine and recalibrate-on-write, so OPC-UA writes are reflected immediately by running simulations.

### Security
- Hardened `.gitignore` to deny `gradle.properties`, `sign.props`, keystores and broad `.env` patterns, with an explicit allowlist for templates/examples.

### Notes
- Intermediate releases 9.2.7-9.2.11 were never committed to git; their disk-only changes are rolled into this entry.

## [9.2.6] - 2026-03-14
### Changed
- Standardised shadow value and modal z-index across the module UI.

## [9.2.5] - 2026-03-14
### Changed
- Standardised CSS spacing, radius and transition timing across the module UI.

## [9.2.4] - 2026-03-14
### Changed
- Standardised modal backdrop opacity to 0.6.

## [9.2.3] - 2026-03-14
### Changed
- Cross-module UI standardisation: typography, spacing, accent colours and component shells aligned with the other modules.

## [9.2.2] - 2026-03-13
### Fixed
- Standardised `PageHeader` component and auth-screen layout (subtitle handling, badge prop, padding).

## [9.1.1] - 2026-02-22
### Changed
- Gateway Connections nav entry renamed from "Connection Browser" to "Devices".

## [9.1.0] - 2026-02-22
### Changed
- Module logs now display newest entries first, with auto-scroll moving to the top.
- Log timestamps now render in the browser's local timezone instead of the server's.

## [9.0.9] - 2026-02-22
### Fixed
- Tag tree expand was broken (backend returned `children`, frontend read `tags`); every folder expanded empty.
- Flat-mode pagination never loaded more; added a `total` field matching what the frontend reads.
- Stats bar showed wrong tag/UDT counts; added a `udt_instances` snake_case alias.

### Changed
- Diagnostics log panel reworked to mirror the Camera Driver log-handler pattern: server-side module filter, incremental polling every 5s, level filter pills, auto-scroll toggle, Clear button, larger panel.

## [9.0.8] - 2026-02-22
### Added
- `DeviceRegistry` interface and `GatewayAuthHelper` extracted from `FileUploadRoutes`.
- Controller layer (`DeviceController`, `TagController`, `SimulationController`, `SystemController`); `FileUploadRoutes` reduced to a thin router.
- `DeviceFileManager` now takes `DeviceRegistry` via constructor instead of static coupling.
- New unit tests across the controller layer and `AddressSpaceBuilder` (285 tests total).

### Changed
- CI excludes `gradle.properties` from the hardcoded-credential scan.
- `moduleVersion` string moved from `FileUploadRoutes` to `SystemController`.

## [9.0.7] - 2026-02-22
### Security
- `requireAuthentication()` guard added to all 14 sensitive API handlers.
- SQL LIKE wildcard escaping in the log filter to prevent injection.
- `validateDeviceName()` rejects invalid device names on every mutating endpoint.
- Write rate limiter added (60/user, 600/IP per hour) on all POST/DELETE endpoints.
- Stricter IPv6 validation regex; `sanitizeForLog()` strips CRLF from log interpolations.

### Added
- `Routes.java` centralising route path constants; `syncVersion` Gradle task wired into the build.
- Shared TypeScript types, typed `apiFetch` wrappers, and native `DeviceManagerView`/`TagBrowserView` React views replacing iframe wrappers.

### Changed
- `FileWatcher`'s boolean flag moved to `AtomicBoolean`; fixed a double-iteration bug in `IncrementalAddressSpaceUpdater`.

### Removed
- Legacy HTML pages, `LogsView` (merged into Diagnostics), and iframe wrapper views.

## [9.0.6] - 2026-02-22
### Added
- `TagTreeBuilderTest` (43 tests), `OpcUaSimulationEngineTest` (23 tests), `DeviceFileManagerTest` (13 tests) — 242 tests total.

## [9.0.4] - 2026-02-22
### Changed
- Full CSS variable system added to `App.scss`; all component CSS swept to replace hardcoded hex values.
- `LogsView` removed; log entries merged into Diagnostics.

## [9.0.2] - 2026-02-21
### Changed
- Standardised all view headers to an icon + title + description pattern; uniform text sizes, colours and card backgrounds across views.

## [9.0.1] - 2026-02-21
### Fixed
- Gateway logs endpoint crash: `getUsername()` returned null for data routes, causing an NPE in the rate limiter; now falls back to an `anon-{ip}` key.

### Changed
- Page background darkened for contrast against panel backgrounds.

## [9.0.0] - 2026-02-21
### Added
- Full React + TypeScript rewrite of the Connection Browser, replacing the monolithic HTML page.
- Sidebar-driven multi-view layout (Dashboard, Devices, Tags, Logs, Diagnostics, Simulation).
- SQLite-backed log storage with real-time filtering and search; CPU/RAM status bar.
- React `ErrorBoundary` component; 21 additional tests (113 total).

### Security
- CSRF protection on all POST/DELETE endpoints; separate read-rate limiter; XSS hardening via `escapeHtml()`; regex-based IP validation replacing DNS-resolving lookups; file-extension validation before parsing; `sign.props` and `package-lock.json` removed from git tracking.

### Changed
- Bumped to major version 9.0.0 as a clean slate; mutable fields made `volatile`/`AtomicBoolean` for thread safety.

### Removed
- Unused `ConnectionBrowser` directory and `react-redux` type dependency; all monospace fonts.

### Fixed
- `InputStream` leak in `serveHtmlPage()`; LINT/LREAL data types and initial values now map/parse correctly; several stale hardcoded version strings corrected.

## [8.2.1] - 2026-02-11
### Changed
- Extracted `TagTreeBuilder` into its own class; replaced fully-qualified inline type names with imports; made `ParserFactory`'s parser list immutable; fixed `IncrementalAddressSpaceUpdater` to handle program-scoped tag paths.

### Added
- Unit tests for `CsvParser`, `JsonPLCParser` and `RateLimiter`.

## [8.2.0] - 2026-02-11
### Security
- Fixed an authentication bypass where `getUsername()` fell back to a `"gateway-user"` default for unauthenticated requests.
- Sanitised error messages returned to clients; removed full filesystem paths from device status responses; fixed rate-limiter window reset; removed an unused 11.4MB ELF binary from module resources.

### Fixed
- `FileValidator` now accepts `.l5x`/`.json`/`.csv` (was rejecting all non-`.l5k` files).
- CSV parser output schema corrected to produce tags in the OPC-UA address space.
- `SimpleDateFormat` thread-safety bug in `FileVersionManager` replaced with `DateTimeFormatter`.

### Changed
- Replaced reflection-based access in `DeviceFileManager`/`FileUploadRoutes` with public API methods; cached `RockwellBuiltInTypes` as an immutable static field.

### Removed
- Unused imports and dead code (`nodeIdCounter` map, unused `ParserException` class, wildcard import).

## [8.1.2] - 2026-02-11
### Fixed
- File persistence across gateway restarts (storage directory mismatch between `plc-simulator` and `logix-emulator`).
- Removed monospace font overrides; UI now uses gateway sans-serif throughout.

### Changed
- Background colours shifted to neutral charcoal; page layout now full-width; added a collapsible upload section with `sessionStorage` persistence.

## [8.1.1] - 2026-02-11
### Changed
- Restyled Connection Browser to match the Ignition 8.3 gateway dark theme.

## [8.1.0] - 2026-02-11
### Added
- Connection Browser merging File Upload and Tag Browser into a single page with a device selector, drag-and-drop upload, and tag tree.

### Changed
- Gateway navigation collapsed to a single "Connection Browser" entry; old routes still serve the merged page for backwards compatibility.

### Removed
- Standalone `simple-upload.html` and `tag-browser.html` pages and their React components; "Supported Formats" block removed from the upload UI.

## [8.0.0] - 2026-02-11
### BREAKING CHANGES
- Module renamed from "Enhanced PLC Simulator" to "Logix PLC Emulator"; module ID, Java package, URL paths, storage directory and output filename all changed. Existing device configurations need to be re-created.

### Removed
- Siemens, Schneider Electric, Beckhoff, ABB, Mitsubishi and Omron parsers (unused vendor support).

### Added
- SINT simulation support; bulk simulation enable/disable by scope and for all devices; automatic storage-directory migration from the old `plc-simulator` path.

### Changed
- Module focused exclusively on Rockwell Logix emulation (L5K, L5X, JSON, CSV).

## [7.0.1] - 2025-11-25
### Fixed
- Route authentication replaced with the SDK's built-in `PermissionType.WRITE`, so routes now properly require Gateway login.
- HTML pages moved from public `/res/` to authenticated `/data/` routes; public index now redirects to the authenticated route.

### Removed
- Unused `AuthenticationHelper.java`, replaced by SDK APIs.

## [7.0.0] - 2025-11-25
### Added
- Omron parser (CX-Programmer and Sysmac Studio formats), bringing vendor coverage to 92% of the global market across seven vendors.
- `IncrementalAddressSpaceUpdater`: hot-reload updates values only when structure is unchanged, avoiding a full OPC-UA disconnect/rebuild.
- Tag Browser page: a tag-tree REST endpoint plus a visual tag-exploration UI with search/filter and auto-refresh.

### Changed
- `L5KParser` reduced 843 to 378 lines by extracting `UDTDefinition` and `RockwellBuiltInTypes`; `FileUploadRoutes` reduced 1067 to 304 lines by extracting `PathSecurity`, `AuthenticationHelper` and `DeviceFileManager`.

### Removed
- Python parser service and its wrapper class (~75MB), superseded by pure-Java parsers since v2.0.0.

## [6.5.0] - 2025-11-24
### Added
- Mitsubishi Electric parser (GX Works CSV, iQ-Platform) and ABB parser (Automation Builder/Control Builder Plus), bringing vendor coverage to 85%.

## [5.4.9] - 2025-11-22
### Security
- Fixed a critical XXE vulnerability in `L5XParser` (external entities, DTD loading and XInclude all disabled).
- Fixed a critical authentication-bypass vulnerability in `FileUploadRoutes`: removed a fallback path with a 1-second session-age bypass, replaced with proper `SecurityContext` validation.
- Fixed a high-severity path-traversal vulnerability: filename/device-name sanitisation and canonical path validation now prevent escaping the storage directory.
- Removed hardcoded module-signing credentials from `gradle.properties`; moved to environment-variable configuration with a committable template.
- Fixed a file-size DoS vulnerability: `Content-Length` is validated before the request body is read, with a 50MB streaming limit enforced.

### Added
- 40-test unit suite covering the parser, file validator and upload-route security fixes, including an XXE-prevention test.
- GitHub Actions CI pipeline: build/test on every push/PR, dependency caching, credential scanning, artifact retention.
- `ARCHITECTURE.md` and `SECURITY.md` documentation.

### Changed
- Gson bumped 2.10.1 to 2.11.0; modl plugin bumped 0.4.0 to 0.5.0.
- Downgraded from Java 21 to Java 17 for Ignition 8.3.1 compatibility (resolved an `UnsupportedClassVersionError` on the gateway).

## [5.4.8] - 2025-11-22
### Security
- All file-upload and device-management API routes now require an authenticated Gateway session; only `/health` and `/auth/status` remain public.

### Added
- Authenticated `/page` route serving the upload page through a data route (replacing the public HTML resource).

### Removed
- Deprecated `getStatusPanels()` method (unsupported on Ignition 8.3+).

### Changed
- Authentication moved server-side; client-side login-gate JavaScript removed.

## [5.4.1] - 2025-11-21
### Fixed
- CRITICAL: corrected a resource-mounting double-nesting bug that 404'd every HTML page — files were nested under `mounted/res/plcsimulator/` when Ignition's mount-path alias already adds that prefix; moved to `mounted/` directly.

### Changed
- Documented the resource-mounting behaviour in `SimulatorModuleHook` to prevent recurrence.

## [4.0.1] - 2025-11-19
### Fixed
- CRITICAL REGRESSION: restored the hierarchical UDT browse structure removed in v4.0.0 — UDT instances are Object nodes with dot-notation NodeIds and simple-name BrowseNames for their members, so both short and long tag paths resolve correctly again.

### Changed
- UDT structure reference type changed to `HasComponent`; UDT instances aliased at the device root (not just their members) to preserve short-path access.

## [4.0.0] - 2025-11-19
### Changed
- Flattened UDT instances into individual variables with dots in BrowseNames, aliasing all `Controller:Global` tags to the device root, intending short-path access (`Motor1.ENABLE`) without a `Controller:Global` prefix.

### Deprecated
- DEPRECATED — DO NOT USE. Removed browsable UDT folders entirely, breaking tag-tree navigation; reverted in v4.0.1.

## [3.0.0] - 2025-11-18
### Added
- Full expansion support for all Studio 5000 predefined structured types (22 total, up from 4): PID, PIDE, ALARM_ANALOG, ALARM_DIGITAL, AXIS_CIP_DRIVE, AXIS_VIRTUAL, AXIS_SERVO_DRIVE, MOTION_GROUP, CAM, CAM_PROFILE, COORDINATE_SYSTEM, PHASE, EQUIPMENT_SEQUENCE, FBD_TIMER, FBD_COUNTER.

### Changed
- MESSAGE structure expanded from 7 to 11 members to match the full specification.

## [2.6.0] - 2025-11-18
### Fixed
- Built-in structured types (TIMER, COUNTER, CONTROL, MESSAGE) now expand into folders with their real members instead of appearing as single String nodes.

## [2.5.1] - 2025-11-18
### Fixed
- Device status fetch errors now show the HTTP status and a helpful message instead of a generic failure, with console logging for debugging.

## [2.5.0] - 2025-11-18
### Added
- File status visibility on the upload page: current filename, size and upload date, or a "no file uploaded" message.
- Delete-file functionality with a confirmation dialog and a DELETE endpoint.

### Changed
- Device status API now returns file metadata (`hasFile`, `fileSize`, `lastModified`, `filePath`).

## [2.4.2] - 2025-11-18
### Changed
- Parser-type description and upload page now list a multi-vendor roadmap (Siemens, Schneider, Beckhoff, JSON) as planned future support.

## [2.4.1] - 2025-11-18
### Changed
- Restricted to L5K files only: parser-type dropdown, file picker and documentation now cover Rockwell L5K exclusively; other parsers commented out for future development.

## [2.4.0] - 2025-11-18
### Fixed
- Removed the redundant "Device Name" config field; device name now comes from the device connection name, so renaming the connection updates it everywhere.

### Changed
- Uploaded files now persist across gateway restarts, saved with device-specific filename prefixes.

## [2.0.5] - 2025-11-11
### Added
- Device config now displays the full clickable gateway URL for the file-upload page, plus a "Direct URL" row with a copy button.

## [2.0.4] - 2025-11-11
### Added
- Device config gains a clickable "Manage PLC Program" field linking to the file-upload page with the device name pre-filled.

## [2.0.0] - 2025-11-11
### Added
- Complete device-update API: uploaded files are now automatically applied to the device (parsed, tags created) with no manual copy/paste step.
- Gateway sidebar menu ("PLC Simulator") with a device-management dashboard, replacing the unrendered clickable-link workaround.
- Java parsers for L5X, JSON and CSV (no Python dependency); simulation engine with five patterns (STATIC/RAMP/SINE/RANDOM/TOGGLE); polling-based hot-reload file watcher; file versioning (last 5 kept, with rollback).
- Comprehensive upload validation (size limits, format/content checks).

### Changed
- BREAKING: module refactored from `AbstractDeviceModuleHook` to `AbstractGatewayModuleHook` to enable Gateway Config sidebar integration; device driver functionality preserved.
- File storage moved off a hardcoded path onto Ignition's data directory API, making it cross-platform.

### Known limitations
- Hot reload always does a full address-space rebuild; Siemens/Schneider/Beckhoff parsers remain unimplemented.

## [1.5.1] - 2025-11-10
### Fixed
- CRITICAL: route mounting failed with "Access control must be specified" — `/upload` and `/devices` now specify `authenticated()`, `/health` specifies `unrestricted()`; route mounting is now wrapped per-route in try-catch so one failing route no longer blocks the rest.

### Added
- Auto-injected clickable "Program Manager" link in device config, built from the device name and the gateway's own origin.

## [1.5.0] - 2025-11-10
### Changed
- Device configuration form redesigned: large file-content textarea replaced with a prominent "Manage PLC Program" link field; internal file-content field demoted and moved to the bottom.

### Added
- Device-specific upload URLs (`?device=Name`) and a matching per-device upload API endpoint.

## [1.4.0] - 2025-11-10
### Added
- Dark theme for the Edit Program page.

### Fixed
- File upload on the Edit Program page is now fully implemented (previously a placeholder), with async upload, progress state and error handling.

## [1.3.2] - 2025-11-10
### Fixed
- Attempted JavaScript-injected upload button in device configuration; may not work on all Ignition versions due to SDK limitations.

### Added
- `QUICK_START.md` documenting all three upload methods and why Edit Program cannot be added to the device dropdown menu.

## [1.3.1] - 2025-11-07
### Added
- Landing page at `/res/plcsimulator/` with a quick-start guide and links to Edit Program and Device Config.

### Changed
- Removed an attempted Gateway Config integration, incompatible with `AbstractDeviceModuleHook`.

## [1.3.0] - 2025-11-07
### Added
- Edit Program page: dedicated drag-and-drop file-import interface at `/res/plcsimulator/edit-program.html`, plus `EDIT_PROGRAM_GUIDE.md`.

## [1.2.1] - 2025-11-07
### Changed
- File content and filename are now fully optional when creating a device; devices without a file start in "Ready - waiting for file upload" status.

## [1.2.0] - 2025-11-07
### Added
- Working file upload via `FileUploadRoutes` (`POST /upload`, `GET /health`) with an auto-injected client-side upload button supporting L5K/JSON/CSV/XML.

## [1.1.0] - 2025-11-07
### Fixed
- Attempted file-upload feature via web resources; blocked by `AbstractDeviceModuleHook` limitations and not functional in this release — users must still copy/paste file content.

## [1.0.10] - 2025-11-06
### Fixed
- Parser-type dropdown now shows friendly names ("Rockwell L5K (Allen-Bradley)") via a `toString()` override instead of the raw enum name.

## [1.0.9] - 2025-11-06
### Fixed
- i18n bundle was never registered, so display names rendered as `?EnhancedSimulator.Meta.DisplayName?`; fixed by calling `BundleUtil.get().addBundle()` on module startup.

## [1.0.5-1.0.8] - 2025-11-05 to 2025-11-06
### Deprecated
- These versions shipped with display-name and i18n issues (fixed in v1.0.9/v1.0.10); deprecated, upgrade recommended.

## [1.0.1] - 2025-11-03
### Changed
- Vendor name updated to "Gaskony" across module metadata.

## [1.0.0] - 2025-11-02
### Added
- Initial release: device-driver architecture on `AbstractDeviceModuleHook` with Rockwell L5K and JSON parsers fully implemented (Siemens/Schneider/Beckhoff placeholders), device configuration schema, i18n resource bundle, and self-signed module signing.
