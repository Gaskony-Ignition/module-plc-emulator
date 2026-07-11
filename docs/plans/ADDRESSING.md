# ADDRESSING.md — Normative NodeId / Browse Grammar for the Logix PLC Emulator

**Task:** C0 of the v10.0.0 fidelity plan. This is the single normative document
the Stage C fidelity fixes (C1–C6) and their `@Tag("fidelity")` tests are written
against. It defines the exact OPC-UA **NodeId identifier** string the emulator must
emit for each Logix construct so that a tag binding developed against the emulator
survives a swap to Ignition's native Allen-Bradley **Logix** driver.

**Status:** DRAFT for lead review. Do NOT commit until reviewed.
**Author:** C0 Opus design agent, 2026-07-10.
**Supersedes for addressing purposes:** the ad-hoc scheme in
`AddressSpaceBuilder.java` and the member tables in `RockwellBuiltInTypes.java`.

---

## 0. The contract, restated

An Ignition OPC tag binds to `ns=<n>;s=[DeviceName]<identifier>`. Both the emulator
and the real Logix driver are Ignition OPC-UA *device* drivers, so both receive the
standard `[DeviceName]` wrapper automatically (verified:
`AddressSpaceLifecycle.createRootNode()`). **The only string that must match across
the swap is the `<identifier>` suffix — the NodeId identifier the builder assigns to
each variable node.** Browse-tree folder structure is a secondary, cosmetic concern
(see §3.13); the NodeId is the binding contract.

Each rule below is tagged **DOC-CONFIRMED** (with source) or **INFERRED** (with
reasoning). "DOC-CONFIRMED" means confirmed against an Inductive Automation doc, an
IA-staff forum post, a verbatim Rockwell reference-manual table, OR a genuine
Studio 5000 export in the vendored corpus — the wire-level ground truth. INFERRED
items are the ones a real IA bench test (or `pycomm3` template read against a live
controller) would need to confirm.

### Target reference — the reference target is the *modern* Logix driver

Ground truth throughout is IA's **modern "Allen-Bradley Logix"** driver (firmware v21+
behaviour), NOT the legacy ControlLogix driver. The legacy driver used a `Global.`
prefix for controller tags and omitted the `Program:` selector; the emulator must
**not** reproduce legacy forms. Firmware v21+ removed the `Global.` prefix requirement
(DOC-CONFIRMED — IA migration guidance).

---

## 1. Vendor-neutrality boundary (maintainer direction 10/07/2026)

Rockwell addressing is a **per-vendor policy layered on a vendor-neutral parsed-tag
model**. A future vendor parser (Siemens, Modbus, …) would supply its own addressing
policy without touching the node-creation machinery. The boundary sits in three layers:

```
  ┌─────────────────────────┐   vendor-specific   parses one export format into the
  │  Parser  (L5XParser, …) │   ───────────────►  common parsed-tag model (JSON):
  └─────────────────────────┘                     scope, name, base type, dims,
              │  parsed-tag model (vendor-neutral)  members[], externalAccess, …
              ▼
  ┌─────────────────────────┐   VENDOR POLICY     given (scope, tagName, memberPath,
  │  AddressPolicy          │   ───────────────►  arrayIndex[], bitIndex) returns the
  │  = RockwellLogixPolicy  │   the ONLY place    NodeId identifier string, decides
  └─────────────────────────┘   Rockwell rules    which nodes exist / are hidden /
              │  NodeId strings + visibility        read-only, and how BOOL arrays pack
              ▼
  ┌─────────────────────────┐   vendor-neutral    walks the model, asks the policy for
  │  AddressSpaceBuilder    │   ───────────────►  each identifier, creates UaNodes.
  │  (node creation)        │   NO vendor rules   Contains NO "Program:"/BOOL-pack/
  └─────────────────────────┘                     member-table logic.
```

**Where the current code violates this** and what C1–C6 must move:

- `AddressSpaceBuilder` currently hardcodes `"Controller:Global"`, the `Programs/` path,
  the `pathPrefix.replace("/", ".")` join, and the BOOL/array element loop. All of that
  is Rockwell policy and must move behind an `AddressPolicy` interface.
- `RockwellBuiltInTypes` (the predefined member tables) is already vendor-specific and
  belongs on the Rockwell side of the boundary — but it is consumed today by the parser.
  Keep the *tables* Rockwell-scoped; the builder must never name a Rockwell type.
- Minimal interface (illustrative, not an implementation mandate):
  `String nodeId(TagRef ref)`, `boolean isBrowsable(TagRef ref)`,
  `boolean isWritable(TagRef ref)`, `List<ElementRef> expand(TagRef ref)`.

No implementation is prescribed here beyond "the Rockwell rules in §2–§3 live in the
policy layer, not in `AddressSpaceBuilder`."

---

## 2. Alias strategy — the v10 node scheme (RECOMMENDED)

### 2.1 What the current code does (the problem)

For controller-scoped tags, `AddressSpaceBuilder` creates **two** nodes per tag
(`AddressSpaceBuilder.java` ~197-228, 367-375):

1. a "long" node under the `Controller:Global` folder with NodeId
   `Controller:Global.<tag>`, and
2. a "short" **duplicate** node at the device root with NodeId `<tag>`,

kept in sync by a bidirectional `enableSynchronizedWrites` filter pair
(`WriteSyncHelpers` re-entrancy guard). Program tags get only the broken
`Programs.<Prog>.<tag>` long form and **no** alias at all (the `rootNode == null` gate
at `:135`). Nested controller members below depth 1 exist only on the long path (#3).

### 2.2 v10 decision: ONE canonical node per tag, no duplicates, no sync

**Each parsed tag maps to exactly one variable node whose NodeId identifier is the
driver-canonical form.** Delete the duplicate-node + synchronised-write subsystem
entirely (`enableSynchronizedWrites`, `WriteSyncHelpers`, the short/long pairing).

| Scope | v10 canonical NodeId | Old (pre-v10) forms removed |
| ----- | -------------------- | --------------------------- |
| Controller | `<tag>` (bare) | `Controller:Global.<tag>` long form + root duplicate |
| Program | `Program:<Prog>.<tag>` | `Programs.<Prog>.<tag>` |

**Justification for a single canonical node over duplicated synchronised nodes:**

1. **Correctness.** The real modern driver exposes exactly one identifier per tag
   (bare for controller, `Program:`-prefixed for program). Duplicate nodes mean the
   emulator answers to a path the real driver does not (`Controller:Global.<tag>`),
   so a binding developed against that path silently dies on swap — the exact failure
   the charter forbids.
2. **The sync machinery is pure liability.** Two nodes + a re-entrancy-guarded write
   mirror is a race-prone workaround for a self-inflicted duplication. One node has one
   value; nothing to synchronise. It also removes the depth-1-only alias bug (#3)
   because there is no second tree to keep in step.
3. **Simulation wiring is simpler.** The engine writes one node; no "which twin did the
   tick update?" ambiguity (relevant to defect B3).

The canonical NodeId is **bare `<tag>` for controller scope** and
**`Program:<Prog>.<tag>` for program scope**. There is no "alias" concept in v10 —
there is one node, correctly named.

### 2.3 Browse folders are allowed but must not leak into NodeIds

pturmel's driver-mirror browse tree groups controller tags under a `Controller:Global`
*display folder* and program tags under `Program:<Name>` folders, while the item paths
remain bare / `Program:`-prefixed respectively. OPC-UA NodeId is independent of browse
hierarchy, so the emulator MAY keep browse folders for human navigation **provided the
child node's NodeId is the canonical identifier above, not the folder-qualified path.**
Recommended browse tree (§3.13) mirrors the real driver. If in doubt, prefer fewer
folders — the NodeId is the contract, folders are cosmetic.

---

## 3. NodeId grammar per construct

Global quick-reference (all forms are the `<identifier>` after `[Device]`):

| # | Construct | Canonical NodeId identifier | Confidence |
| - | --------- | --------------------------- | ---------- |
| 1 | Controller (global) tag | `TagName` | DOC-CONFIRMED |
| 2 | Program-scoped tag | `Program:<Prog>.TagName` | DOC-CONFIRMED |
| 3 | UDT/AOI member (nested) | `Tag.Member.Sub` | DOC-CONFIRMED |
| 4 | 1-D array element | `Tag[i]` | DOC-CONFIRMED |
| 5 | Multi-dim array element | `Tag[i,j]`, `Tag[i,j,k]` (≤3 dims, commas) | DOC-CONFIRMED |
| 6 | Array of UDT/predefined | `Tag[i].Member` | DOC-CONFIRMED |
| 7 | Array member inside a UDT | `Tag.Member[i]` | DOC-CONFIRMED |
| 8 | BOOL array | `Tag[N/32].(N%32)` only; bare `Tag[N]` must NOT exist | DOC-CONFIRMED |
| 9 | Bit of an atomic integer | `Tag.b` (DINT 0-31) | DOC-CONFIRMED syntax / INFERRED range |
| 10 | STRING | value at `Tag` + `Tag.LEN` + `Tag.DATA[i]` | DOC-CONFIRMED |
| 11 | Predefined struct member | `Tag.PRE`, `Tag.ACC`, … (see §3.9 tables) | DOC-CONFIRMED (per table) |
| 12 | Module I/O tag | `Module:I.Data`, `Local:s:I.Data`, `:O`, `:C` | INFERRED |
| 13 | External Access = None | node NOT created (omitted from browse + I/O) | DOC-CONFIRMED |
| 14 | External Access = Read Only | node created READ-ONLY | DOC-CONFIRMED |

### 3.1 Controller-scoped (global) tags — **DOC-CONFIRMED**

NodeId = the bare Logix tag name, no prefix, no wrapper: `SystemClock`, `Motor_1`.

- **DOC-CONFIRMED**: IA migration guidance — firmware v21+ removed the `Global.`
  prefix; the modern driver addresses controller tags bare. pturmel's drop-in-compatible
  EtherNet/IP module documents the simplest tagpath as literally `tag`
  (automation-pros.com/enip1/UserManual.pdf §8.1.1).
- **Do NOT** emit `Controller:Global.<tag>` — that half-echoes the *legacy* driver and
  is not a modern-driver path. (The string `Controller:Global` is fine as a browse
  *folder label*; see §3.13.)

### 3.2 Program-scoped tags — **DOC-CONFIRMED**

NodeId = `Program:<ProgramName>.<TagName>` — the literal word `Program`, a colon, the
program name, a dot, then the tag name. **Singular `Program`, colon then dot.**

- **DOC-CONFIRMED**: IA docs / guidance "add the `Program:` prefix to OPC Item Paths";
  verbatim IA-forum examples `[myPLC]Program:IgnitionData.Trigger` and
  `[avlProleit 2]Program:_003_CIP_Loop_3.TagName`; this is exactly Studio 5000's own
  program-tag notation, which is the design intent.
- This is the single highest-impact fix (C1). The old `Programs.<Prog>.<tag>` form
  (wrong word, wrong separators, no `Program:` selector) breaks essentially every
  program-scoped binding on swap. Members and array elements inherit the prefix:
  `Program:<Prog>.<tag>.<member>`, `Program:<Prog>.<tag>[i]`.

### 3.3 UDT / AOI members and nesting — **DOC-CONFIRMED**

Dotted, nests to arbitrary depth: `Tag.Member`, `Tag.Member.Sub`. Array-of-struct
combines with subscripts: `Tag.Member[3].Sub` and `Tag[3].Member`.

- **DOC-CONFIRMED**: pturmel manual §8.1.1 verbatim examples `tag.member`,
  `tag.member[3].submember`; standard Logix/CIP symbolic-path structure.
- The current builder already nests member NodeIds correctly on the long path
  (`addUdtMember` recurses); the fix is only to (a) root them at the canonical prefix
  (§2.2) and (b) expand array members (§3.6/§3.7).
- **AOI backing tags** expose `EnableIn`, `EnableOut` and Input/Output parameters plus
  *visible* local tags. Current code DROPS `EnableIn`/`EnableOut` (#16) — restore them.
  **InOut parameters are references, not backing-tag members** — exclude them from the
  emitted member set (they resolve to the referenced tag, not to storage in the AOI
  instance). Filter AOI parameters by `Usage`: emit `Input`/`Output`, drop `InOut`.

**Nested-AOI caveat (behavioural fidelity) — DOC-CONFIRMED:** "Reading AOIs within AOIs
is not supported by Allen-Bradley" (IA/AB guidance; Kevin Herron notes the firmware-v21+
external-access deprecation as the root cause). Local tags inside an AOI definition
default to `ExternalAccess = None`, so nested-AOI members are normally **not browsable**
on real hardware. The emulator currently over-exposes them (recursive depth-10
expansion, #32). Under §3.10 (External Access) these `None` locals are dropped anyway,
which brings the emulator into line — **do not special-case nested AOIs; just honour
External Access and they fall out correctly.**

### 3.4 1-D array elements — **DOC-CONFIRMED**

`Tag[i]`, zero-based, `i` in `0 .. dim-1`. Program scope: `Program:<Prog>.Tag[i]`.

- **DOC-CONFIRMED**: pturmel §8.1.1; corpus `INFO_ABOUT` (INT[2]) → `INFO_ABOUT[0]`,
  `INFO_ABOUT[1]`.

### 3.5 Multi-dimensional array elements — **DOC-CONFIRMED**

`Tag[i,j]` and `Tag[i,j,k]` — **comma-separated indices inside ONE set of square
brackets**, up to **3 dimensions** (Logix hard limit). NOT separate brackets, NOT
first-dimension-only.

- **DOC-CONFIRMED**: pturmel §8.1.1 "Multiple consecutive subscripts may be
  comma-delimited within one set of square brackets" (`tag[3,7]`), 3-D parse
  `arrayTag[2,3,4]`; corpus decorated data shows exactly this — `Index="[0,0]"` …
  `Index="[1,3]"` for `Dimensions="2 4"`, and `Index="[0,0,0]"`/`Index="[0,0,1]"` for
  `Dimensions="1 1 2"`.
- **Index order / parser note:** the L5X **`Dimensions` attribute is space-separated**
  (`"2 4"`, `"3 5"`, `"1 1 2"`) while the decorated `<Array Dimensions>` and the
  addressing form are **comma-separated**. The element index order matches the decorated
  `<Element Index="[i,j]">` order (first listed dimension is the first/outer subscript,
  verified against the corpus). **Robust rule for the policy:** when decorated
  `<Element>` entries are present, derive index ranges/order from them; otherwise expand
  from `Dimensions` in listed order. This sidesteps any Rockwell dimension-order
  ambiguity. The current code splits on comma and keeps only `dims[0]` — both the
  separator and the drop-to-1-D are wrong (C2).

### 3.6 Arrays of UDT / predefined — **DOC-CONFIRMED**

`TIMER[5]`, `MyUDT[10]` fully expand: `Tag[0].Member … Tag[4].Member`.

- **DOC-CONFIRMED**: standard Logix (array-of-struct is `Tag[i].Member`); corpus
  contains such arrays. Current code `return`s from the UDT branch before the array
  branch (`AddressSpaceBuilder.java` :232 short-circuits :237), so only one un-indexed
  instance is created (#6). Fix: expand the array first, then expand each element's
  members (C2).

### 3.7 Array member inside a UDT — **DOC-CONFIRMED**

A UDT member declared with a dimension (`DINT Data[10]`) expands per element:
`Tag.Data[0] … Tag.Data[9]`. For a struct member array: `Tag.Member[i].Sub`.

- **DOC-CONFIRMED**: pturmel `tag.member[25]`; corpus UDTs carry `Dimension="n"` on
  `<Member>` and `<ArrayMember>`. `addUdtMember` currently ignores dimensions and emits
  a single scalar (#7) — fix in C2.

### 3.8 BOOL arrays — DWORD-packed — **DOC-CONFIRMED (official IA docs)**

A `BOOL[N]` is exposed as a packed array of 32-bit DWORDs. **Only** `Tag[word].bit`
nodes exist; a bare `Tag[element]` BOOL node **must NOT be created**, and a read of one
must fail exactly as the real driver does.

**Packing rule (normative):** element `N` → `Tag[N / 32].(N % 32)` (integer division →
word index; modulo → bit index). Number of DWORD words = `ceil(N / 32)`.

| PLC BOOL element | Emitted NodeId |
| ---------------- | -------------- |
| `boolTag[0]`  | `boolTag[0].0` |
| `boolTag[31]` | `boolTag[0].31` |
| `boolTag[32]` | `boolTag[1].0` |
| `boolTag[63]` | `boolTag[1].31` |

- **DOC-CONFIRMED**: IA docs *Connecting to Logix*, verbatim — "Boolean arrays items
  are browsed as members of a 32-bit DWORD. Thus, a `BOOL[64]` in the PLC is implemented
  as `DWORD[2]` in the driver," with the exact table above. Failure mode DOC-CONFIRMED
  on the IA forum: typing `VM01.GEN_ALARMS[2]` for a `BOOL[32]` yields "The node id
  refers to a node that does not exist in the server address space"; the browser reveals
  `VM01.GEN_ALARMS[0].2`.
- **Word-boundary / edge cases:**
  - Studio 5000 requires standalone BOOL array dimensions to be a **multiple of 32**
    (corpus confirms: every BOOL array is `Dimensions="32"`). The policy should still
    handle non-multiples defensively via `ceil` (**INFERRED**): a `BOOL[10]` → one DWORD,
    `Tag[0].0 … Tag[0].9`; bits `.10–.31` physically exist in the word but are outside
    the declared array and should not be emitted as array elements.
  - This is the emulator's biggest current backwards bug (#11/C3): it emits the exact
    per-element `Tag[i]` BOOL form the real driver **rejects**. C3 must replace it with
    the packed form and must **not** create the bare-element node.
- The emulator emits `Tag[w].b` NodeIds; the OPC-UA data type of each is `Boolean`
  (the value is one bit). Optionally the backing `DWORD` word node (`Tag[w]`, UInt32)
  may also be exposed — the real driver browses the DWORD as the container — but the
  binding contract that must match is the `Tag[w].b` bit node.

### 3.9 Bit of an atomic integer — **DOC-CONFIRMED (syntax) / INFERRED (range)**

`Tag.b` where `b` is a decimal bit number, e.g. `Status.5`. Valid range is the width of
the host integer: SINT 0-7, INT 0-15, DINT 0-31, LINT 0-63.

- **DOC-CONFIRMED (syntax)**: pturmel §8.1.1 (optional trailing dot-delimited bit
  number, `tag.member[0].5`); IA "addressing bits" guidance (append `.<bit>`, set the
  Ignition tag type to Boolean). Corpus alias tags reference bits directly:
  `AliasFor="_bSts.9"`, `AliasFor="_bSts.1"` — real Logix bit addressing on a DINT.
- **INFERRED (range)**: the 0-31-for-DINT (etc.) bound follows from the integer width;
  universally true but not quoted from a single IA table.
- **Emitter policy:** the emulator does not know at parse time which bits an application
  will bind. It need not pre-create all 32 bit nodes for every DINT. Minimum viable
  fidelity (C-scope): emit bit nodes only where the export references them (alias
  targets, and the named bits of predefined structs). Full `.0–.31` synthesis for every
  atomic integer is a **future** enhancement — mark any test asserting arbitrary
  `Tag.b` on a plain DINT as INFERRED until a live browse confirms the driver
  pre-creates them (it resolves them on demand, so pre-creation is an emulator choice).
- **v10 status: NOT IMPLEMENTED (post-v10, maintainer decision 11/07/2026).** Neither
  the "emit bit nodes only where referenced" minimum nor full `.0–.31` synthesis
  shipped in v10 — arbitrary `Tag.b` bit-of-integer addressing has no emulator support
  at all yet. Blocked on: the INFERRED range bounds above being bench-confirmed, and
  the pre-create-vs-on-demand question actually being resolved (on-demand is cheap
  per-bit but changes the address-space's node-count-at-build-time invariant the rest
  of the emulator assumes; pre-creating all bits for every atomic integer risks a
  node-count explosion on large controller exports). See `KNOWN_ISSUES.md` for the
  user-facing statement of this gap.

### 3.10 STRING — **DOC-CONFIRMED**

A Logix `STRING` (and any custom `STRING_n` / STRING-family type) is exposed **both** as
a single `String` value at the parent node **and** with browsable sub-members
`Tag.LEN` (DINT) and `Tag.DATA` (SINT array, element-addressable `Tag.DATA[i]`).

- **DOC-CONFIRMED**: Kevin Herron (IA staff), verbatim — "with the v21 driver the LEN
  and DATA members are always browsable underneath the String tag itself." Corpus
  `<DataType Name="STRING">` shows `LEN` (DINT) + `DATA` (SINT[n]) members; custom string
  types carry `DATA` dims of 23, 82, 100.
- **Value derivation (INFERRED but strongly documented):** the parent String value is
  derived from `DATA[0 .. LEN-1]`. If the export sets `DATA` but leaves `LEN = 0`, the
  parent reads empty — reproduce that (the driver reads by `LEN`).
- **Fix (C-scope, closes #19/#20):** plain `STRING` currently maps to a bare scalar
  `String` with no members; give it the `.LEN` + `.DATA[i]` structure. `STRING_n`
  currently over-expands to `.LEN` + un-expanded scalar `.DATA` — expand `DATA` per §3.7
  and also surface the parent String value.

### 3.10a Initial values — scope note (maintainer decision, C8/FIX-14, 11/07/2026)

This document does not otherwise specify initial-value derivation (it is a NodeId/
addressing grammar, not a value semantics one), but the scope boundary C8 drew is
recorded here so it lives where the rest of the addressing decisions do.

- **v10 status:** `L5XParser.extractValue()` reads only the top-level scalar
  `<DataValue Value="...">` for an atomic tag (C8). A structure/array's initial values
  — `<DataValueMember>` elements nested inside a `<Structure>` (e.g. a `TIMER` instance's
  `.PRE`), and per-element `<Element>` values inside an `<Array>` — are NOT read; every
  such member/element gets its type-default value regardless of what the export
  actually contains (a real export with `TIMER.PRE=5000` starts the emulator's copy at
  `0`).
- **This is an explicit maintainer decision, not an oversight:** per-member/per-element
  initial-value derivation is deferred **post-v10**. See `KNOWN_ISSUES.md` for the
  user-facing statement of this gap.

### 3.11 Predefined structured-type member sets — **DOC-CONFIRMED (per type)**

The emulator's `RockwellBuiltInTypes` tables are hand-approximated and wrong in several
places (C4). The **authoritative** sets follow. Members are addressed `Tag.<Member>`
(e.g. `MyTimer.ACC`, `MyCounter.DN`). Where a member is a bit of a hidden control word,
the *addressing form is still the named member* (`Tag.EN`), not `Tag.CTL.31` — the named
bit is what browses and binds.

> **L5X type-name mapping the parser MUST apply (corpus-verified):** the L5X `DataType`
> string for the enhanced PID is **`PID_ENHANCED`**, not `PIDE`; alarms are
> `ALARM_ANALOG`/`ALARM_DIGITAL` (also seen as instruction mnemonics `ALMA`/`ALMD`).
> The policy must alias `PID_ENHANCED → PIDE` member table.

#### TIMER — 5 members — **DOC-CONFIRMED** (verbatim from corpus L83E export + pycomm3)

`PRE` (DINT), `ACC` (DINT), `EN` (BOOL), `TT` (BOOL), `DN` (BOOL).

- **There is NO `.ER` member** and no `.OV`. The corpus `<Structure DataType="TIMER">`
  exposes exactly `ACC DN EN PRE TT`; pycomm3's live template returns
  `[CTL, PRE, ACC, EN, TT, DN]` (CTL is the hidden control word, not a browsable member).
  The emulator's current phantom `.ER` (`RockwellBuiltInTypes.java:58`) is **WRONG** —
  remove it. (Undocumented reserved bits exist in the control word but are unsupported
  and must not be emitted.)

#### COUNTER — 7 members — **DOC-CONFIRMED** (emulator already correct)

`PRE` (DINT), `ACC` (DINT), `CU` (BOOL), `CD` (BOOL), `DN` (BOOL), `OV` (BOOL),
`UN` (BOOL). No `.EN`, no `.ER`.

#### CONTROL — 10 members — **DOC-CONFIRMED** (emulator missing 3)

`LEN` (DINT), `POS` (DINT), `EN` (BOOL), `EU` (BOOL), `DN` (BOOL), `EM` (BOOL),
`ER` (BOOL), `UL` (BOOL), `IN` (BOOL), `FD` (BOOL).

- CONTROL **does** have `.ER` (unlike TIMER). The emulator is missing `.UL .IN .FD`
  (`RockwellBuiltInTypes.java:73-81`) — add them.

#### MESSAGE (MSG) — **DOC-CONFIRMED (status + core) / INFERRED (config layout)**

Status word `FLAGS` is a **16-bit INT** (not a DINT). Browsable named members:
- Status bits (BOOL): `EW`, `ST`, `DN`, `ER`, `TO`, `EN`, `EN_CC`.
- Data/error (INT): `ERR`, `EXERR`, `DN_LEN`, `REQ_LEN`.  *(`.ERR`/`.EXERR` are INT, not DINT.)*
- Config members (accessible, but exact byte layout is proprietary — **INFERRED** names
  from 1756-PM012 / field practice): `Class` (INT), `Instance` (DINT), `Attribute`
  (INT), `LocalIndex` (DINT), `RemoteIndex` (DINT), `Channel`/`Rack`/`Group`/`Slot`
  (SINT), `DestinationLink`/`DestinationNode`/`SourceLink` (INT), `UnconnectedTimeout`
  (DINT), `ConnectionRate` (DINT), `TimeoutMultiplier` (SINT), `ServiceCode` (INT).
- The emulator's current MESSAGE set treats `ERR/EXERR` as INT (ok) but invents
  `ConnectionPath` (STRING) which is not a standard browsable member, and omits `EN_CC`,
  `ST`. Correct the status members; the config members are best-effort.

#### PID (classic) — ~46-49 members — **DOC-CONFIRMED** (verbatim from Logix5000 ref manual)

**Not in the vendored corpus** (no `DataType="PID"` anywhere) — synthesise a fixture.
21 status bits in the control word + 28 REAL parameters:

- Status bits (BOOL): `EN CT CL PVT DOE SWM CA MO PE NDF NOBC NOZC INI SPOR OLL OLH EWD DVNA DVPA PVLA PVHA`.
- Parameters (all REAL): `SP KP KI KD BIAS MAXS MINS DB SO MAXO MINO UPD PV ERR OUT PVH PVL DVP DVN PVDB DVDB MAXI MINI TIE MAXCV MINCV MINTIE MAXTIE`.
- Note `PID.ERR` is a **REAL** (scaled error) — distinct from `MESSAGE.ERR` (INT). The
  emulator's current PID has 14 members with several non-existent names (`CVH/CVL/TIE/MINTIE/MAXTIE`
  mixed with invented ones) — replace wholesale with the set above.

#### PIDE (`PID_ENHANCED`) — ~130+ members — **DOC-CONFIRMED** (verbatim from corpus 1768 export)

The genuine corpus tag `RMPS_PIDE` (`DataType="PID_ENHANCED"`) exposes **138** members.
The emulator's ~30-member approximation is far short. **Recommended approach: derive the
PIDE (and PID/ALARM) member table from the real export member list rather than
hand-authoring** — a bundled resource generated from the corpus is more reliable than a
transcribed table. The confirmed leading members (from the export, verbatim):

`EnableIn`(BOOL) `PV`(REAL) `PVFault`(BOOL) `PVEUMax`(REAL) `PVEUMin`(REAL) `SPProg`(REAL)
`SPOper`(REAL) `SPCascade`(REAL) `SPHLimit`(REAL) `SPLLimit`(REAL) `UseRatio`(BOOL)
`RatioProg`(REAL) `RatioOper`(REAL) `RatioHLimit`(REAL) `RatioLLimit`(REAL) `CVFault`(BOOL)
`CVInitReq`(BOOL) `CVInitValue`(REAL) `CVProg`(REAL) `CVOper`(REAL) … `PGain`(REAL)
`IGain`(REAL) `DGain`(REAL) … `CVEU`(REAL) `CV`(REAL) `SP`(REAL) `E`(REAL) … `ProgOper`(BOOL)
`Auto`(BOOL) `Manual`(BOOL) `Override`(BOOL) `Hand`(BOOL) … `Status1`(DINT) `Status2`(DINT)
`InstructFault`(BOOL) `PVFaulted`(BOOL) `CVFaulted`(BOOL) … (138 total).

- **FLAG:** PIDE ≠ PID. Different type name, FBD-only, Program/Operator command-arbitration
  pattern that classic PID lacks. Do not conflate.

#### ALARM_ANALOG (ALMA) — ~90-100+ members — **DOC-CONFIRMED (most)**

Large. Per-level (HH/H/L/LL) each carries Limit/Enable/InAlarm/Acked/ProgAck/OperAck/
Severity/MinDuration, plus positive/negative rate-of-change. Core confirmed members:
`EnableIn In InFault HHLimit HLimit LLimit LLLimit HHEnabled HEnabled LEnabled LLEnabled
Deadband ROCPosLimit ROCNegLimit ROCPeriod` … outputs `EnableOut InAlarm AnyInAlarmUnack
HHInAlarm HInAlarm LInAlarm LLInAlarm ROC HHAcked … Severity(DINT) Status(DINT)
InstructFault`. **Recommended: derive from a real ALARM_ANALOG export** (corpus file #1
L83E has 8) rather than the emulator's 25-member approximation.

#### ALARM_DIGITAL (ALMD) — ~35-40 members — **DOC-CONFIRMED**

`EnableIn In InFault Condition AckRequired Latched ProgAck OperAck ProgReset OperReset
ProgSuppress OperSuppress ProgUnsuppress OperUnsuppress ProgDisable OperDisable ProgEnable
OperEnable AlarmCountReset UseProgTime ProgTime(LINT) Severity(DINT) MinDurationPRE(DINT)`
→ outputs `EnableOut InAlarm Acked InAlarmUnack Suppressed Disabled MinDurationACC(DINT)
AlarmCount(DINT) InAlarmTime(LINT) AckTime(LINT) RetToNormalTime(LINT)
AlarmCountResetTime(LINT) DeliveryER(BOOL) DeliveryDN(BOOL)` + `InFaulted InstructFault
Status(DINT)`.

#### AXIS_* / MOTION_GROUP / COORDINATE_SYSTEM / CAM — **POLICY (see §3.9-motion)**

These are not in the corpus (no motion exports found publicly). Policy defined below.

#### 3.11.1 Policy for very large / uncorpused predefined types

Motion structures are enormous and **not represented in the corpus**, so full fidelity
cannot be tested against a real file in v10. Policy:

- **Do not hand-author hundreds of members from memory** — that reproduces the exact
  "approximate member table" failure C4 is fixing.
- **Minimum member set + rationale:** emit the ~10-15 most-referenced members so the
  common motion faceplate bindings line up, and mark the type INCOMPLETE in a code
  comment + `KNOWN_ISSUES.md`. The real full list is documented in **MOTION-RM003**
  (CIP drives) / **1756-RM007** (servo) and is best captured later from a live-controller
  `pycomm3` template read or an IA bench export.
- **Minimum sets (INFERRED, common to the axis family):** `ActualPosition`,
  `CommandPosition`, `ActualVelocity`, `CommandVelocity`, `ActualAcceleration`,
  `CommandAcceleration`, `PositionError`, `AverageVelocity`, `MasterOffset` (all REAL) +
  status `AxisFault`(BOOL), `AxisState`(DINT), `MotionStatus`(DINT),
  `ServoActionStatus`(DINT), `DriveEnableStatus`(BOOL). AXIS_CIP_DRIVE adds
  `ActualTorque`, `MotorVelocityFeedback`.
- **Approx member counts (for expectation-setting): AXIS_CIP_DRIVE ~468 (DOC-CONFIRMED),
  AXIS_SERVO_DRIVE ~200-260, AXIS_VIRTUAL ~110-150, COORDINATE_SYSTEM ~80-120,
  MOTION_GROUP ~12, CAM element = 3.**
- **FLAG — the emulator's CAM is WRONG:** `CAM` is a small **cam-point** type whose
  element has exactly **3 members** — `X`(REAL), `Y`(REAL), `SegmentType`(SINT) — used as
  an array (`MyCam[n].X`). The emulator's current CAM (`Type/Size/Status/StartSlope/
  EndSlope`) is fabricated. `MOTION_GROUP` is ~12 members, not the 5 the emulator lists.
  Correct or clearly mark these INCOMPLETE.

### 3.12 External Access — **DOC-CONFIRMED**

- `ExternalAccess = None` → the tag/member is **omitted from the browse and from I/O
  entirely** (the real CIP driver never receives it). The emulator must **not create the
  node** (currently it does, visible + writable — #25/#31, inverse of the driver).
- `ExternalAccess = Read Only` → node created with `AccessLevel.READ_ONLY` (no write
  filter). Also apply to `Constant="true"` tags (currently writable — #24).
- **DOC-CONFIRMED**: pturmel manual §3.1 — "tags with no external access are simply not
  listed in a browse … both controller-scope and program-scope tags, but excluding tags
  with no external access"; IA support guidance confirms `None` tags are not returned to
  Ignition. Caveat (INFERRED): for *structure members* the None/Read-Only flag is only
  reliably known by attempting a read on real hardware; the emulator honours the
  `ExternalAccess` attribute the L5X provides, which is authoritative for the export.

**CRITICAL DISTINCTION — `OpcUaAccess` is NOT `ExternalAccess`:** Ignition-8.3 L5X
exports carry a separate `OpcUaAccess="None"` attribute (present 132× in corpus file #1).
That attribute governs the **controller's own native OPC-UA server** (on 5580/5380
controllers), **not** Ignition's CIP-based Logix driver, which is what the emulator
mimics. The emulator must therefore key visibility off **`ExternalAccess`**, and MUST
**ignore `OpcUaAccess`**. Consequence for tests: corpus tags like `SimpleString`
(`ExternalAccess="Read/Write" OpcUaAccess="None"`) are **visible** in the emulator
(and would be to the CIP driver), even though the controller's native OPC-UA server
would hide them. **INFERRED** (well-reasoned from the two-server architecture; the single
most important subtlety for anyone bench-testing against a real 5580 via its native
OPC-UA server vs via Ignition's Logix driver — confirm on a bench if possible).

### 3.13 I/O module tags — `Module:I.Data` / `Local:s:I.Data` — **INFERRED**

**Decision: the emulator SHOULD emit I/O module tags** (C5), because they are ordinary
controller-scope tags on a real Logix controller and field-I/O screens bind to them; an
emulator without them leaves those bindings dead until the real PLC arrives. The L5X
`<Modules>` section IS present in real exports (corpus: 36 modules in #1, 11 in #2, etc.),
so the data to synthesise from exists.

Naming (INFERRED — least-documented area):
- Networked/named modules: `<ModuleName>:I.Data`, `<ModuleName>:O.Data`,
  `<ModuleName>:C.<member>` (e.g. corpus `Dig_In_1:I.Data`).
- Local-chassis modules: also addressable as `Local:<slot>:I.Data` / `:O` / `:C`, where
  `<slot>` comes from the module's `<Port Address="s">` (corpus `Dig_In_1` is at
  `Address="2"` → `Local:2:I.Data`). Emit the `<ModuleName>:` form as canonical; the
  `Local:s:` form MAY be added as an equivalent where the module's parent is `Local`.
- Members come from the module-defined type (`AB:1756_DI:C:1` etc.) in the `<Modules>`
  `ConfigTag`/`InputTag`/`OutputTag` `<Structure>` — synthesise the member/byte layout
  from those decorated structures. `.Data` is typically a DINT/SINT array
  (`Local:2:I.Data[3]`, and bits `Local:2:I.Data.5` for a digital point).

**Why INFERRED and flagged risky:** IA docs are silent on the exact browse form; the
`<ModuleName>:` vs `Local:s:` choice and which module members are browsable (many carry
restricted access and won't read) can only be pinned on a live browse. pturmel warns I/O
module types "often have unreadable members" and "don't obey the normal Logix data
alignment rules." **Scope guidance for C5:** synthesise the readable `:I.Data` / `:O.Data`
/ `:C` arrays and their bit access; do not attempt exhaustive per-member fidelity of
diagnostic/config sub-members. Mark the whole section INFERRED pending a bench diff.

### 3.14 Atomic data-type mapping — **DOC-CONFIRMED (core) / INFERRED (newer)**

| Logix type | OPC-UA type | Confidence |
| ---------- | ----------- | ---------- |
| BOOL | Boolean | DOC-CONFIRMED |
| SINT | SByte | DOC-CONFIRMED |
| INT | Int16 | DOC-CONFIRMED |
| DINT | Int32 | DOC-CONFIRMED |
| LINT | Int64 | DOC-CONFIRMED |
| REAL | Float | DOC-CONFIRMED |
| LREAL | Double | DOC-CONFIRMED |
| STRING | String (+ `.LEN`/`.DATA`, §3.10) | DOC-CONFIRMED |
| USINT | Byte (UInt8) | INFERRED (C6) |
| UINT | UInt16 | INFERRED (C6) |
| UDINT | UInt32 | INFERRED (C6) |
| ULINT | UInt64 | INFERRED (C6) |
| WORD/DWORD/LWORD | UInt16/UInt32/UInt64 | INFERRED (C6) |
| DT / LDT / LTIME / TIME | dedicated presentation (Int64/DateTime) | INFERRED (C6) |

The emulator currently maps every unknown type to `String` (#29/#33). C6 must add the
newer unsigned/time types. Until a live driver diff confirms the exact OPC-UA
presentation of DT/LDT/LTIME, treat those as INFERRED (Int64 epoch is a safe default).

### 3.15 Browse tree (cosmetic, RECOMMENDED to mirror the driver)

```
[Device]/
  Controller:Global/        (browse folder; children NodeId = bare <tag>)
     <controller tags, UDT objects, array elements, predefined instances>
  Program:<Name>/           (browse folder per program; children NodeId = Program:<Name>.<tag>)
     <program tags>
  <I/O module tags, e.g. Dig_In_1:I …>   (controller scope; bare/module NodeIds)
```

The folder **labels** may be `Controller:Global` and `Program:<Name>` (matching the
driver-mirror browse), but a child's **NodeId identifier is the canonical form from §2.2**
— never the folder-qualified path. Drop the old `Programs/` parent folder and the
`Programs.<Name>.` NodeId prefix.

---

## 4. Migration note (for the v10 release notes)

**What breaks for pre-v10 users.** v10 changes the NodeId scheme to match the real Logix
driver, so any Ignition tag bound to an old emulator path must be re-pointed:

- **Program-scoped tags**: old `Programs.<Prog>.<tag>` → new `Program:<Prog>.<tag>`
  (and members/elements likewise). This affects every program-scoped binding.
- **Controller tags addressed via the long form** `Controller:Global.<tag>` no longer
  exist — use the bare `<tag>` (which pre-v10 also offered as a short alias, so bindings
  that already used the bare form are unaffected).
- **Arrays** that previously collapsed to a single un-indexed node now expand to
  elements (`Tag[0]…`), and **BOOL arrays** now use the DWORD-packed `Tag[w].b` form
  instead of the (driver-invalid) `Tag[i]` form — any binding to the old BOOL-element
  form must move to `Tag[word].bit`.
- **Predefined-struct members** with corrected sets: bindings to the emulator's phantom
  `TIMER.ER` break (it never existed on real hardware); previously-missing members
  (`CONTROL.UL/.IN/.FD`, the full PID/PIDE/ALARM sets) now appear.

**Upgrade guidance (release-note paragraph):**
> v10.0.0 changes the emulator's OPC NodeId scheme to be swap-compatible with Ignition's
> native Allen-Bradley Logix driver. Program-scoped tags now use the driver's
> `Program:<ProgramName>.<Tag>` form (previously `Programs.<ProgramName>.<Tag>`), arrays
> now expand to individual elements, and BOOL arrays use the driver's DWORD-packed
> `Tag[word].bit` form. **Bindings created against a pre-v10 emulator device must be
> re-pointed to the new paths** — the payoff is that a binding developed against the v10
> emulator now works unchanged when you swap in the real PLC. Re-import/redeploy the
> device's file after upgrading, then use the OPC browser to confirm the new paths before
> updating tag bindings.

---

## 5. Test-assertion checklist (`@Tag("fidelity")`)

Each row: real corpus file → input construct → **required emitted NodeId(s)** the test
asserts verbatim. Files are in `gateway/src/test/resources/corpus/` after Stage A
vendoring (exclude the unlicensed #9 1768 file — its constructs are re-covered by
synthetic fixtures below). Assertions are on the NodeId identifier (the `[Device]` prefix
is added by the driver framework and is not part of the assertion).

### 5.1 Controller scope — `CompactLogix5370-1769L33ER-fw33-NodeblueAI.L5X`
- `SystemClock` (DINT) → NodeId `SystemClock` (Int32), no `Controller:Global.` prefix.
- `EmergencyStop` (BOOL) → `EmergencyStop` (Boolean).
- `Motor_1` (UDT `Motor_UDT`) → object `Motor_1`; members `Motor_1.Running` (Boolean),
  `Motor_1.Speed` (Float), `Motor_1.RunTime` (Int32).
- Assert **absence**: no node with identifier `Controller:Global.SystemClock`.

### 5.2 Program scope — same file, `MainProgram` / `MotorProgram`
- `MainTimer` (TIMER) → `Program:MainProgram.MainTimer` object;
  member `Program:MainProgram.MainTimer.PRE` (Int32), `.ACC`, `.EN`, `.TT`, `.DN`.
- Assert **absence** of `Program:MainProgram.MainTimer.ER` (phantom removed).
- `CycleCounter` (COUNTER) → `Program:MainProgram.CycleCounter.ACC` (Int32),
  `.CU`, `.CD`, `.DN`, `.OV`, `.UN`.
- `VFD_SpeedRef` (REAL) in MotorProgram → `Program:MotorProgram.VFD_SpeedRef` (Float).
- Assert **absence** of any `Programs.MainProgram.MainTimer…` (old form gone).

### 5.3 UDT member depth + External Access — same file (AOI `Motor_Control`)
- AOI instance `Program:MainProgram.Motor1_AOI` exposes `EnableIn`, `EnableOut`, and
  visible Input/Output params (`Start`, `Stop`, `Running`) →
  `Program:MainProgram.Motor1_AOI.EnableIn`, `….Start`, `….Running`.
- Assert **absence** of `….RunLatch` and `….FaultTimer` (AOI LocalTags with
  `ExternalAccess="None"` → not emitted). *(This is the key External-Access test.)*

### 5.4 1-D and multi-dim arrays — `CompactLogix5370-1769L33ER-fw30-stellentus.L5X`
- `INFO_ABOUT` (INT[2]) → `INFO_ABOUT[0]`, `INFO_ABOUT[1]` (Int16).
- `multiArray` (INT `Dimensions="2 4"`) → `multiArray[0,0]`, `multiArray[0,3]`,
  `multiArray[1,0]`, `multiArray[1,3]` (Int16). Assert **absence** of `multiArray[0]`
  (must be comma-indexed, not first-dim-only).

### 5.5 3-D array + multi-dim (large) — `ControlLogix-1756L83E-fw36-L5Sharp.L5X`
- `MultiDimensionalArray` (DINT `Dimensions="3 5"`) → `MultiDimensionalArray[0,0]` …
  `MultiDimensionalArray[2,4]` (Int32); assert count = 15 elements.
- `TestArray` (DINT `Dimensions="1 1 2"`) → `TestArray[0,0,0]`, `TestArray[0,0,1]`.
- **Note**: these three tags carry `OpcUaAccess="None"` but `ExternalAccess="Read/Write"`
  → they MUST be emitted (the emulator keys off ExternalAccess, ignoring OpcUaAccess).
  This doubles as the OpcUaAccess-distinction test.

### 5.6 STRING — `ControlLogix-1756L83E-fw36-L5Sharp.L5X`
- `SimpleString` (STRING) → parent `SimpleString` (String value) +
  `SimpleString.LEN` (Int32) + `SimpleString.DATA[0]` (SByte). Assert `.DATA` expands to
  the STRING type's DATA dimension (82 for base STRING).

### 5.7 BOOL array (DWORD packing) — synthetic fixture `boolpack.l5x`
The licensed corpus BOOL arrays are all UDT-member `BOOL[32]`; add a small SYNTHETIC
top-level fixture (mirrors the unlicensed 1768 `PID_Enable : BOOL[32]`):
- `PackBits` (BOOL `Dimensions="32"`) → `PackBits[0].0`, `PackBits[0].31` (Boolean).
- `PackBits2` (BOOL `Dimensions="64"`) → `PackBits2[0].0`, `PackBits2[1].0`,
  `PackBits2[1].31`.
- Assert **absence** of `PackBits[0]`, `PackBits[2]`, `PackBits2[32]` (bare BOOL-element
  nodes must NOT exist — this is the "node does not exist" fidelity behaviour).

### 5.8 Bit of a DINT — `ControlLogix-1756L85E-fw38-RockwellAutomation.L5X`
- Alias targets `_bSts.0`, `_bSts.1`, `_bSts.9` confirm bit addressing; assert the
  emulator resolves/exposes `_bSts.9` (Boolean) as a bit of the `_bSts` DINT.
  *(Mark INFERRED if the emulator only pre-creates referenced bits — see §3.9.)*

### 5.9 Predefined member correctness — synthetic `predefined.l5x` + corpus
- TIMER (corpus §5.2): assert `.PRE .ACC .EN .TT .DN` present, `.ER` absent.
- CONTROL (synthetic): assert all 10 incl. `.UL .IN .FD`.
- PIDE (`PID_ENHANCED`): assert `.PV`, `.SP`, `.CVEU`, `.PGain`, `.InstructFault`
  present (subset of the 138); assert type-name alias `PID_ENHANCED → PIDE` worked.
- PID (classic, synthetic since uncorpused): assert `.SP .KP .KI .KD .OUT .SO .MAXO`
  present and `.ERR` is `Float` (Real), not Int.

### 5.10 I/O modules — `ControlLogix-1756L72-fw37-iotrustlab-controller.L5X`
- Module `Dig_In_1` (`1756-IB32/B`, `<Port Address="2">`) → `Dig_In_1:I.Data` (and/or
  `Local:2:I.Data`). Assert an `:I.Data` node exists for a parsed module.
  *(Mark the whole 5.10 group INFERRED — naming/browsability needs a bench diff.)*

---

## 6. Confidence summary

### 6.1 DOC-CONFIRMED findings that VALIDATE or SHARPEN the gap analysis
- **Program scope `Program:<Prog>.<tag>`** — confirmed verbatim (IA docs + forum
  examples). Gap analysis was right; this is C1, the highest-impact fix.
- **BOOL DWORD-packing `Tag[N/32].(N%32)`, bare `Tag[N]` must fail** — confirmed by
  official IA docs *with the exact mapping table*. Gap analysis was right and this is now
  DOC-CONFIRMED, not inferred.
- **TIMER has no `.ER`** — confirmed by BOTH a genuine corpus export
  (`<Structure DataType="TIMER">` = `ACC DN EN PRE TT`) and pycomm3. The emulator's
  phantom `.ER` is definitively wrong.
- **STRING exposes `.LEN` + `.DATA[i]` and a String value** — confirmed by a direct
  Kevin Herron (IA staff) quote.
- **External Access None = omitted from browse; Read Only = read-only** — confirmed
  (pturmel §3.1 + IA support). Gap analysis was right (#25/#31).

### 6.2 Where the gap analysis was INCOMPLETE / needed correction
- **CAM is not a large struct** — it is a 3-member cam-point type (`X`,`Y`,`SegmentType`)
  used as an array. The emulator's 5 fabricated members AND the gap analysis's implicit
  "AXIS/CAM/PHASE abbreviated" framing both understate that CAM's members are simply
  *wrong*, not merely truncated. `MOTION_GROUP` is ~12 members. Only AXIS_* and
  COORDINATE_SYSTEM are genuinely huge (AXIS_CIP_DRIVE ~468).
- **`OpcUaAccess` vs `ExternalAccess`** — the gap analysis (written before spotting the
  Ignition-8.3 `OpcUaAccess` attribute) keys hiding off `ExternalAccess` only. That is
  correct for the CIP Logix driver, but the corpus's pervasive `OpcUaAccess="None"` is a
  trap: it governs the controller's *native* OPC-UA server, not Ignition's driver, and
  MUST be ignored by the emulator. New, important, and flagged INFERRED.
- **PIDE L5X type name is `PID_ENHANCED`** (not `PIDE`) and has 138 members in a real
  export — the parser must map the name and the member table is far larger than either
  the emulator's ~30 or a casual reading of the gap analysis's "~46" (that ~46 figure is
  the *classic PID*, not PIDE).
- **Browse folder nuance**: the driver-mirror browse DOES show a `Controller:Global`
  display folder — so keeping that folder LABEL is faithful; only the NodeId must be
  bare. The gap analysis's "no Controller:Global wrapper" is true of the *item path*, not
  necessarily the browse folder.

### 6.3 INFERRED rules — the list a real IA bench test must confirm
These are the rules that could only be marked INFERRED; upgrade them to DOC-CONFIRMED via
a live browse of the CompactLogix test PLC (or a `pycomm3` template read):
1. **Bit-index range bounds** (DINT 0-31, etc.) — syntax confirmed, the width bound is
   inferred from the type size.
2. **Whether the driver pre-creates every `Tag.b` bit node** for a plain DINT, or
   resolves them on demand (affects §3.9 emitter policy and test 5.8).
3. **`BOOL[N]` with N not a multiple of 32** (the `ceil` / stray-upper-bits behaviour) —
   corpus only shows multiples of 32.
4. **Module I/O naming and browsability** (§3.11) — `<ModuleName>:I.Data` vs
   `Local:s:I.Data`, which members are readable. Least-documented area; whole section
   INFERRED.
5. **`OpcUaAccess` is ignored by the CIP driver** (§3.10) — strongly reasoned from the
   two-OPC-server architecture, but not bench-verified.
6. **MESSAGE config-member layout** (Class/Instance/… names and types) — status word +
   core error members are DOC-CONFIRMED; the config fields are inferred.
7. **Newer atomic/time types** (USINT/UINT/UDINT/ULINT, WORD/DWORD/LWORD, DT/LDT/LTIME)
   OPC-UA presentation (§3.12) — mapping inferred, especially the time types.
8. **Full member sets for AXIS_*, COORDINATE_SYSTEM** — minimum sets defined; full
   fidelity needs a motion export or live read (no public corpus).
9. **PID (classic) member set** — DOC-CONFIRMED from the reference manual, but UNTESTED
   against a real file (no `DataType="PID"` in the corpus → synthetic fixture only).

### 6.4 Primary sources
- IA — *Connecting to Logix* (BOOL-array table, verbatim):
  https://www.docs.inductiveautomation.com/docs/8.1/ignition-modules/opc-ua/opc-ua-drivers/allen-bradley-ethernet/connecting-to-logix
- Kevin Herron (IA) on STRING `.LEN`/`.DATA`:
  https://forum.inductiveautomation.com/t/allen-bradley-logix-device-driver-and-string-tags/50614
- BOOL-array `Tag[0].2` vs failing `Tag[2]` (pturmel):
  https://forum.inductiveautomation.com/t/logix-driver-vs-ethip-driver-array-of-bools/81964
- Kevin Herron + pturmel on AOI / external access:
  https://forum.inductiveautomation.com/t/cannot-read-some-tags-inside-rockwell-aoi/38393
- Multi-dim `LS[0,0]` / bit:
  https://forum.inductiveautomation.com/t/workaround-for-a-multi-demensional-array/100405
- pturmel EtherNet/IP module manual (tagpath grammar, external-access browse filtering,
  3-D array parse, module-I/O caveats — third-party but documented drop-in-compatible with
  the native v21+ driver): https://www.automation-pros.com/enip1/UserManual.pdf
- Rockwell Logix5000 General Instructions / Reference (TIMER/COUNTER/CONTROL/PID/ALARM
  member tables, via ManualsLib mirrors of 1756-RM003/RM006 content); PIDE §
  1756-RM006; Motion MOTION-RM003 / 1756-RM007.
- Genuine Studio 5000 exports (wire-level ground truth): the vendored corpus —
  TIMER member set, PIDE 138-member set, multi-dim index form, BOOL-array-multiple-of-32,
  I/O module names/slots, and the `OpcUaAccess` attribute were all verified directly
  against corpus files.
```
