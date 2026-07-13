# Known Issues and Limitations

## Current Version: v10.0.0

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

#### 4. Predefined Motion/Coordinate Structured Types Are Incomplete Member Sets

**Status**: Known Limitation (v10.0.0, ADDRESSING.md §3.11.1 policy)
**Severity**: Low (affects motion/coordinate tags only)

`AXIS_CIP_DRIVE`, `AXIS_VIRTUAL`, `AXIS_SERVO_DRIVE`, `MOTION_GROUP` and `COORDINATE_SYSTEM`
are real Rockwell predefined types with hundreds of members each (approximate DOC-CONFIRMED
counts: AXIS_CIP_DRIVE ~468, AXIS_SERVO_DRIVE ~200-260, AXIS_VIRTUAL ~110-150,
COORDINATE_SYSTEM ~80-120, MOTION_GROUP ~12). No public Studio 5000 export containing these
types has been found, so full fidelity cannot be corpus-tested in v10.

**Current behaviour**: `RockwellBuiltInTypes` emits only the ~10-15 most-referenced members for
the AXIS_* family (INFERRED: ActualPosition, CommandPosition, ActualVelocity, CommandVelocity,
ActualAcceleration, CommandAcceleration, PositionError, AverageVelocity, MasterOffset, AxisFault,
AxisState, MotionStatus, ServoActionStatus, DriveEnableStatus, plus ActualTorque and
MotorVelocityFeedback for AXIS_CIP_DRIVE), so common motion faceplate bindings line up. Both
MOTION_GROUP and COORDINATE_SYSTEM retain their pre-v10 placeholder member sets unchanged —
these are best-effort and not verified against a real export.

**Impact**: A tag/binding referencing a AXIS_*/MOTION_GROUP/COORDINATE_SYSTEM member outside the
minimum set above will not resolve on the emulator even though it would on a real controller.

**Workaround**: None currently. The full member lists are documented in Rockwell's MOTION-RM003
(CIP drives) / 1756-RM007 (servo) manuals and are best captured from a live-controller pycomm3
template read or an IA bench export — deliberately not hand-authored from memory here, per
policy (see `docs/plans/ADDRESSING.md` §3.11.1).

---

#### 5. Bit-of-Integer Addressing (`Tag.b`) Not Implemented

**Status**: Not Implemented (post-v10, ADDRESSING.md §3.9)
**Severity**: Low-Medium (only affects bindings that address a single bit of an
atomic integer directly, e.g. `Status.5` on a DINT)

Real Logix tags support addressing a single bit of an atomic integer by appending a
decimal bit number (`Tag.b`), e.g. `Status.5` (SINT: bits 0-7, INT: 0-15, DINT: 0-31,
LINT: 0-63). The emulator has **no support for this at all** in v10 — neither
pre-creating the bit nodes nor resolving them on demand.

**Why deferred:** this is a deliberate maintainer decision, not an oversight, blocked
on two open questions:

1. The bit-width bounds above are INFERRED (they follow logically from the integer
   width) rather than confirmed against a real driver browse.
2. Whether bit nodes should be pre-created at address-space build time or resolved
   on demand is unresolved. Pre-creating all bits for every atomic integer risks a
   node-count explosion on large controller exports (a single DINT array of 1000
   elements would add up to 32,000 extra nodes); resolving on demand is cheaper but
   changes the address-space's node-count-at-build-time invariant the rest of the
   emulator currently assumes.

**Impact**: a binding that addresses `Tag.b` directly on a plain atomic integer
(rather than a named bit member of a predefined type, which IS supported — see
§3.11) will not resolve on the emulator even though it would on a real controller.

**Workaround**: None currently. Confirming this against a real driver bench test
(or a `pycomm3` live browse) is the prerequisite for scoping the fix; see
`docs/plans/ADDRESSING.md` §3.9.

---

#### 6. Structure-Member Initial Values Not Read From Export (Array Element Values Now Supported)

**Status**: Partially resolved (v10.0.0, FIX-15) — structure-member values remain
deferred (ADDRESSING.md §3.10a — C8 scope decision, narrowed by FIX-15)
**Severity**: Low (affects only the STARTING value of structure members; the tag
still exists, is browsable, and is writable as normal)

C8 (v10.0.0) added reading of a scalar atomic tag's initial value from its L5X
`<DataValue Value="...">` element, but at first deliberately did not read initial
values nested inside a structure or a top-level array. **FIX-15 closes the
array-element half of that gap**: a top-level array tag's decorated
`<Array><Element Index="..." Value="..."/></Array>` block is now parsed (including
multi-dimensional indices and DWORD-packed BOOL arrays), so e.g. an exported
`RealArray[2]=42.5` now appears on the emulator's `RealArray[2]` node instead of the
REAL type default, and a value-only change to a single array element is now detected
and applied on hot-reload.

**Still deferred**: a UDT/AOI/predefined instance's `<DataValueMember>` elements
(e.g. a `TIMER` instance's `.PRE`) and any array *nested inside* a UDT/AOI instance
(a member array's per-element `<Element>`/`<ArrayMember>` values, or an array-of-UDT
element's `<Structure Index="...">` member values) are still NOT read — every such
structure member starts at its type-appropriate default (`0`, `false`, `""`, etc.)
regardless of what the export actually specifies. A real export with
`TIMER.PRE=5000` still starts the emulator's copy at `PRE=0`.

**Impact**: any binding or test that depends on a *structure member's* initial value
matching the export (rather than being written afresh at runtime) will see the type
default instead. Top-level array elements are no longer affected.

**Workaround**: write the expected initial value via REST/OPC-UA immediately after
the device comes up, or treat the export's per-structure-member initial values as
non-authoritative for the emulator. See `docs/plans/ADDRESSING.md` §3.10a for the
scope decision and `L5XParser.extractValue()`/`extractArrayElementValues()`'s
Javadoc for the code-level detail.

---

#### 7. File Version Snapshots Use Second-Granularity Timestamps

**Status**: Known Limitation (post-v10, identified during the v10.0.0 docs/release pass)
**Severity**: Low (only matters for uploads separated by less than one second)

`FileVersionManager.saveVersion()` names each retained version file with a
`yyyyMMdd_HHmmss` timestamp (second precision). Two uploads for the same device
landing within the same second produce an identical version filename, and the
second `Files.copy(..., REPLACE_EXISTING)` silently overwrites the first —
one of the two versions is lost rather than both being retained.

**Impact**: extremely rapid successive uploads (scripted/automated re-upload, or
two operators uploading near-simultaneously) can silently drop a version from
the retained-5 history instead of pushing out the oldest one.

**Workaround**: none currently; space uploads by at least one second if
retaining every intermediate version matters. Accepted as a deferred,
non-blocking follow-up rather than part of v10.0.0 — fixing it means moving to
a sub-second or monotonic version discriminator (e.g. an appended counter) in
`FileVersionManager`.

---

#### 8. No Locking Between Concurrent Upload/Revert Operations

**Status**: Known Limitation (post-v10, identified during the v10.0.0 docs/release pass)
**Severity**: Low-Medium (only affects overlapping REST calls against the same device)

`DeviceFileManager`'s upload path and `FileVersionManager`'s `saveVersion()` /
`restoreVersion()` perform their file-copy and directory-listing operations
without any per-device lock. An upload and a version revert (or two uploads)
issued for the same device at effectively the same time can interleave: a
revert can restore a version concurrently with an in-flight upload writing the
same target file, or two uploads can both read the pre-upload file for
versioning before either has written its replacement.

**Impact**: under concurrent REST calls against one device, a revert or upload
can race and leave the on-disk file (or the retained-version set) in a
state that does not cleanly correspond to either individual request. Normal
single-operator usage is not affected.

**Workaround**: none currently; avoid issuing overlapping upload/revert
requests for the same device. Accepted as a deferred, non-blocking follow-up —
fixing it requires a per-device lock (or serialising) around the
prepare/save/revert file operations in `FilePreparation`/`FileVersionManager`.

---

#### 9. L5K Alias Tag Type/Read-Only Resolution Is a Minimum-Viable Heuristic

**Status**: By Design (v10.1.0, L5K-GRAMMAR.md §2.2 scope decision - deliberately deferred, not
implemented, from the v10.1 L5K parser review)
**Severity**: Low (affects only the reported `data_type`/read-only flag of `OF`-alias tags; the
alias tag itself is still emitted and browsable)

An L5K alias tag declaration (`<name> OF <target> [(attrs)];`, L5K-GRAMMAR.md §2.2) is a reference
to a base tag, bit, or module I/O channel - its real data type and access are strictly the
resolved target's, which a full implementation would look up. `L5KParser.TagStatementBuilder`
instead uses a **minimum-viable heuristic** (`resolveAliasType`): a `.bit`-suffixed target always
resolves to `BOOL`; otherwise the alias's own `RADIX` attribute (when present) picks `REAL`
(`Float`/`Exponential`) or `SINT` (`ASCII`); failing both, an opaque `DINT` leaf is assumed.

**Why deferred:** L5K-GRAMMAR.md §2.2 explicitly scopes full alias-to-target type/value resolution
to a **future Phase-2 enhancement**, not v10.1 - the grammar only requires the parser to emit the
alias as a leaf node with an `alias_for` target string, not to walk the target chain and mirror its
actual type/access. This was re-confirmed during the independent v10.1 parser review (review
finding #5): the heuristic is correct behaviour *for this phase*, not an oversight.

**Impact**: an alias whose target is neither a `.bit` reference nor RADIX-tagged Float/ASCII will
be reported as `DINT` even if the real target is, say, a `REAL` or a UDT member of another type; a
read-only target's alias may not correctly inherit read-only status unless the alias's own
attributes independently indicate it. File A's 572 alias tags (L5K-GRAMMAR.md §3.8) are affected
by this heuristic to varying degrees depending on their targets.

**Workaround**: none currently; treat an alias tag's `data_type`/read-only flag as best-effort
until full target-chain resolution is implemented. See `docs/plans/L5K-GRAMMAR.md` §2.2 for the
grammar-level scope decision and `L5KParser.TagStatementBuilder.resolveAliasType()`'s Javadoc for
the code-level detail.

---

## Reporting New Issues

If you encounter issues not listed here:

1. **Check Gateway Logs**: `Status > Logs > Gateway` (filter for "logixemulator")
2. **Verify Module Version**: Config > Modules (should show v10.0.0)
3. **Check Device Status**: Config > Devices > Edit device (status field shows current state)

**Report Issues With:**
- Ignition version (e.g., 8.3.2)
- Module version (e.g., v10.0.0)
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
