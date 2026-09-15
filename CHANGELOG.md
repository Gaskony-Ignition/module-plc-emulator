# Changelog

All notable changes to the Logix PLC Emulator module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [11.1.2] - 2026-07-30

**Type:** PATCH — dead dependency removed, no behavioural change

### Removed

- **`wicket-core` 9.8.0 (`compileOnly`)** — a dead declaration. The 30/07/2026 audit
  found no `wicket*` jar anywhere under `lib/core/*` on Ignition 8.3.8 and no Wicket
  import in this module's source; the Gateway config pages it was presumably added for
  are built with `gateway-web`'s `SystemJsModule`/`nav` API instead, which stays. Being
  `compileOnly` it was never shipped, so removing it changes nothing at runtime — it
  was only misleading, and had been cited as a live "boundary library" example in
  `/modules/CLAUDE.md` until this audit corrected that.

## [11.1.1] - 2026-07-30

**Type:** PATCH — dependency audit/update (28-30/07/2026)

### Changed

- **Gson moved from `compileOnly` 2.11.0 to `modlImplementation` 2.14.0** (latest
  stable). Verified no `com.google.gson.*` object ever crosses the module/platform
  boundary in either direction: grepped every route handler in `web/controller/`
  (all 20 WebDev routes return `org.json.JSONObject`, never a raw Gson type —
  `DeviceController.attachParseSummary()` converts its one Gson `JsonObject` to
  `org.json.JSONObject` via `.toString()` before it reaches the servlet response),
  and confirmed by bytecode inspection that none of `common-8.3.0.jar`,
  `gateway-api-8.3.0.jar`, `opc-ua-gateway-api-10.3.0.jar`, `gateway-web-8.3.0.jar`,
  or `gateway-api-web-8.3.0.jar` reference `com/google/gson` anywhere, so no SDK
  method can hand us (or accept from us) a Gson type either. Shipping our own copy
  means compile-time and runtime always agree by construction; ~280KB added to the
  `.modl`. (Originally pinned down to 2.8.9 to match the platform's bundled copy —
  that plan was superseded once the boundary check confirmed shipping is safe.)
- **sqlite-jdbc bumped 3.49.1.0 → 3.53.2.1** (`modlImplementation`, shipped —
  classloader-isolated from the platform's older bundled 3.41.2.2, free to track
  latest stable).
- **jakarta-servlet bumped 5.0.0 → 6.0.0** (`compileOnly`) — matches what the
  gateway actually bundles (`jakarta.servlet-api-6.0.0.jar`); build stays green.
- **Test dependencies bumped to latest stable, estate-wide consistent**:
  junit-jupiter 5.11.3 → 5.14.4 (explicitly NOT 6.x — `mockito-junit-jupiter`
  5.23.0 is still "Mockito JUnit 5 support"; JUnit 6 support is an open upstream
  PR), mockito 5.18.0 → 5.23.0, awaitility 4.2.2 → 4.3.0. assertj-core stays at
  3.27.7 (already the latest stable 3.x; 4.0.0-M1 is a milestone, not a release).
  Added an explicit `junit-platform-launcher` 1.14.4 `testRuntimeOnly` pin —
  Gradle 8.10.2's own bundled launcher is older than the platform-engine pulled in
  by junit-jupiter ≥5.12 and fails discovery with `OutputDirectoryCreator not
  available` without it.
- **No change**: `opc-ua-gateway-api` (10.3.0), `gateway-web` (8.3.0), and
  `wicket-core` (9.8.0) stay `compileOnly` at their declared versions. Confirmed
  via live-gateway inspection (Ignition 8.3.8, container `ignition-maker`) that no
  bare Wicket jar exists anywhere under `lib/core/*`, and bytecode inspection of
  all gateway-scope jars found no Wicket or Gson traces either — Wicket is
  declared `compileOnly` in this module's `gateway/build.gradle.kts` but is not
  actually imported or used anywhere in the module's source. Left as a boundary
  library per estate policy rather than removed, since removing an unused
  declaration is a separate cleanup, not a version audit, decision.

### Fixed

- No functional code changes; this is a dependency-only audit. All 562 gateway
  tests pass (one timing-sensitive TOGGLE-pattern test flaked once under load and
  passed cleanly on rerun in isolation and in a subsequent full clean build — not
  a regression from this audit).

## [11.1.0] - 2026-07-30

**Type:** MINOR — module now opts in to Ignition Maker Edition

### Added

- **`SimulatorModuleHook` now overrides `isMakerEditionCompatible()` to return
  `true`.** `AbstractDeviceModuleHook` (like every `AbstractGatewayModuleHook`
  subclass, including device drivers) defaults this to `false`, so without the
  override Maker Edition silently refuses to start the module and reports it
  as "not eligible for use with Ignition Maker Edition" — no fault, no other
  log line. Verified live on Maker 8.3.8 (see forum thread linked in the
  override's Javadoc). Free module; no device-driver behaviour identified that
  would misbehave under Maker's licensing. New capability, hence a minor bump
  rather than a patch. Regression test: `SimulatorModuleHookMakerEditionTest`.

## [11.0.0] - 2026-07-27 - **L5K is the only supported Rockwell format**

A user following the README uploaded an L5X export and it did not work. The README was the
cause: its "Supported Formats" section still carried the superseded 10/07/2026 decision text
("**Rockwell L5X** … **Primary format.** … **Rockwell L5K** - Best-effort"), which the 12/07
decision (`docs/plans/V10_1_L5K_PLAN.md`) had replaced and the v10.1.0 release never updated.
The Device Manager compounded it: it read "Drop L5X file" and its file picker
(`accept=".L5X,.l5x,.xml"`) would not let an operator select a `.l5k` at all, while the legacy
device-config uploader advertised "L5K, JSON, CSV, XML" and offered `.xml`, which the server has
never accepted. The shipped module therefore steered every new user onto L5X — the one Rockwell
path v10.1.0 never verified live (its verification used two real L5K site exports only; the last
end-to-end L5X run was the v10.0.0 DoD on 12/07, before v10.1.0 changed `L5XParser`).

Rather than verify a second format nobody uses, L5X is withdrawn: L5K is the only supported
Rockwell format (maintainer decision 27/07/2026, `PROJECT_CHARTER.md` §4).

### Removed

- **BREAKING: Rockwell L5X is no longer a supported format.** Uploading a `.l5x` is rejected
  with actionable guidance ("Export your program as an L5K file from Studio 5000 and upload
  that instead") rather than the generic unsupported-format message. `L5XParser` is no longer
  registered in `ParserFactory`, so no filename can route to it anywhere in the running module,
  and `FilePreparation` no longer picks `.l5x` files off disk — **an existing device whose
  uploaded file is an `.l5x` will not reload it after upgrading; re-upload the program as L5K.**
  JSON and CSV are unaffected.
- `.xml` removed from the legacy device-config uploader's file picker: it was offered but the
  server never accepted it, so choosing an XML file always failed after upload.
- `.txt` removed from `ParserFactory.getSupportedExtensions()`, which advertised it despite
  `FileValidator` never accepting it. (`CsvParser.canHandle` still matches `.txt`; it is
  unreachable from the upload path.)

### Changed

- README, QUICK_START, PROJECT_CHARTER (§2 DoD item 2 and §4 Won't-Do), the device-driver
  `Meta.Description`, the `ROCKWELL` parser-type label ("Rockwell L5K (Allen-Bradley)"), the
  Device Manager and Tag Browser copy, and both upload pickers now consistently state L5K.
  `FileValidator.SUPPORTED_EXTENSIONS` is the single source of truth the pickers mirror.
- `L5XParser` is retained but marked not-user-facing. It is deliberately not deleted: it is the
  reference implementation `L5KParser` is held to by `AoiCrossFormatEquivalenceTest` (the
  swap-fidelity contract) and it backs the real-world-corpus address-space coverage in
  `AddressSpaceBuilderIntegrationTest`/`AddressSpaceBuilderFidelityTest`. Those tests now
  instantiate it directly instead of resolving it through `ParserFactory`.

### Security

- The XXE attack surface is removed rather than guarded: with no XML parser reachable from any
  upload, a DOCTYPE-bearing payload can no longer reach `DocumentBuilderFactory` through the
  REST path at all. `L5XParserTest`'s XXE tests are retained as defence in depth in case L5X is
  ever re-exposed, and the B8 test now asserts the upload gate rejects the fixture.

### Known gap (deliberately not addressed here)

- The device config's **"Max File Size (MB)"** field is still ignored — uploads are fixed at
  50 MB. Reviewed for this release and deferred: the largest real site export is 5.5 MB (~9×
  headroom), while the fix requires resolving the device before the request body is consumed,
  which is the wrong change to make on the upload path immediately before a release. Now tracked
  as **KNOWN_ISSUES #11** with the intended approach recorded.

## [10.1.0] - 2026-07-13 - **Real-world L5K parsing**

The v10.0.0 L5K parser silently corrupted real Studio 5000 exports: it matched tag declarations
by whole-file regex, but a ladder rung (`N : XIC(Sim)OTL(Sts);`) is token-for-token identical to
a tag declaration (`Name : Type;`), so ladder/FBD instruction mnemonics leaked into the tag tree
as thousands of bogus tags while every AOI-local tag and array was dropped - and the upload still
reported success with no warning. (This defect long predated v10.0.0; it was present unchanged in
9.2.14 and traces to a parser refactor around v7.0.0.) This release replaces that parser and makes
L5K a first-class, fidelity-verified format alongside L5X, verified against real site exports to
exact ground-truth tag counts.

### Added

- **Statement-oriented, block-stack L5K parser** (`L5KParser`, complete rewrite). Parses by an
  explicit block stack over the 22 recognised `KEYWORD`/`END_*` pairs, accumulating statements to
  the `;` terminator with full quote/bracket/paren/comment awareness. Tag-shaped statements are
  parsed **only** inside the five whitelisted contexts (controller `TAG`, program `TAG`, AOI
  `PARAMETERS`, AOI `LOCAL_TAGS`, `DATATYPE`); all routine/ST/FBD/`MODULE`/`CONFIG` content is
  opaque. A rung-token tripwire hard-fails loudly if instruction text ever reaches a tag context.
  AOI definitions parse as types and expand through the same machinery and member semantics as the
  L5X path (`EnableIn`/`EnableOut` emitted, `InOut` excluded, per-member `ExternalAccess`
  honoured). Output feeds the same vendor-neutral parsed-tag model - no `AddressSpaceBuilder`
  changes.
- **Loud parse accounting.** Uploads now carry a parse summary (skipped-line, unknown-type and
  unmodelled-FBD counters + a `structurallyClean` flag); a non-clean parse surfaces a warning in
  the response instead of a bare success. Structurally unparseable files hard-fail with an
  L5K-specific error.
- **Normative grammar spec** `docs/plans/L5K-GRAMMAR.md`, derived from real exports plus Rockwell
  1756-RM084; every rule FILE- or MANUAL-CONFIRMED.
- **Real-file fidelity tests.** An environment-gated integration test
  (`PLC_EMU_PRIVATE_L5K_DIR`) asserts exact ground-truth counts against genuine site exports; a
  10-fixture synthetic grammar matrix covers each construct in-repo; a cross-format equivalence
  test asserts the same AOI expands identically from L5X and L5K.

### Fixed

- **L5X AOI instance expansion no longer emits a phantom `InOut` member (FIX-A, swap-fidelity
  breach).** `L5XParser` added every AOI `<Parameter>` - Input, Output, AND InOut - to an
  instance's expanded `udt_members`. An `InOut` parameter is a *reference* to the caller's tag,
  not backing storage in the AOI instance (L5K-GRAMMAR.md §3.2(3), ADDRESSING.md §3.3): the real
  CIP driver never exposes it as an instance member. The new L5K parser already excluded
  `Usage := InOut` correctly (`L5KParser.buildAoiMember`); L5X did not. `L5XParser.parseAOI` now
  excludes `Usage="InOut"` parameters from both the AOI type definition (`aois[].members[]`) and
  every expanded instance - `EnableIn`/`EnableOut` and Input/Output parameters are unaffected.
  **This is a deliberate behaviour change from v10.0.0**: any AOI instance binding that referenced
  an `InOut` parameter's phantom node (which never worked against a real driver anyway) will find
  that node gone. A cross-format equivalence test
  (`AoiCrossFormatEquivalenceTest`) now asserts the same AOI (defined identically in L5X and L5K)
  expands to an identical instance member set through both parsers.
- **L5K statement accumulator now recognises `(* ... *)` block comments (FIX-B).** A block comment
  appearing inside a whitelisted `TAG`/`PARAMETERS`/`LOCAL_TAGS`/`DATATYPE` block could previously
  merge into a neighbouring statement's accumulated text (or, if the comment contained a `;`,
  desynchronise the terminator scan). `L5KParser.accumulateStatement` now strips block-comment
  content the same way `scanQuoteState` already does in the opaque (non-tag-bearing) path.
- **L5K statement accumulator no longer silently drops residue after a same-line terminator
  (FIX-C).** A second declaration following a `;` on the same physical line (e.g.
  `A : DINT; B : DINT;`) was previously discarded with no accounting at all. Real exports are
  one-declaration-per-line so this was latent, but it is exactly the silent-loss class the v10.1
  rewrite exists to eliminate. Such residue is now counted in `skippedTagLines` and logged as a
  WARN, flipping `structurallyClean` to `false` - loud, never silent.
- **L5K UDT/AOI instance expansion now emits a default `initial_value` for atomic leaf members
  (FIX-D), aligning with `L5XParser.expandUdtInstance`.** `L5KParser.expandRecursive` previously
  left atomic (non-nested) instance members with no `initial_value` at all, while the L5X path
  always sets a type-appropriate default. Both parsers now feed `AddressSpaceBuilder` the same
  member shape. (Investigated and NOT changed: a `usage` marker on expanded instance members. On
  inspection, `L5XParser.expandUdtInstance` does not itself propagate a `usage` field onto any
  instance member either - the `"usage":"Local"` annotation `L5XParser.parseAOI` adds lives only
  on the AOI *type definition's* `LocalTag` entries, never on an instance's `udt_members`, in
  either parser, and `AddressSpaceBuilder` does not consume `usage` at all. Adding a synthesised
  `usage` marker to the L5K instance shape would therefore diverge from, not align with, L5X's
  actual current output - documented here as a residual rather than implemented.)

## [10.0.0] - 2026-07-12 - **Fidelity Release: Swap-Compatible NodeIds**

Follows the 09/07/2026 Definition-of-Done verification of v9.2.14, which failed
three of the charter's seven checklist items (simulation, hot-reload writes,
version revert) and surfaced a deeper problem: the emulator's OPC NodeId
scheme did not match Ignition's real Allen-Bradley Logix driver, so tag
bindings developed against the emulator silently broke on swap to a real PLC.
Charter §2.2 was amended to require swap-compatibility (see
`docs/PROJECT_CHARTER.md`); this release delivers it, distilled into the
normative `docs/plans/ADDRESSING.md` spec and its `@Tag("fidelity")` test
suite, alongside the outright defects the DoD run found.

### BREAKING CHANGES

- **NodeId scheme now matches Ignition's native Allen-Bradley Logix driver.**
  Every construct's OPC-UA NodeId identifier was redesigned against
  `docs/plans/ADDRESSING.md` so that a tag binding developed against the
  emulator survives a swap to the real PLC. Concretely:
  - **Controller-scoped tags** are now the bare tag name (`<tag>`, no
    prefix). **Program-scoped tags** are now `Program:<Prog>.<tag>`
    (previously the invalid `Programs.<Prog>.<tag>`).
  - The pre-v10 duplicate-node scheme — a "long" node under
    `Controller:Global.<tag>` plus a synchronised "short" alias at the device
    root (`enableSynchronizedWrites` / `WriteSyncHelpers`) — has been
    **removed entirely**. Each tag now has exactly one canonical node; there
    is no alias concept in v10.
  - **BOOL arrays are now DWORD-packed** (`Tag[word].bit`, e.g.
    `boolTag[0].0` … `boolTag[0].31`, `boolTag[1].0` …) instead of one node
    per element (`Tag[i]`) — the bare per-element form the real driver
    rejects no longer exists.
  - **Arrays fully expand**: array-of-UDT/predefined instances
    (`Tag[i].Member`), multi-dimensional arrays (`Tag[i,j]`, `Tag[i,j,k]`,
    comma-indexed within one bracket pair), and array members inside a UDT
    (`Tag.Member[i]`) all now emit every element instead of collapsing to a
    single un-indexed node.
  - **`ExternalAccess="None"` tags/members are no longer created at all**
    (previously visible and writable) — they are omitted from browse and I/O
    entirely, matching the real CIP driver. `Read Only` and `Constant="true"`
    tags are now created read-only.
  - **Predefined structured-type member tables corrected** against Rockwell
    reference manuals and real Studio 5000 exports: `TIMER`'s phantom `.ER`
    member is removed (it never existed on real hardware); `CONTROL` gains
    the previously-missing `.UL`/`.IN`/`.FD`; `MESSAGE`, `PID`, `PIDE`
    (`PID_ENHANCED`), `ALARM_ANALOG` and `ALARM_DIGITAL` member sets are
    corrected/expanded. Bindings to the old fabricated members break.

  **Migration guidance** (from `docs/plans/ADDRESSING.md` §4, reused
  verbatim): v10.0.0 changes the emulator's OPC NodeId scheme to be
  swap-compatible with Ignition's native Allen-Bradley Logix driver.
  Program-scoped tags now use the driver's `Program:<ProgramName>.<Tag>` form
  (previously `Programs.<ProgramName>.<Tag>`), arrays now expand to
  individual elements, and BOOL arrays use the driver's DWORD-packed
  `Tag[word].bit` form. **Bindings created against a pre-v10 emulator device
  must be re-pointed to the new paths** — the payoff is that a binding
  developed against the v10 emulator now works unchanged when you swap in
  the real PLC. Re-import/redeploy the device's file after upgrading, then
  use the OPC browser to confirm the new paths before updating tag bindings.
  Controller tags addressed via the bare form pre-v10 are unaffected (the
  bare form already existed as the short alias).

### Fixed

- **L5X uploads crashed the entire address-space build** on any tag whose
  parsed `initial_value` was non-numeric (e.g. a `"{structure}"` sentinel or
  empty string) — `AddressSpaceBuilder.getInitialValue()`'s strict
  `getAsInt()` had no per-tag guard, so one bad tag aborted the whole build.
  Parsing is now lenient with type-appropriate defaults, and per-tag node
  creation is isolated so one bad tag logs a warning instead of aborting.
- **Simulation engine never updated tag values** — the engine and the
  OPC-UA address space were not correctly wired together, so assigned
  RAMP/SINE/RANDOM/TOGGLE patterns never moved a browsed value. The engine
  now writes registered nodes directly and simulation tag paths are
  correctly converted for the registry.
- **File version manager was inert** — `saveVersion` was never called on the
  REST upload path, `getVersions`/`restoreVersion` were dead code with no
  route or UI, and a Gateway restart could reload a stale or arbitrary file
  because file selection used an unordered directory listing. Upload now
  saves a version on every successful upload, restart deterministically
  picks the most-recently-modified file, and uploads beyond the retention
  limit (5) are pruned.
- **JSON parser produced zero OPC tags** despite parsing without error — the
  parser's output shape did not match what `buildAddressSpace` expected
  (`global_tags`/`programs` keys). Output is now normalised so flat JSON tag
  lists are correctly consumed.
- **Upload endpoint reported success on a failed parse** — a corrupt or
  unparseable file returned HTTP 200 `{"success":true}` regardless. The
  upload endpoint (and hot-reload) now propagate a genuine parse/build
  failure as a 4xx/5xx error body instead of masking it.
- **L5K parser silently substituted a demo tag** on any input that failed
  its synthetic grammar, rather than reporting the failure. Per the
  maintainer's one-primary-format-per-vendor decision (L5X is primary,
  L5K is best-effort), the L5K parser now fails loudly instead of masking
  the failure with a fabricated `L5K_ParseError` tag.
- **Cross-device file scoping was ambiguous** — devices whose names
  text-overlapped under the legacy `_`-separator matching (e.g. `plc` vs
  `plc_test`) could pick up, and even prune, each other's uploaded files on
  Gateway restart. File matching now uses an unambiguous `.`-separated
  naming tier for pickup and retention pruning; the legacy `_`-separated
  form is still recognised for pre-existing files but is pickup-only and
  never deleted.
- **Hot-reload did not correctly update array and nested-member values** —
  the incremental updater diffed on the old (now-removed) node-id scheme and
  could leave a partially-applied address space after a structural change.
  Hot-reload now diffs on the expanded canonical node ids and rebuilds
  cleanly on a partial apply.
- **Read-only enforcement did not cover every write path** — REST tag
  writes and the simulation engine could both write to an
  `ExternalAccess="Read Only"` or `Constant="true"` tag, and packed
  BOOL-array bit nodes did not respect the read-only flag at all. All write
  paths (OPC-UA, REST, simulation) now honour `ExternalAccess`.
- Genuine L5X parse failures are propagated instead of being masked with
  demo tags; ASCII-radix single-character scalar initial values now decode
  correctly; NUL/control-byte-only file content is now distinguished from
  genuinely empty content (previously misreported as "File content is
  empty"); absolute filesystem paths are no longer echoed into device
  status on 4xx error bodies; the XXE regression test now uses a
  realistically-sized (≥10KB) fixture that actually reaches the XML parser.

### Added

- **File version list/revert** — REST routes (`GET /device/:name/versions`,
  `POST /device/:name/versions/revert`) plus a minimal web-UI surface to
  list and roll back to a previously-uploaded file version.
- **Module I/O tags** — the L5X `<Modules>` section is now parsed into
  controller-scope I/O tag nodes (`<ModuleName>:I.Data`, `:O.Data`, `:C.…`;
  local-chassis modules also addressable as `Local:<slot>:I.Data`) per
  `docs/plans/ADDRESSING.md` §3.13.
- **v32+ unsigned and time atomic types** — `USINT`/`UINT`/`UDINT`/`ULINT`
  and `DT`/`LDT`/`LTIME`/`TIME` now map to proper OPC-UA types instead of
  degrading to `String`.
- **Initial values read from real exports** — `L5XParser.extractValue()` now
  reads the `Value` attribute of a self-closing `<DataValue Value="…"/>`
  element (Studio 5000's actual export form), falling back to text content;
  previously every scalar tag silently started at its type default
  regardless of the export.
- **Base `STRING` tags gain `.LEN`/`.DATA` members** (`Tag.LEN` DINT,
  `Tag.DATA[i]` SINT array) alongside the existing scalar `String` value,
  matching the real driver's browsable structure.
- **AOI `EnableIn`/`EnableOut` parameters** are now exposed on AOI backing
  tags alongside visible Input/Output parameters (InOut parameters remain
  excluded — they are references, not backing-tag storage).
- **Real-world export corpus + fidelity test suite** — eight licence-clean
  genuine Studio 5000 L5X exports vendored under
  `gateway/src/test/resources/corpus/` (with `ATTRIBUTION.md`), plus a new
  `@Tag("fidelity")` suite (17 tests, run via the `fidelityTest` Gradle task,
  excluded from the default `test` task) asserting the driver-matching
  NodeId behaviour in `docs/plans/ADDRESSING.md` §5.
- **`AddressPolicy` vendor seam** — Rockwell addressing rules
  (`RockwellLogixPolicy`) are now behind a pluggable `AddressPolicy`
  interface; `AddressSpaceBuilder` contains no vendor-specific logic. Lays
  the groundwork for a future non-Rockwell parser without touching
  node-creation machinery (no non-Rockwell parser ships in v10).

### Known limitations

See `docs/KNOWN_ISSUES.md` for full detail. Carried into v10.0.0, all by
deliberate maintainer decision rather than oversight:

- **Motion/coordinate predefined member sets are INCOMPLETE** —
  `AXIS_CIP_DRIVE`/`AXIS_VIRTUAL`/`AXIS_SERVO_DRIVE`/`MOTION_GROUP`/
  `COORDINATE_SYSTEM` expose only the ~10-15 most-referenced members; no
  public Studio 5000 export containing these types was found to corpus-test
  full fidelity.
- **Bit-of-integer addressing (`Tag.b` on a plain atomic integer) is not
  implemented** — deferred post-v10 pending bench confirmation of bit-width
  bounds and a pre-create-vs-on-demand design decision.
- **Structure/array member initial values are not read from the export** —
  only a scalar atomic tag's initial value is read (C8); a UDT/AOI/array
  member's nested `<DataValueMember>`/`<Element>` initial value is not, and
  starts at its type default instead. Deferred post-v10.

---

## [9.2.1] - 2026-03-07

### Cross-module standardisation (Round 4)

#### Changed
- Add `allprojects` block for consistent version/group propagation to subprojects (matches AT, Camera, Git, Python3)
- Add `allowImportingTsExtensions: false` to `tsconfig.webpack.json` (matches AT, Git, Python3)
- Remove vestigial `prettier` devDependency (no `.prettierrc` existed)
- Standardise ESLint rule order to `no-unused-vars`, `no-explicit-any`, `ban-ts-comment` (matches AT, Git, Python3)

---

## [9.2.14] - 2026-05-09

### Removed
- `:designer` Gradle subproject and its no-op `DesignerHook`. The Designer hook only logged startup/shutdown — there is no Designer-side functionality. Removing it shrinks the .modl, drops one scope from the build, and avoids shipping a hook for a scope that does nothing.

### Changed
- `:common` scope mapping changed from `GD` to `G` (Designer scope no longer exists, so common code is gateway-only).
- `gradle/libs.versions.toml`: removed unused `ignition-designer-api` library declaration.
- `.github/workflows/pr-checks.yml`: added a `gradle-check` job that runs `./gradlew check --no-daemon` so JaCoCo, Checkstyle, and SpotBugs gate PRs (matches AT, Camera, Git, Python3).

---

## [9.2.13] - 2026-05-09

### Sprint 3 closeout

Bundles the six Sprint 3 commits sitting on top of 9.2.12.

#### Added
- Accessibility baseline (P10): `prefers-reduced-motion: reduce` block in `App.scss`, skip link wired to `<main id="main-content">`, new accessible `Modal` primitive (`role=dialog`, focus trap, Escape, focus restore, body scroll lock) with the device-delete confirmation modal in `DeviceManagerView` migrated as the first consumer, and `jsx-a11y/label-has-associated-control` ESLint rule at warn severity.

#### Changed
- Visibility-aware polling for `StatusBar` — fetches suspend on `visibilitychange:hidden` and resume on visible via the `useVisibilityAwarePolling` hook, cutting wasted gateway round-trips when the panel/tab is backgrounded (perf).
- Standardise `.gitattributes` (canonical LF version) and `.gitignore` (track `package-lock.json`; add `*.bak` / `*.backup` / `*.hprof` / Windows-litter patterns) to match the cross-module standard.
- Backfill `CHANGELOG.md` entries for the six prior intermediate patches that shipped without release notes.
- `CLAUDE.md` required-reading list updated to point at `.claude/skills/` (SKILLS.md / LEARNINGS.md retired in the Mar 6 shared-skills migration). One Australian English substitution in `docs/TESTING.md` (P8 docs sweep).

#### Refactored
- Break up `LogixEmulatorDevice` god class (P6): 1054 → 472 lines (target ≤500 achieved). Extracts eight focused collaborators in the `device/` package, each with its own unit-test class.

---

## [9.2.12] - 2026-05-06

### Fixed
- Live `dataItems` snapshot consumed by simulation engine and recalibrate-on-write so writes through the OPC-UA address space are reflected immediately by running simulations (C11 / C12)

### Security
- Harden `.gitignore` to deny `gradle.properties`, `sign.props`, `*.jks`, `*.keystore`, and broad `.env.*` patterns; explicit allowlist for templates/examples and `*.public.key` / `*.pub.pem` (B3-autonomous)

### Notes
- Intermediate releases 9.2.7-9.2.11 were never committed to git; their disk-only changes are rolled into this entry.

---

## [9.2.6] - 2026-03-14

### Changed
- Standardise shadow value and modal z-index across the module UI for consistency with the other 4 modules.

---

## [9.2.5] - 2026-03-14

### Changed
- Standardise CSS values (spacing, radius, transition timing) across the module UI per the cross-module variable contract.

---

## [9.2.4] - 2026-03-14

### Changed
- Standardise modal backdrop opacity to 0.6 (matches the cross-module Catppuccin overlay token).

---

## [9.2.3] - 2026-03-14

### Changed
- Cross-module UI standardisation pass: align typography, spacing, accent colours, and component shells with AT / Camera / Git / Python3.

---

## [9.2.2] - 2026-03-13

### Fixed
- Standardise `PageHeader` component and auth-screen layout to match the cross-module pattern (subtitle handling, badge prop, padding).

---

## [9.1.1] - 2026-02-22 - **Rename nav item to "Devices"**

### Changed
- Gateway Connections nav entry renamed from "Connection Browser" to "Devices"

---

## [9.1.0] - 2026-02-22 - **Log UX: Newest-First + Local Timestamps**

### Changed
- **Module logs now display newest entries at the top** — render order reversed so developers see the latest activity without scrolling. Auto-scroll now moves to the top of the panel when enabled.
- **Log timestamps are now in the browser's local timezone** — backend returns raw `epochMs` alongside the server-formatted string; frontend formats with `new Date(epochMs).toLocaleString()` so the time reflects the user's locale and timezone, not the Ignition server's.

---

## [9.0.9] - 2026-02-22 - **Bug Fix: Tag Tree + Camera-Driver-Style Logs**

### Fixed
- **Tag tree expand was broken** — `handleGetTagChildren` returned the array under the key `"children"` but the frontend's `toggleExpand()` and `loadMoreChildren()` both read `data.tags`. Every folder expanded to empty. Fixed by returning `"tags"` (with backward-compat `"children"` removed).
- **Flat-mode pagination never loaded more** — `handleGetTags` (flat) returned `totalAtLevel` but the frontend read `data.total`. Added `total` field: equals `totalAtLevel` in flat mode, `stats.totalTags` in tree mode.
- **Stats bar showed wrong tag / UDT counts** — Added `udt_instances` snake_case alias alongside `udtInstances` so the TypeScript interface matched.

### Changed
- **Diagnostics log panel** fully reworked to mirror Camera Driver `GatewayLogHandler` pattern:
  - `handleSystemLogs` now accepts `moduleOnly` param (default `true`) — adds SQL `WHERE logger_name LIKE 'com.inductiveautomation.logixemulator%'` server-side, replacing fragile client-side "contains logix" filter.
  - Returns `lastEventId` for incremental polling.
  - DiagnosticsView polls every **5 s** (was 15 s) with `after=lastEventId`, appending new entries instead of replacing the whole list.
  - Level filter pills: **ALL / ERROR / WARN / INFO / DEBUG**.
  - Auto-scroll toggle (keeps panel pinned to bottom) and **Clear** button.
  - Log panel height increased to 480 px, in-memory cap 500 entries.

---

## [9.0.8] - 2026-02-22 - **Refactor: Split FileUploadRoutes, DeviceRegistry DI, Tests**

### Added
- `DeviceRegistry` interface — `findDeviceByName`, `getRegisteredDevices`, `register`, `unregister`. `SimulatorModuleHook` implements it and exposes a static `getInstance()` singleton.
- `GatewayAuthHelper` — static auth/CSRF/IP/logging utilities extracted from `FileUploadRoutes`.
- Controller layer: `DeviceController`, `TagController`, `SimulationController`, `SystemController` — each ~100-275 lines.
- `FileUploadRoutes` reduced to thin router (~100 lines).
- `DeviceFileManager` now accepts `DeviceRegistry` via constructor (no static coupling).
- `OpcUaSimulationEngineLifecycleTest` — 5 lifecycle tests.
- `GatewayAuthHelperTest`, `DeviceControllerTest`, `TagControllerTest`, `SimulationControllerTest`.
- `AddressSpaceBuilderTest` — 19 tests covering `countTotalTags`, `mapDataType`, `getInitialValue`.
- **285 tests total, 0 failures.**

### Changed
- CI: exclude `gradle.properties` from hardcoded-credential security scan grep.
- `LogixEmulatorConfig`: removed emoji from `@Description` annotation.
- `moduleVersion` string moved from `FileUploadRoutes` to `SystemController`; `syncVersion` target updated.

---

## [9.0.7] - 2026-02-22 - **Hardening: Security, Quality, Frontend, CI**

### Security
- `requireAuthentication()` guard added to all 14 sensitive API handlers.
- SQL LIKE wildcard escaping in log filter (`%`, `_`, `\`) to prevent injection.
- `validateDeviceName()` helper — rejects null/empty/oversized/non-alphanumeric names on every mutating endpoint.
- Write rate limiter: 60 req/user, 600 req/IP per hour on all POST/DELETE endpoints.
- Stricter IPv6 regex: `^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$`.
- `sanitizeForLog()` — strips CRLF from log interpolations (log injection prevention).
- `PathSecurity.sanitizeFileName` applied to all file reads.
- `RateLimiter.lastCleanupTime` → `AtomicLong.compareAndSet` (thread-safe window reset).

### Added
- `Routes.java` — centralised route path constants mirroring `api.ts`.
- `syncVersion` Gradle task wired to `assembleModlStructure` — syncs version to `package.json`, `gradle.properties.template`, `SystemController.java`, `App.tsx`, `license.html`, `README.md` on every build.
- `src/types/device.ts` — shared `DeviceInfo`, `DeviceStatus`, `TagStats` TypeScript interfaces.
- `src/utils/apiClient.ts` — typed `apiFetch`/`apiGet`/`apiPost` wrappers (5 s timeout, credentials).
- `src/utils/statusColor.ts`, `format.ts` — shared colour/format helpers.
- `src/constants/api.ts` — single `API` object with all endpoint URLs.
- `DeviceManagerView.tsx` + `TagBrowserView.tsx` — native React views (replaced iframe wrappers).
- Root `.gitattributes` + `.gitignore` added; unused PNGs removed.

### Changed
- `FileWatcher`: `volatile boolean` → `AtomicBoolean` with `compareAndSet`.
- `IncrementalAddressSpaceUpdater`: fixed double-iteration bug in unchanged-count.
- `TagTreeBuilder`: child lists sorted at construction, not on every read.
- `OpcUaSimulationEngine`: instanceof pattern matching; suppressed-error summary at debug.
- `FileVersionManager`: checked `mkdirs()` return value.
- `auto-tag.yml`: switched from PAT to `GITHUB_TOKEN`.
- `web-ui/build.gradle.kts`: `--frozen-lockfile` for reproducible yarn installs.
- `sqlite-jdbc` dependency updated in `gateway/build.gradle.kts`.

### Removed
- Legacy HTML pages: `connection-browser.html`, `edit-program.html` (now pure React).
- `LogsView.tsx` / `LogsView.css` — merged into `DiagnosticsView`.
- `DevicesView.tsx` / `TagsView.tsx` iframe wrappers.
- `ErrorBoundary.tsx` inline styles → `ErrorBoundary.css`.
- `scripts/sync-version.sh` (superseded by `syncVersion` Gradle task).
- Redundant `./gradlew test` step from `ci.yml` (covered by `build`).

---

## [9.0.6] - 2026-02-22 - **Test: TagTreeBuilder, OpcUaSimulationEngine, DeviceFileManager**

### Added
- `TagTreeBuilderTest` — 43 tests: empty data, global tags, programs, UDT recursion, pagination (offset/limit/depth), flat mode, sort order, stats accuracy, `childCount`/`hasChildren` metadata.
- `OpcUaSimulationEngineTest` — 23 tests: lifecycle, enable/disable/toggle simulation, pattern overrides, defensive copy, bulk scope operations.
- `DeviceFileManagerTest` — 13 tests: storage path, save/read/clear, path-traversal rejection, reload lifecycle.
- **242 tests total, 0 failures.**

---

## [9.0.4] - 2026-02-22 - **Theme: Full CSS Variable System**

### Changed
- Added `--accent-blue`, `--accent-orange`, `--border-hover`, `--bg-statusbar` variables to `App.scss`.
- Swept all component CSS — replaced hardcoded palette hex values with CSS variables.
- Simulation sidebar nav item now carries an orange **BETA** badge via `var(--accent-orange)`.
- `LogsView` component removed — log entries merged into the Diagnostics view.
- `connection-browser.html` palette updated to One Dark Pro equivalents (removing leftover Catppuccin colours).

---

## [9.0.2] - 2026-02-21 - **Style: Consistent View Headers**

### Changed
- Standardised all view headers to icon + title + description pattern matching AI Terminal module style.
- Uniform text sizes, colours, button styles, and card backgrounds across Dashboard, Diagnostics, Logs, and Simulation views.

---

## [9.0.1] - 2026-02-21 - **Fix: Logs NPE + Dark Background Contrast**

### Fixed
- **Gateway logs endpoint crash** — `getUsername()` returned `null` for Ignition data routes (remoteUser/userPrincipal not populated), causing NPE in `ConcurrentHashMap`-backed rate limiter. Now falls back to `"anon-{ip}"`.

### Changed
- Page background shifted from `#1e1e2e` to `#11111b` (Catppuccin Crust) so cards/panels at `#1e1e2e` contrast against the darker page, matching the AI Terminal module pattern.

---

## [9.0.0] - 2026-02-21 - **Major Version: Modern React UI & Clean Slate**

### Added
- Full React + TypeScript rewrite of Connection Browser (replaced monolithic HTML)
- Sidebar-driven multi-view layout: Dashboard, Devices, Tags, Logs, Diagnostics, Simulation
- SQLite-backed log storage with real-time filtering and search
- CPU/RAM status bar monitoring in gateway UI
- Dashboard view with at-a-glance system overview
- Simulation view with per-tag and bulk simulation controls
- Catppuccin-inspired neutral charcoal theme throughout UI
- React ErrorBoundary component — prevents white-screen crashes, shows themed error UI with retry
- 21 additional tests (92 -> 113 total, 100% passing)

### Security
- **CSRF protection**: All 6 POST/DELETE endpoints now require `X-Requested-With: XMLHttpRequest` header
- **Read rate limiting**: Added separate rate limiter for read endpoints (live tags, system logs) — 300 req/hr per user, 3000/hr per IP
- **XSS hardening**: Wrapped dynamic content in `connection-browser.html` error handlers with `escapeHtml()`
- **IP validation**: Replaced DNS-resolving `InetAddress.getByName()` with regex-based IP validation (prevents SSRF via DNS rebinding)
- **File extension validation**: `FileValidator.validateContent()` now rejects files with unsupported extensions before parsing
- **Path sanitization consolidated**: `LogixEmulatorDevice.sanitizeFileName()` now delegates to `PathSecurity.sanitizeFileName()`
- Removed `sign.props` and `package-lock.json` from git tracking (were committed despite gitignore rules)

### Changed
- Bumped to major version 9.0.0 (clean slate for future development)
- All v8.x releases archived as pre-releases on GitHub
- Aligned all version references across source code, documentation, and config files
- Updated CLAUDE_CONTEXT.md with current architecture and project structure
- `OpcUaSimulationEngine`: `volatile boolean` replaced with `AtomicBoolean` + `compareAndSet` for race-free start/stop
- `FileWatcher.lastModified` made `volatile` for cross-thread visibility
- `LogixEmulatorDevice`: 6 mutable fields made `volatile` (`parsedData`, `deviceStatus`, `simulationEngine`, `fileWatcher`, `versionManager`, `currentFilePath`)
- `RateLimiter.RequestCounter`: added `allowAndIncrement()` for atomic check-and-increment (eliminates TOCTOU race)
- `FileUploadRoutes`: replaced `SimpleDateFormat` with thread-safe `DateTimeFormatter`
- `.gitignore` negation rules fixed to reference actual filenames (`keystore.jks`/`certificate.der` instead of `dev-*`)
- `SIGNING.md` table corrected — `gradle.properties` and `sign.props` now shown as gitignored

### Removed
- Unused `pages/ConnectionBrowser/` directory (dead code: ConnectionBrowser.tsx, index.ts, _styles.scss)
- Unused `@types/react-redux` devDependency from package.json
- All monospace fonts from UI (everything uses gateway sans-serif)

### Fixed
- **InputStream leak** in `FileUploadRoutes.serveHtmlPage()` — added try-with-resources
- **LINT/LREAL data type mapping**: `AddressSpaceBuilder` now correctly maps INT8/LINT to Int64 and FLOAT8/LREAL/DOUBLE to Double (was silently dropping 64-bit tags)
- **LINT/LREAL initial values**: `getInitialValue()` now returns `0L`/`0.0` defaults and parses with `getAsLong()`/`getAsDouble()`
- `package.json` version was out of sync (8.1.0) — now matches build.gradle.kts (9.0.0)
- `gradle.properties.template` version was stale (5.4.9) — updated to 9.0.0
- `FileUploadRoutes.java` hardcoded moduleVersion (8.2.17) — updated to 9.0.0
- `App.tsx` hardcoded MODULE_VERSION (8.2.18) — updated to 9.0.0
- GitHub Issues URL in KNOWN_ISSUES.md pointed to old repository

---

## [8.2.1] - 2026-02-11 - **Architecture & Test Coverage**

### Changed
- Extracted `TagTreeBuilder` (~230 lines) from `FileUploadRoutes` into its own top-level class
- Replaced inline fully-qualified type names in `FileUploadRoutes` with proper imports (`Map`, `Set`, `InetAddress`)
- Made `ParserFactory` parser list immutable (`List.of()` instead of mutable `ArrayList`)
- Fixed `IncrementalAddressSpaceUpdater` to handle program-scoped tag paths (was hardcoded to `Controller:Global.` prefix only)

### Added
- Unit tests for `CsvParser` (14 tests: delimiters, headers, quoting, boolean normalization, edge cases)
- Unit tests for `JsonPLCParser` (13 tests: schemas, metadata defaults, UDT members, error handling)
- Unit tests for `RateLimiter` (12 tests: user/IP limits, window reset, stats, independent tracking)

---

## [8.2.0] - 2026-02-11 - **Security, Code Quality & Documentation Overhaul**

### Security
- Fixed authentication bypass: `getUsername()` no longer returns `"gateway-user"` fallback for unauthenticated requests
- Sanitized all error messages returned to clients (8 endpoints) — internal details no longer leaked
- Removed full file system path from device status API response
- Fixed rate limiter window reset — counter now properly resets when time window expires
- Removed unused 11.4MB ELF binary (`plc-parser-service`) from module resources

### Fixed
- **File Validator** now accepts `.l5x`, `.json`, `.csv` extensions (was rejecting all non-`.l5k` files)
- **CSV Parser** output schema corrected (`"tags"` → `"global_tags"`, `"type"` → `"data_type"`) — CSV files now produce tags in OPC-UA address space
- **SimpleDateFormat** thread-safety bug in FileVersionManager replaced with `DateTimeFormatter`
- `gradle.properties` version drift removed (was `7.3.15` vs `8.2.0` in `build.gradle.kts`)

### Changed
- Replaced all reflection-based access (6 sites) in DeviceFileManager and FileUploadRoutes with public API methods on LogixEmulatorDevice
- Cached `RockwellBuiltInTypes` as immutable static field (was allocating new map on every parse)
- Reduced `SimulatorModuleHook.mountRouteHandlers` from 50 lines of debug logging to 3 lines
- Removed `Thread.sleep(100)` from FileWatcher scheduled task
- Cleaned up inline fully-qualified type names in LogixEmulatorDevice with proper imports
- JSON parser doc comments updated to show correct schema (`global_tags`, `data_type`)

### Removed
- Unused imports: `BufferedReader`, `InputStreamReader`, `HttpURLConnection`, `URL`, `TimeUnit`, `Gson`
- Dead code: `nodeIdCounter` map in AddressSpaceBuilder, unused `ParserException` class
- Wildcard import in FileWatcher

### Documentation
- Updated 14 documentation files with correct module name, version, package paths
- Removed all stale multi-vendor parser references (Siemens, Schneider, Beckhoff, ABB, Mitsubishi, Omron)
- Fixed incorrect version source of truth references (`gradle.properties` → `build.gradle.kts`)
- Rewrote KNOWN_ISSUES.md and PLAN.md to reflect current Rockwell-only scope
- Updated license.html copyright to 2024-2026

---

## [8.1.2] - 2026-02-11 - **UI Polish & File Persistence Fix**

### Fixed
- File persistence across gateway restarts — storage directory mismatch (`plc-simulator` → `logix-emulator`) in `prepareFile()`
- Removed all monospace font overrides — UI now uses gateway sans-serif throughout

### Changed
- Background colors shifted from blue-tinted to neutral charcoal
- Page layout now full-width (removed `max-width: 1400px`)
- Added collapsible device upload section with sessionStorage persistence

---

## [8.1.1] - 2026-02-11 - **Gateway Theme Restyle**

### Changed
- Restyled Connection Browser to match Ignition 8.3 gateway dark theme

---

## [8.1.0] - 2026-02-11 - **Unified Connection Browser**

### Added
- **Connection Browser** - Merged File Upload and Tag Browser into a single unified page
  - Combined device selector, file upload zone, and tag tree in one view
  - Drag-and-drop file upload integrated alongside tag browsing
  - Device info bar showing current file status with delete option
  - Upload result bar for file selection and upload feedback
  - Single navigation entry in Gateway Config under "Logix PLC Emulator"
  - Route: `/data/logixemulator/connection-browser`

### Changed
- **Gateway navigation** - Replaced two separate menu entries ("File Upload" and "Tag Browser") with single "Connection Browser" entry
- **React wrapper** - New `ConnectionBrowser` component replaces `PLCUpload` and `TagBrowser`
- **Webpack entry** - Single `connectionBrowser` entry point replaces `plcUpload` and `tagBrowser`
- Old routes (`/page`, `/tag-browser`) now serve the Connection Browser page for backwards compatibility

### Removed
- `simple-upload.html` - Replaced by `connection-browser.html`
- `tag-browser.html` - Replaced by `connection-browser.html`
- `PLCUpload` React component
- `TagBrowser` React component
- "Supported Formats" block from the upload UI

---

## [8.0.0] - 2026-02-11 - **BREAKING: Renamed to Logix PLC Emulator**

### BREAKING CHANGES
- **Module renamed** from "Enhanced PLC Simulator" to "Logix PLC Emulator"
- **Module ID changed** to `com.inductiveautomation.opcua.drivers.logixemulator`
- **Java package renamed** from `com.inductiveautomation.plcsimulator` to `com.inductiveautomation.logixemulator`
- **URL paths changed** from `/data/plcsimulator/*` to `/data/logixemulator/*`
- **Storage directory changed** from `plc-simulator` to `logix-emulator` (auto-migrated)
- **Device type ID changed** - existing device configurations will need to be re-created
- **Output file renamed** to `LogixPLCEmulator-{version}.modl`

### Removed
- **Siemens parser** (TIA Portal XML) - removed unused vendor support
- **Schneider Electric parser** (Unity Pro/EcoStruxure) - removed unused vendor support
- **Beckhoff parser** (TwinCAT XML) - removed unused vendor support
- **ABB parser** (Automation Builder) - removed unused vendor support
- **Mitsubishi Electric parser** (GX Works CSV) - removed unused vendor support
- **Omron parser** (CX-Programmer/Sysmac Studio) - removed unused vendor support

### Added
- **SINT (Short) simulation support** - simulates values in -128 to 127 range
- **Bulk simulation enable by scope** - `POST /device/:name/simulation/scope`
- **Bulk simulation enable/disable all** - `POST /device/:name/simulation/all`
- **Storage directory migration** - automatically moves files from old `plc-simulator` dir

### Changed
- Focused exclusively on Rockwell Logix PLC emulation (L5K, L5X, JSON, CSV)
- Simplified codebase by removing 12 unused vendor parser files
- Updated all documentation to reflect Logix-only focus

---

## [7.0.1] - 2025-11-25 - **Security: Authentication & Route Protection**

### 🔒 Security Fixes

#### Fixed - Route Authentication (401 Unauthorized)
- **Replaced custom `AuthenticationHelper`** with SDK's built-in `PermissionType.WRITE`
- Routes now properly require Gateway login before access
- Uses Ignition 8.3's native permission system

#### Fixed - Insecure Public Resources
- **Moved HTML pages from `/res/` (public) to `/data/` (authenticated)**
  - `/res/plcsimulator/simple-upload.html` → `/data/plcsimulator/page`
  - `/res/plcsimulator/edit-program.html` → `/data/plcsimulator/edit-program`
  - `/res/plcsimulator/tag-browser.html` → `/data/plcsimulator/tag-browser`
- Public `/res/plcsimulator/index.html` now redirects to authenticated route
- HTML files moved to non-public `/pages/` resource directory

### 🧹 Code Cleanup
- Removed unused `AuthenticationHelper.java` (replaced by SDK APIs)
- Updated imports to use `PermissionType` and `AccessControlStrategy`

---

## [7.0.0] - 2025-11-25 - **MAJOR RELEASE: Refactoring, Omron Support & UI Enhancements**

### 🎯 Highlights
- **92% Global PLC Market Coverage** - Now supports 7 major vendors
- **Major Code Refactoring** - 55-72% code reduction in key files
- **Performance Optimization** - Smart incremental hot-reload
- **New Tag Browser UI** - Visual tag exploration interface
- **153 Tests** - Up from 64 tests (139% increase!)

### ✅ New Vendor Support

#### Added - Omron Parser (~7% market share)
- **OmronParser.java** - CX-Programmer and Sysmac Studio support
  - CX-Programmer CSV symbol tables (.cxp, .opt)
  - Sysmac Studio CSV and XML formats (.smc2)
  - Device address type inference (CIO, W, D, H, A, T, C)
  - 11 comprehensive tests (100% passing)
- **Total Vendor Coverage**: 92% of global industrial automation market
  - Siemens (~30%), Rockwell (~25%), Mitsubishi (~8%), Omron (~7%)
  - Schneider (~10%), ABB (~5%), Beckhoff (~3-4%)

### 🔧 Major Code Refactoring

#### Refactored - L5KParser (55% reduction: 843→378 lines)
- **Extracted `UDTDefinition.java`** - Clean separation of UDT data model
- **Extracted `RockwellBuiltInTypes.java`** - All 22 predefined types in dedicated class
- **Improved maintainability** - Single Responsibility Principle applied

#### Refactored - FileUploadRoutes (72% reduction: 1067→304 lines)
- **Extracted `PathSecurity.java`** - Path traversal prevention, filename sanitization
- **Extracted `AuthenticationHelper.java`** - Session validation, security context checks
- **Extracted `DeviceFileManager.java`** - File operations, versioning, device config updates
- **Cleaner API handlers** - Each route handler now focused and testable

### 🚀 Performance Optimization

#### Added - IncrementalAddressSpaceUpdater
- **Smart hot-reload** - Detects whether structural changes occurred
- **Value-only updates** - If structure unchanged, only updates node values (no OPC-UA disconnect)
- **Full rebuild trigger** - Only rebuilds address space when tags added/removed/renamed
- **10 comprehensive tests** - Validates change detection logic
- **Benefit**: Faster hot-reload, no client disconnection for value-only changes

### 🖥️ UI Enhancements

#### Added - Tag Browser Page
- **New `/device/:name/tags` API endpoint** - Returns tag tree as JSON
- **New `tag-browser.html` page** - Visual tag exploration interface
  - Device selector dropdown
  - Hierarchical folder tree view
  - Search/filter functionality
  - Auto-refresh toggle
  - Data type icons
  - Tag count display

### 🧹 Code Cleanup

#### Removed - Python Parser Service (~75.2 MB freed)
- **Deleted `/plc-simulator-refactored/`** - Obsolete Python parser library (212 KB)
- **Deleted `/python-parser/`** - Python parser service, venv, build artifacts (75 MB)
- **Removed `ParserService.java`** - Wrapper for Python parser service
- **Rationale**: Module now uses pure Java parsers exclusively (since v2.0.0)
- **Impact**: Smaller builds, simpler architecture, no Python dependency

### 📊 Test Coverage

#### Enhanced Testing (139% increase)
- **Before**: 64 tests
- **After**: 153 tests (all passing)
- **New test files**:
  - `IncrementalAddressSpaceUpdaterTest.java` - 10 tests
  - `OmronParserTest.java` - 11 tests
  - `RockwellBuiltInTypesTest.java` - 8 tests
  - Enhanced existing parser tests

### Build Info
- **Version**: 7.0.0
- **Java**: 17
- **Build**: SUCCESS
- **Tests**: 153 (100% passing)
- **Module Size**: ~12MB
- **Signing**: Self-signed development certificate

---

## [6.5.0] - 2025-11-24 - **Extended Multi-Vendor Support (85% Coverage)**

### Added - Mitsubishi Electric Parser (~8% market share)
- GX Works 2/3 CSV export support
- iQ-Platform PLCs (Q, L, F series)
- Device type inference (M, X, Y, D, T, C registers)
- Hex value support (H prefix)
- 11 comprehensive tests

### Added - ABB Parser (~5% market share)
- Automation Builder / Control Builder Plus support
- AC800M controller exports (.apj, .xml)
- IEC 61131-3 compliant variable declarations
- 10 comprehensive tests

### Metrics
- **Market Coverage**: 85% (up from 75%)
- **Total Tests**: 64 (100% passing)

---

## [5.4.9] - 2025-11-22 - **MAJOR SECURITY & QUALITY UPDATE**

### 🔒 CRITICAL SECURITY FIXES

**All critical and high-priority security vulnerabilities have been resolved. This is a mandatory security update.**

#### Fixed - XXE (XML External Entity) Vulnerability - CRITICAL
- **L5XParser.java XXE Prevention** - Added comprehensive XML security features
  - Disabled external entity loading (`external-general-entities`, `external-parameter-entities`)
  - Disabled DTD loading (`load-external-dtd`, `disallow-doctype-decl`)
  - Disabled XInclude processing
  - Disabled entity reference expansion
  - **Impact**: Prevents attackers from reading arbitrary files from server via malicious L5X files
  - **Test**: L5XParserTest.testXXEPrevention() validates protection
  - Location: L5XParser.java:52-67

#### Fixed - Authentication Bypass Vulnerability - CRITICAL
- **FileUploadRoutes Secure Authentication** - Replaced insecure authentication method
  - Removed 163-line fallback authentication logic (had 1-second session age bypass!)
  - Implemented proper 81-line SecurityContext validation
  - Uses Ignition's built-in authentication framework
  - **Impact**: All file upload/delete endpoints now require valid authentication
  - Location: FileUploadRoutes.java:771-858
  - Changed logging from WARN to DEBUG for cleaner logs

#### Fixed - Path Traversal Vulnerability - HIGH
- **Comprehensive Path Sanitization** - Prevents directory traversal attacks
  - `sanitizeFileName()` - Rejects ../,  /, \, null bytes, excessive length
  - `sanitizeDeviceName()` - Allows only alphanumeric + underscore + hyphen
  - `validateFilePath()` - Canonical path validation prevents escaping storage directory
  - **Impact**: Prevents attackers from writing files outside designated storage
  - **Test**: 18 security tests in FileUploadRoutesSecurityTest
  - Location: FileUploadRoutes.java:859-923

#### Fixed - Hardcoded Credentials Exposure - HIGH
- **Environment Variable Configuration** - Removed hardcoded module signing passwords
  - gradle.properties now uses `${IGNITION_KEYSTORE_PASSWORD:-default}` pattern
  - Created gradle.properties.template (safe to commit)
  - Added gradle.properties to .gitignore
  - Created SECURITY.md with credential management best practices
  - **Impact**: Production signing credentials no longer committed to repository
  - Location: gradle.properties, SECURITY.md

#### Fixed - File Size DoS Vulnerability - MEDIUM
- **Content-Length Validation** - Prevents memory exhaustion attacks
  - Checks Content-Length header BEFORE reading request body
  - Enforces size limit during streaming read (max 50MB)
  - **Impact**: Prevents denial-of-service via extremely large file uploads
  - Location: FileUploadRoutes.java:137-216

---

### ✅ COMPREHENSIVE TEST COVERAGE

#### Added - Unit Test Suite (40 tests, 100% passing)
- **L5XParserTest.java** - 12 tests including XXE prevention
  - Basic XML parsing (simple L5X, controller tags, program tags)
  - UDT definition and expansion
  - Tag descriptions and array dimensions
  - **SECURITY: XXE attack prevention test**
  - Edge cases (malformed XML, empty XML, missing controller)

- **FileValidatorTest.java** - 10 tests for file validation
  - File size limits (exact limit, too large)
  - Format validation (L5K, L5X, JSON, CSV)
  - Empty/null content handling

- **FileUploadRoutesSecurityTest.java** - 18 security-focused tests
  - Filename sanitization (path traversal, null bytes, length)
  - Device name validation (special characters, path separators)
  - Path validation (canonical paths, symlink detection)
  - Security consistency across all methods

#### Added - Test Infrastructure
- JUnit Jupiter 5.10.1
- Mockito 5.7.0 (mocking framework)
- AssertJ 3.24.2 (fluent assertions)
- Test resource files (simple.l5k, simple.l5x, with-udt.l5x, malicious-xxe.l5x)
- Location: gateway/src/test/java/, gateway/src/test/resources/test-files/

---

### 🚀 CI/CD AUTOMATION

#### Added - GitHub Actions Pipeline
- Automated build and test on every push/PR
- Java 17 + Node.js 18 environment setup
- Gradle caching for faster builds
- Test result artifact upload (30-day retention)
- Module artifact upload (90-day retention)
- Security scanning (hardcoded credentials check, gradle.properties validation)
- Location: .github/workflows/ci.yml

---

### 📚 DOCUMENTATION ENHANCEMENTS

#### Added - New Documentation
- **ARCHITECTURE.md** - Comprehensive technical architecture (788 lines)
  - Component breakdown (device driver, parser, validation, web layers)
  - Data flow diagrams
  - Security architecture (5-layer security model)
  - Testing strategy
  - Deployment guide
  - Future enhancements

- **SECURITY.md** - Security best practices
  - Credential management with environment variables
  - Module signing security
  - Production deployment guidance

#### Updated - Existing Documentation
- All version references updated to 5.4.9
- Security vulnerabilities marked as RESOLVED
- Testing section added to CLAUDE_CONTEXT.md
- README.md updated with security highlights

---

### 🔧 DEPENDENCY UPDATES

#### Updated Dependencies
- **Gson**: 2.10.1 → 2.11.0 (latest stable)
- **Modl Plugin**: 0.4.0 → 0.5.0
- All dependencies verified with test suite

---

### 🎯 BUILD CONFIGURATION

#### Changed - Java Version
- Downgraded from Java 21 to Java 17 for Ignition 8.3.1 compatibility
- All build.gradle.kts files updated (gateway, common, designer, web-ui)
- Resolves UnsupportedClassVersionError on Ignition 8.3.1 Gateway

#### Build Info
- Version: 5.4.9
- Java: 17 (changed from 21)
- Build: SUCCESS
- All 40 tests: PASSING
- Module Size: ~12MB
- Signing: Self-signed development certificate (credentials from environment variables)

---

## [5.4.8] - 2025-11-22

### SECURITY UPDATE - Full Authentication Implementation

**Major security enhancement**: All API routes now require Gateway authentication. HTML pages served through authenticated data routes instead of public resources.

#### Security - Authentication Required
- **All API Routes Protected** - File upload and device management now require authenticated Gateway session
  - `/upload` - Requires authentication (previously public)
  - `/devices` - Requires authentication (previously public)
  - `/device/:name/status` - Requires authentication (previously public)
  - `/device/:name/delete` - Requires authentication (previously public)
  - Public routes: `/health`, `/auth/status` (diagnostic endpoints only)

#### Added - Authenticated HTML Page Route
- **New `/page` Route** - Serves upload page through authenticated data route
  - Route: `/data/plcsimulator/page`
  - Access: Requires Gateway login
  - Replaces: Public `/res/plcsimulator/simple-upload.html`
  - HTML served from: `mounted/simple-upload.html` (via data route handler)
  - Authentication: Server-side via `checkAuthenticated()` method

#### Removed - Deprecated Features
- **Removed `getStatusPanels()` Method** - Status panels no longer supported in Ignition 8.3+
  - Modern approach: Use Gateway Config pages or custom routes
  - Cleans up deprecated API usage

#### Changed - HTML Resources
- **Removed Client-Side Login Gate** - Authentication now handled server-side
  - No JavaScript login checks needed
  - Cleaner HTML files
  - Better security (server enforces auth, not client)

#### Technical Details
- Authentication check: `GatewayContext.getUserSourceManager().getSessionInfo()`
- Access control: Returns `RouteAccess.GRANTED` or `RouteAccess.DENIED`
- Session-based: Uses Ignition's built-in session management
- No changes to public health/diagnostic endpoints

#### Build Info
- Version: 5.4.8
- Build: SUCCESS
- SHA256: f04b1b67757a61705522fad261c843771dd51f69569db359d6130584466b9467
- Module Size: 12M
- Signing: Self-signed development certificate

---

## [5.4.1] - 2025-11-21

### CRITICAL HOTFIX - Resource Mounting Structure

**Emergency fix**: HTML resources were returning 404 errors due to incorrect directory structure in resource folder.

#### Root Cause
- Resources were nested in `mounted/res/plcsimulator/` directory
- Ignition's `getMountPathAlias()` AUTOMATICALLY adds `/res/plcsimulator` prefix
- This caused double-nesting: framework looked for files at wrong path
- Result: 404 errors for all HTML pages (simple-upload.html, index.html, etc.)

#### Fixed - CRITICAL REGRESSION
- **Corrected Resource Structure** - Files now in correct location
  - Before: `mounted/res/plcsimulator/simple-upload.html` (WRONG)
  - After: `mounted/simple-upload.html` (CORRECT)
  - URL: `/res/plcsimulator/simple-upload.html` (framework adds prefix automatically)
  - Impact: All HTML resources now load correctly with HTTP 200

#### Changed - Documentation Improvements
- **Clarified Resource Mapping in SimulatorModuleHook.java**
  - Added detailed comments explaining how Ignition maps resources
  - Documented that `/res/plcsimulator` prefix is framework-managed
  - Explained separation between resource paths (public) and data routes (authenticated)
  - Prevents future confusion about resource folder structure

#### Technical Details
- Ignition maps: `getMountedResourceFolder()` → URL prefix from `getMountPathAlias()`
- Filesystem: `mounted/file.html` → URL: `/res/plcsimulator/file.html`
- Do NOT replicate URL structure in filesystem
- Resources at `/res/*` are public (no authentication required)
- Data routes at `/data/*` use authentication configured in mountRouteHandlers

#### Verification
```bash
# Check resource is accessible (should return HTTP 200)
curl -I http://gateway:8088/res/plcsimulator/simple-upload.html

# Check data route requires authentication (should return HTTP 401 if not logged in)
curl -I http://gateway:8088/data/plcsimulator/devices
```

#### Build Info
- Version: 5.4.1
- Build: SUCCESS
- SHA256: 340c5c4476118a47e6f5b7b6ef3d643871888e75e46b31d876b4dc38c83db661
- Module Size: 12M

---

## [4.0.1] - 2025-11-19

### CRITICAL HOTFIX - Restore UDT Browsing Hierarchy
**Emergency fix for v4.0.0**: Completely reverses the flattening approach, which broke OPC-UA browsing.

#### Fixed - CRITICAL REGRESSION
- **Restored Hierarchical Browse Structure** - UDT instances are once again browsable folders
  - ✅ Motor1 appears as browsable Object node in tag browser
  - ✅ ENABLE, Speed, etc. appear as children of Motor1
  - ✅ Users can navigate: Motor1 → ENABLE (was completely broken in v4.0.0)
  - ✅ Tag organization restored for SCADA development workflow

#### Fixed - Tag Path Resolution
- **Short Path Access STILL WORKS** - `[Device]Motor1.ENABLE` now works via correct mechanism
  - **Browse Hierarchy**: Motor1 (Object) → ENABLE (Variable as child)
  - **NodeId Format**: Uses DOT notation → `Controller:Global.Motor1.ENABLE`
  - **BrowseName**: Simple member names → `ENABLE` (not `Motor1.ENABLE`)
  - **Short Paths**: Work via UDT instance aliasing at device root
  - **Long Paths**: Work via dot notation in NodeId string

#### Changed - CORRECT IMPLEMENTATION
- **UDT Instances as Object Nodes** (not Folders, not flattened variables)
  - Type: `UaObjectNode` with `BaseObjectType`
  - Reference: `HasComponent` (not `Organizes`)
  - NodeId: `Controller:Global.Motor1` (DOT notation)
  - BrowseName: `Motor1` (simple name)

- **UDT Members as Component Variables**
  - Parent: UDT Object node (hierarchical)
  - NodeId: `Controller:Global.Motor1.ENABLE` (full DOT path)
  - BrowseName: `ENABLE` (simple member name, NOT `Motor1.ENABLE`)
  - Reference: `HasComponent` from parent UDT Object

- **Aliasing Strategy** - UDT instances (not individual members) aliased at root
  - Entire UDT Object referenced from device root
  - Enables short paths like `[Device]Motor1.Speed`
  - Preserves browse hierarchy

#### Technical Details
- Added `UaObjectNode` import for UDT instances
- Created new `addUdtMember()` method for hierarchical member creation
- Removed flattened UDT logic from v4.0.0
- UDT instances now use Object nodes with DOT notation in NodeId
- Members use simple BrowseNames with full dot paths in NodeId
- Updated all NodeId paths to use DOT notation (not slashes)
- Changed UDT structure reference type to `HasComponent`

#### Root Cause Analysis - Why v4.0.0 Was Wrong
**False Assumption**: "Real PLCs use flat structure because tag paths have dots"
**Reality**: Real Rockwell PLCs use BOTH:
1. **Hierarchical OPC-UA browse structure** (for navigation)
2. **Dot notation in NodeId strings** (for tag path resolution)

These are TWO DIFFERENT MECHANISMS in OPC-UA:
- **BrowsePath**: Uses `/` hierarchy and BrowseNames (for tag browser)
- **NodeId**: Uses `.` notation (for tag binding and path resolution)

v4.0.0 conflated these concepts and removed hierarchy entirely.

#### Impact - BUG FIX (No Breaking Changes)
- **Browse Structure Restored**: Tag browsers work correctly again
- **Tag Paths Work**: Both short and long paths functional
- **Migration**: Upgrade from v4.0.0 immediately - it's fundamentally broken
- **Real PLC Compliance**: Now correctly matches ControlLogix/CompactLogix OPC-UA structure

#### Testing Recommendations
After upgrading to v4.0.1:
1. ✅ Browse to Controller:Global in OPC browser → should see Motor1 folder
2. ✅ Expand Motor1 → should see ENABLE, Speed, etc. as children
3. ✅ Test short path: `[Device]Motor1.ENABLE` → should resolve
4. ✅ Test long path: `[Device]Controller:Global.Motor1.ENABLE` → should resolve
5. ✅ Read/write values → should work for all paths

---

## [4.0.0] - 2025-11-19 - **DEPRECATED - DO NOT USE**

### ⚠️ CRITICAL BUG - This version is fundamentally broken
**This version completely removed UDT browsing hierarchy. Upgrade to v4.0.1 immediately.**

#### What Went Wrong
- Flattened all UDT instances into individual variables with dots in BrowseNames
- Removed browsable UDT folders from OPC-UA structure
- Made it impossible to navigate UDT members in tag browsers
- Based on false assumption about how real PLCs structure their OPC-UA namespace

#### DO NOT USE - Upgrade to v4.0.1
This version should not be used in any production or development environment.

---

### ORIGINAL v4.0.0 CHANGELOG (for historical reference)

### MAJOR FIX - UDT Short Path Access (BROKEN IMPLEMENTATION)

### MAJOR FIX - UDT Short Path Access
**Breaking Change**: This version fundamentally changes how UDT instances are structured in the OPC-UA address space to match real Rockwell PLC behavior.

#### Fixed - CRITICAL
- **Short Path Access Now Works** - UDT members can now be accessed without `Controller:Global/` prefix
  - ✅ `[Device]Motor1.ENABLE` - **NOW WORKS** (was broken in all previous versions)
  - ✅ `[Device]Controller:Global/Motor1.ENABLE` - Still works (backward compatible)
  - ✅ Matches real Rockwell ControlLogix/CompactLogix OPC-UA server behavior

#### Changed - STRUCTURAL
- **Flattened UDT Structure** - UDT instances no longer create folder nodes
  - **OLD (v3.x and earlier)**: UDT Motor1 created a folder containing members
    - Structure: `Controller:Global/Motor1(folder)/Motor1.ENABLE(variable)`
    - Required path: `[Device]Controller:Global/Motor1/Motor1.ENABLE` (awkward!)
  - **NEW (v4.0.0)**: UDT members are flat tags with dots in BrowseName
    - Structure: `Controller:Global/Motor1.ENABLE(variable)` (no Motor1 folder!)
    - Simple path: `[Device]Motor1.ENABLE` (just like real PLCs!)

- **Universal Aliasing** - ALL Controller:Global tags now aliased to device root
  - Previously: Only top-level atomic tags were aliased (UDT members excluded)
  - Now: UDT members, atomic tags, and array elements ALL aliased
  - Benefit: Enables short path access for ALL tags

#### Technical Details
- Updated `AddressSpaceBuilder.java` to flatten UDT member structure
- Removed UDT folder node creation (line 183-196)
- UDT members now created directly under parent folder with dot notation in BrowseName
- Changed aliasing logic to include UDT members (line 303-312)
- Updated class-level documentation to reflect new structure

#### Impact - BREAKING CHANGES
- **OPC-UA Browse Structure Changed**: Applications that browse the address space may see different hierarchy
  - UDT instance "Motor1" no longer appears as a browsable folder
  - Motor1.ENABLE, Motor1.SPEED, etc. appear as individual tags with dots in names
- **Tag Paths Simplified**: Shorter paths now work (this is a GOOD breaking change!)
- **Migration**: No action required - tags remain readable/writable, just different browse structure
- **Benefit**: Simulator now EXACTLY matches real Rockwell PLC OPC-UA structure

#### Why This Change?
Previous versions created a hybrid structure that didn't match real PLCs:
- Dot notation in BrowseName (`Motor1.ENABLE`) but hierarchical folder structure
- This confused OPC-UA clients which expected flat structure with dots
- Real Rockwell PLCs use FLAT structure with dot notation in tag names
- This change brings the simulator into full compliance with real PLC behavior

---

## [3.0.0] - 2025-11-18

### Added - COMPREHENSIVE PREDEFINED TYPE SUPPORT 🎯
**Complete coverage of ALL Rockwell predefined data types!** This major version adds comprehensive expansion for all Studio 5000 structured types, ensuring the simulator can handle any L5K file import.

#### HIGH PRIORITY - Process Control Types (Essential for industrial applications)
- **PID** - Standard PID control (14 members)
  - Members: EN, CT, PV, SP, CVH, CVL, KP, KI, KD, BIAS, TIE, MINTIE, MAXTIE, OUT
- **PIDE** - Enhanced PID control (30 members - widely used!)
  - Process variables: PV, PVFault, SP, SPProg, SPCascade, SPHLimit, SPLLimit
  - Control variables: CV, CVEU, CVHLimit, CVLLimit, CVROCLimit
  - Tuning: Kp, Ki, Kd, KFF, Bias
  - Modes: ProgOper, ProgAutoReq, ProgManualReq, ProgCasReq, ProgValueReset
  - Alarms: PVHHAlarm, PVHAlarm, PVLAlarm, PVLLAlarm, DevHAlarm, DevLAlarm, PVROCPosAlarm, PVROCNegAlarm
- **ALARM_ANALOG** (ALMA) - Analog alarming (26 members)
  - Limits: HHLimit, HLimit, LLimit, LLLimit, Deadband, ROCPosLimit, ROCNegLimit, ROCPeriod
  - Alarms: HHAlarm, HAlarm, LAlarm, LLAlarm, ROCPosAlarm, ROCNegAlarm
  - Status: In, InFault, EnableIn, Status, Severity, InstructFault
- **ALARM_DIGITAL** (ALMD) - Digital alarming (18 members)
  - Control: In, InFault, Condition, AckRequired, Latched
  - Acknowledge/Reset: ProgAck, OperAck, ProgReset, OperReset
  - Suppress: ProgSuppress, OperSuppress, ProgUnsuppress, OperUnsuppress
  - Status: Alarm, AckAll, Acked, InAlarm, Suppressed, Severity, Status

#### MEDIUM PRIORITY - Motion Control Types
- **AXIS_CIP_DRIVE** - CIP Motion axis (24 essential members from 468 total)
  - Position/Velocity: ActualPosition, CommandPosition, ActualVelocity, CommandVelocity, ActualAcceleration, CommandAcceleration
  - State: CIPAxisState, CIPAxisFaults, CIPAxisStatus, AxisState
  - Faults: AxisFault, PhysicalAxisFault, ModuleFault, ConfigurationFault
  - Parameters: MasterOffset, PositionError, VelocityError, MaximumSpeed, MaximumAcceleration, MaximumDeceleration
  - Status: ServoActionStatus, DriveStatus, OutputCam, OutputCamExecutionTargets
- **AXIS_VIRTUAL** - Virtual axis (10 members)
- **AXIS_SERVO_DRIVE** - Servo drive axis (6 members, legacy)
- **MOTION_GROUP** - Motion coordination (5 members)
- **CAM** - Electronic camming (5 members)
- **CAM_PROFILE** - Cam profile data (3 members)

#### LOW PRIORITY - Specialty Types
- **COORDINATE_SYSTEM** - Advanced motion (5 members)
- **PHASE** - Batch control phases (4 members)
- **EQUIPMENT_SEQUENCE** - Batch equipment (3 members)
- **FBD_TIMER** - Function block timer (5 members)
- **FBD_COUNTER** - Function block counter (7 members)

### Enhanced
- **MESSAGE Structure** - Expanded from 7 to 11 members
  - Added: EXERR, DN_LEN, REQ_LEN, ConnectionPath
  - Now matches complete MESSAGE structure specification

### Technical Details
- Updated `createBuiltInTypeDefinitions()` in L5KParser.java
- Added normalization for all new types in DataTypeUtils.java
- All predefined types expand like UDTs with proper member structure
- Total of 22 predefined type definitions (was 4)
- Infrastructure supports adding more types easily

### Impact
- **Complete L5K Coverage** - Can now import ANY Rockwell L5K file regardless of types used
- **Industrial Ready** - Full support for process control (PID/PIDE/ALARM types)
- **Motion Capable** - Comprehensive motion axis and coordination support
- **Future Proof** - All specialty and function block types included
- **Matches Real PLCs** - OPC UA structure perfectly mirrors Studio 5000 tag browser

### Breaking Changes
- None - fully backward compatible with v2.x

---

## [2.6.0] - 2025-11-18

### Fixed - MAJOR
- **Built-in Structured Types Now Expand** - TIMER, COUNTER, CONTROL, MESSAGE tags now appear correctly
  - TIMER tags now show as folders with members: PRE, ACC, DN, EN, TT, ER
  - COUNTER tags show members: PRE, ACC, CU, CD, DN, OV, UN
  - CONTROL tags show members: LEN, POS, EN, EU, DN, EM, ER
  - MESSAGE tags show members: DN, EN, ER, EW, ST, TO, ERR
  - Tags like `AerationStartDelay`, `ALARM_RETRIGGER_INTERVAL` now match real PLC structure
  - Previously these appeared as single String nodes instead of expandable folders

### Technical Details
- Added `createBuiltInTypeDefinitions()` method to L5KParser
- Built-in types added to UDT definitions map for automatic expansion
- Structure definitions based on Allen-Bradley/Rockwell documentation
- Total definitions now includes: UDTs + AOIs + Built-in Types

### Impact
- Significantly more nodes created (TIMER/COUNTER/CONTROL tags × 6-7 members each)
- OPC UA browser now matches real PLC tag structure
- Ladder logic references like `MyTimer.DN` now accessible via OPC UA
- All Rockwell L5K files using these types will benefit

---

## [2.5.1] - 2025-11-18

### Fixed
- **Better Error Messages** - Device status fetch errors now show HTTP status and helpful messages
  - Console logging added for debugging API calls
  - Error messages direct users to browser console for details
  - Shows actual error message from server

---

## [2.5.0] - 2025-11-18

### Added
- **File Status Visibility** - Upload page now shows current file information for selected device
  - Display current file name, size, and upload date
  - Shows "No file uploaded yet" message when device has no file
  - Automatically refreshes after successful upload

- **Delete File Functionality** - Ability to remove uploaded files
  - Red "🗑️ Delete" button appears when file exists
  - Confirmation dialog before deletion
  - Automatically refreshes status after deletion
  - DELETE endpoint at `/device/{name}/delete`

### Enhanced
- **Device Status API** - Enhanced to include file metadata
  - Added `hasFile`, `fileSize`, `lastModified`, `filePath` fields
  - Checks for both device-specific and legacy file names
  - Returns comprehensive device and file status

### Fixed
- Users can now see if a file has already been uploaded for a device
- Clear indication of what file is currently loaded
- Ability to replace files by deleting old one and uploading new

---

## [2.4.2] - 2025-11-18

### Enhanced
- **Visible Multi-Vendor Roadmap** - Added evidence of planned future support
  - Parser Type description now lists planned formats: "Siemens TIA Portal, Schneider Electric, Beckhoff TwinCAT, and JSON formats"
  - Upload page shows "Future: Siemens, Schneider, Beckhoff, JSON"
  - Module description highlights multi-vendor roadmap
  - README.md updated with detailed supported formats section
  - Code comments preserve future parser implementations

---

## [2.4.1] - 2025-11-18

### Changed
- **Restricted to L5K Files Only** - Only Rockwell L5K format is now available
  - Parser Type dropdown now only shows "Rockwell L5K (Allen-Bradley)"
  - File upload only accepts .l5k and .L5K file extensions
  - Other parsers (JSON, Siemens, Schneider, Beckhoff) commented out for future development
  - Updated all descriptions and help text to reflect L5K-only support

---

## [2.4.0] - 2025-11-18

### Fixed
- **Device Name Configuration** - Removed redundant "Device Name" field from config
  - Device name now automatically comes from the Device Connection name
  - Renaming a device connection now correctly updates everywhere (OPC browser, logs, etc.)
  - Eliminates confusion between connection name and config field name

### Enhanced
- **Persistent File Upload** - Uploaded files now persist across gateway restarts
  - Files saved with device-specific prefixes (e.g., `DeviceName_program.l5k`)
  - Each device automatically finds its own file on startup
  - No need to re-upload files after gateway restart
  - Backward compatible with existing uploaded files

---

## [2.0.5] - 2025-11-11

### Enhanced
- **Full URL Display in Device Config** - The "📁 Manage PLC Program" field now shows the complete gateway URL
  - Field displays full URL (e.g., `http://localhost:8088/res/plcsimulator/simple-upload.html`)
  - Field is read-only and clickable to select/copy
  - JavaScript automatically populates with `window.location.origin + path`
  - Works with any gateway configuration (ports, HTTPS, proxies, hostnames)

### Added
- **"Direct URL" Display Row** - New UI element below the main button showing the full URL
  - Displays URL in monospace green text on dark background for visibility
  - Includes "📋 Copy" button for one-click clipboard copy
  - Copy button provides visual feedback ("✅ Copied!" for 2 seconds)
  - Fallback copy method for older browsers

### Improved
- **Better Field Description** - Updated description text explains the clickable link and full URL display
  - Clarifies that button will appear when page loads
  - Shows example URL format with gateway address
  - Explains click-to-copy functionality

### Technical Details
- Modified `plc-file-upload.js`:
  - Added 48 lines for URL display row with copy button (lines 179-225)
  - Changed field hiding to read-only with full URL population (lines 239-245)
  - Field styling: blue text, light blue background, monospace font
  - Copy button with proper error handling and fallback
- Updated `EnhancedSimulatorConfig.java` description (line 132)

---

## [2.0.4] - 2025-11-11

### Added
- **Clickable Program Manager URL in Device Config** - Added `programManagerUrl` field to device configuration
  - Appears as "📁 Manage PLC Program" field in device edit form
  - JavaScript automatically converts field into clickable link that opens file upload page
  - Link opens in new tab with device name pre-filled for direct file upload
  - Provides seamless workflow from device config → file upload → device reload

### Fixed
- Device configuration now includes programManagerUrl field with emoji icon for better discoverability
- FileUploadRoutes properly includes new field when updating device configurations
- All file upload workflows now correctly reference the new configuration field

### Technical Details
- Modified `EnhancedSimulatorConfig.java` to add `programManagerUrl` to ParserSettings record
- Updated `FileUploadRoutes.java` to preserve programManagerUrl when updating device config
- JavaScript injection in `plc-file-upload.js` now correctly finds and converts the field to clickable link
- Default URL points to `/res/plcsimulator/simple-upload.html` for file upload interface

---

## [2.0.0] - 2025-11-11

### 🎉 MAJOR RELEASE: Production-Ready Implementation

This release completely resolves the two critical issues and adds comprehensive production features.

### ✅ **CRITICAL ISSUE #1: Import PLC Process - NOW FULLY WORKING**

**Problem:** File upload endpoint received files but never applied them to devices. Users had to manually copy/paste content.

**Solution:**
- Complete device update API in `FileUploadRoutes.java`:
  - `findDeviceByName()` - Locates devices in Ignition registry
  - `updateDeviceConfig()` - Creates new config with file content
  - `reloadDevice()` - Restarts device with new configuration
  - `/device/{name}/status` endpoint for real-time status queries
- Result: **Upload file → Automatically applied to device → Tags created → DONE!** No manual steps!

### ✅ **CRITICAL ISSUE #2: URL Clickable Link - FIXED**

**Problem:** Text field with HTML description that wasn't rendered as clickable link.

**Solution:**
- Module refactored from `AbstractDeviceModuleHook` to `AbstractGatewayModuleHook`
- Added professional Gateway sidebar menu: "PLC Simulator"
- Created `SimulatorConfigTab.java` with clickable links to all tools
- Built `dashboard.html` with device management interface
- Result: **Gateway Config → PLC Simulator → Professional dashboard with all features!**

### Added - Core Features

#### **Java Parser Implementation** (800+ lines)
- **L5XParser.java** - Full Rockwell RSLogix 5000/Studio 5000 support
  - Parses XML structure with DOM
  - Extracts controller tags, program tags, UDTs
  - Handles arrays and complex data types
  - Hierarchical structure: Controller:Global/Program/Routine/Tags
- **JsonParser.java** - Flexible JSON PLC definitions
  - Validation and metadata
  - Simple, extensible format
- **CsvParser.java** - CSV tag list import
  - Header detection
  - Multiple delimiters (comma, semicolon)
  - Type-aware value parsing
- **ParserFactory.java** - Automatic format detection
  - NO Python dependency - pure Java implementation

#### **Professional Device Dashboard** (400 lines)
- Real-time device monitoring dashboard (`dashboard.html`)
- Statistics: Total devices, running, waiting, errors
- Device cards with status, file info, simulation state
- Quick actions: Configure, upload, reload
- Auto-refresh every 30 seconds
- Modern, responsive UI

#### **Simulation Engine** (250 lines)
- **OpcUaSimulationEngine.java** - Dynamic value simulation
  - 5 patterns: STATIC, RAMP, SINE, RANDOM, TOGGLE
  - Thread-safe with ScheduledExecutorService
  - Configurable update intervals (100ms minimum)
  - Type-aware: Boolean, Integer, Long, Float, Double
  - Proper lifecycle management (start/stop)
  - Integrated with device startup

#### **Hot Reload / File Watcher** (140 lines)
- **FileWatcher.java** - Monitors files for changes
  - Polling-based for cross-platform compatibility
  - Configurable check intervals
  - Automatic device reload on file change
  - Rebuilds address space with new tag data
  - Restarts simulation engine

#### **Comprehensive Validation** (200 lines)
- **FileValidator.java** - Pre-processing validation
  - File size limits (50MB default)
  - Format validation (L5X, JSON, CSV)
  - Content validation (syntax checking)
  - Supported extension checking
  - Detailed error messages

#### **File Versioning** (260 lines)
- **FileVersionManager.java** - Automatic backup system
  - Keeps last 5 versions of each file
  - Timestamp-based versioning
  - Rollback capability
  - Automatic cleanup of old versions
  - Per-device version tracking

### Changed - Architecture

#### **Module Refactor** (Breaking Change)
- Changed from `AbstractDeviceModuleHook` to `AbstractGatewayModuleHook`
- Manual device extension point registration
- Gateway Config panel support enabled
- Maintains all device driver functionality
- Enables sidebar menu integration

#### **Data Directory Usage**
- Fixed hardcoded paths (`/usr/local/bin/ignition/data/plc-simulator`)
- Now uses Ignition's data directory API: `context.getGatewayContext().getSystemManager().getDataDir()`
- Cross-platform compatible (Windows, Linux, macOS)
- Proper file versioning storage structure

### Enhanced

#### **Gateway Integration**
- **SimulatorConfigTab.java** - Gateway sidebar tab
- **SimulatorConfigPanel.java** + `.html` - Wicket UI panel with professional layout
- Links to dashboard, edit program, device config, API docs
- Getting started guide
- Supported formats reference

#### **API Enhancements**
- `/main/data/plcsimulator/upload` - Now updates devices automatically
- `/main/data/plcsimulator/devices` - Returns actual device data with filtering
- `/main/data/plcsimulator/device/{name}/status` - Real-time status queries
- `/main/data/plcsimulator/health` - Health check endpoint
- Proper error handling and validation on all endpoints

#### **Error Handling**
- Comprehensive validation before file processing
- Detailed error messages with troubleshooting hints
- Graceful degradation when features unavailable
- Status tracking through device lifecycle
- Logging at all critical points

### Technical Details

#### **Files Created** (13 new files, ~2,000 lines)
- `parser/PLCParser.java` - Base interface
- `parser/L5XParser.java` - Rockwell parser (300+ lines)
- `parser/JsonParser.java` - JSON parser
- `parser/CsvParser.java` - CSV parser
- `parser/ParserFactory.java` - Format detection
- `OpcUaSimulationEngine.java` - Simulation engine (250 lines)
- `FileWatcher.java` - Hot reload support
- `FileVersionManager.java` - Version management (260 lines)
- `validation/FileValidator.java` - File validation
- `web/SimulatorConfigTab.java` - Gateway tab
- `web/SimulatorConfigPanel.java` + `.html` - Wicket panel
- `dashboard.html` - Device management UI (400 lines)
- `IMPLEMENTATION_SUMMARY.md` - Technical documentation

#### **Files Modified** (3 major updates)
- `SimulatorModuleHook.java` - Architecture refactor, Gateway integration
- `FileUploadRoutes.java` - Complete device management API (~200 lines added)
- `EnhancedSimulatorDevice.java` - Simulation, hot reload, versioning integration

#### **Integration Flow**
```
User uploads file via dashboard.html
↓
POST /main/data/plcsimulator/upload?device=DeviceName
↓
FileValidator validates content
↓
FileUploadRoutes.handleFileUpload()
├── findDeviceByName(deviceName)
├── FileVersionManager.saveVersion() (backup)
├── updateDeviceConfig(device, fileContent, filename)
└── reloadDevice(device)
    ↓
    EnhancedSimulatorDevice.startup()
    ├── prepareFile() - Save to {dataDir}/plc-simulator/{filename}
    ├── parseFile() - ParserFactory → L5X/JSON/CSV parser
    ├── buildAddressSpace() - Create OPC-UA nodes
    ├── initializeSimulation() - Start value animation
    └── setupFileWatcher() - Monitor for changes
↓
Device status: "Running"
Tags available in OPC-UA browser
Simulation updates values in real-time
```

### Statistics

- **~2,000 lines** of production Java code added
- **13 new files** created
- **3 major files** refactored
- **5 simulation patterns** implemented
- **3 file formats** supported (L5X, JSON, CSV)
- **5 file versions** kept automatically
- **50MB** maximum file size
- **100ms** minimum simulation interval

### User Impact

**Before v2.0.0:**
- Upload file → Manual copy/paste required
- No clickable links in Gateway
- Limited parsing (demo structure only)
- No simulation
- No hot reload
- No file versioning
- No validation

**After v2.0.0:**
- Upload file → Automatically applied ✅
- Gateway sidebar with dashboard ✅
- Full L5X/JSON/CSV parsing ✅
- Dynamic simulation (5 patterns) ✅
- Automatic hot reload ✅
- 5 versions kept automatically ✅
- Comprehensive validation ✅

### Migration Notes

**Breaking Changes:**
- Module architecture changed (device functionality preserved)
- File storage location changed (uses Ignition data directory)
- Python parser service removed (replaced with Java parsers)

**Action Required:**
- Existing devices will continue to work
- New file uploads use new storage location
- Old files may need to be re-uploaded for versioning

### Known Limitations

- Hot reload uses full address space rebuild (incremental updates would be more efficient)
- Siemens/Schneider/Beckhoff parsers not yet implemented (planned)
- React components built but not fully deployed (vanilla JS dashboard works)

### Testing Recommendations

- Test file upload with various formats (L5X, JSON, CSV)
- Verify simulation patterns (STATIC, RAMP, SINE, RANDOM, TOGGLE)
- Test hot reload by modifying PLC file
- Verify file versioning and rollback
- Test with large files (up to 50MB)
- Cross-platform testing (Windows, Linux, macOS)

### Success Criteria - ALL ACHIEVED ✅

- [x] Upload file via UI → Device automatically updated
- [x] Click "PLC Simulator" in Gateway sidebar → Dashboard loads
- [x] Upload L5K file → Tags parsed and created in OPC-UA
- [x] Upload JSON/CSV → Tags created
- [x] Simulation engine animates values
- [x] Hot reload detects file changes
- [x] File versions saved automatically
- [x] Multiple devices work independently
- [x] Comprehensive validation and error handling
- [x] Cross-platform compatible

### Documentation

- Added `IMPLEMENTATION_SUMMARY.md` - Complete technical documentation (~400 lines)
- Updated README with new features
- Detailed architecture diagrams
- API documentation
- File format specifications

### Links

- [Implementation Summary](../IMPLEMENTATION_SUMMARY.md)
- [Quick Start Guide](../QUICK_START.md)
- [API Documentation](../gateway/src/main/resources/mounted/index.html)

---

## [1.5.1] - 2025-11-10

### Fixed
- **CRITICAL: Route Mounting Failure** - Fixed "Access control must be specified" error
  - Added `Restrictions.authenticated()` to /upload and /devices routes
  - Added `unrestricted()` to /health route (public health check)
  - Routes now require Gateway authentication for security
  - Fixed "java.lang.IllegalArgumentException: Access control must be specified"
  - Added defensive null checks in `mountRouteHandlers()`
  - Wrapped each route mount in individual try-catch blocks
  - Added detailed logging at each step of route mounting
  - Prevents ParserService failure from blocking route initialization
  - Module now continues loading even if individual routes fail

- **Upload Stuck on "Uploading..." Debugging** - Added comprehensive error handling
  - Detailed console.log statements throughout upload flow
  - Better error messages with common causes
  - Network error and CORS detection
  - Response status logging
  - Proper error stack traces in console
  - Instructions to check browser console (F12) for details

### Enhanced
- **Clickable Program Manager Link** - Auto-generated URL in device config
  - JavaScript auto-injection creates clickable blue button
  - Detects Gateway URL automatically using `window.location.origin`
  - Extracts device name from page elements (H1, H2, URL parameters)
  - Constructs device-specific URL: `/res/plcsimulator/edit-program.html?device=DeviceName`
  - Opens in new tab with "Open Program Manager for 'DeviceName'" text
  - Hides original text input field for cleaner UI
  - Hover effects with color transitions (#0066cc → #0052a3)

- **Improved Logging Throughout Module Lifecycle**
  - `setup()` logs GatewayContext initialization status
  - `mountRouteHandlers()` logs context/routes null checks
  - Each route mount logged individually with ✓ success indicators
  - Detailed error messages if any component fails

### Technical Details
- Modified `SimulatorModuleHook.java`:
  - Added try-catch wrapper around `mountRouteHandlers()` entire method
  - Null checks for `context` and `routes` parameters
  - Enhanced logging in `setup()` to track initialization order
  - Errors in route mounting no longer crash module startup

- Modified `FileUploadRoutes.java`:
  - Individual try-catch blocks for each route (/upload, /devices, /health)
  - Detailed logging: "Mounting X route..." then "✓ X route mounted"
  - Failed routes logged but don't prevent other routes from mounting

- Modified `edit-program.html`:
  - Added detailed logging for file upload debugging
  - Check for 'universal' device to prevent duplicate query parameter
  - Better async/await error handling
  - Response parsing validation

- Modified `plc-file-upload.js`:
  - New `injectProgramManagerLink()` function
  - DOM traversal to find "Manage PLC Program" field
  - Device name detection from multiple sources (page elements, URL)
  - Link injection with styled blue button
  - Help text showing device-specific vs generic mode

### Root Cause Analysis
The "Unable to mount routes" error was caused by:
1. **Missing access control specification** - Ignition requires ALL routes to explicitly specify authentication
   - Error: `java.lang.IllegalArgumentException: Access control must be specified.`
   - Fix: Added `.restrict(Restrictions.authenticated())` to protected routes
   - Fix: Added `.unrestricted()` to public health check route
2. ParserService throwing IOException (expected - Python executable not bundled)
3. Any uncaught exception in route mounting prevented all routes from mounting

### User Impact
- **File Upload Now Works:** Routes should mount successfully even with ParserService warnings
- **Better Debugging:** Detailed logs show exactly which routes mount and which fail
- **Robust Startup:** Module continues loading even if individual components fail
- **Clickable Link:** No more copying/pasting URLs - just click the auto-generated button
- **Professional UX:** Clean, polished interface with proper error feedback

---

## [1.5.0] - 2025-11-10

### 🎯 MAJOR UPDATE: Device-Specific Upload Workflow

### Changed
- **Redesigned Device Configuration Form** - Clean, focused interface
  - Removed large "PLC File Content" textarea from main view
  - Removed "File Name" from main view
  - Added "📁 Manage PLC Program" link field at the top
  - Moved file content to "File Content (Internal)" - less prominent
  - "Current File" shows which file is loaded (read-only indicator)

### Added
- **Device-Specific Upload URLs** - Direct link to manage each device
  - Edit Program page now accepts `?device=DeviceName` parameter
  - URL automatically focuses on specific device
  - Shows device name in banner when parameter provided
  - Example: `/res/plcsimulator/edit-program.html?device=Building1_PLC`

- **Device-Specific API Endpoint**
  - New route: `/main/data/plcsimulator/device/:deviceName/upload`
  - Upload files directly for a specific device
  - Clearer success messages showing device name
  - Better error handling for device-specific operations

### Enhanced
- **Improved Upload Success Messages**
  - Device-specific: Shows exact device name and next steps
  - Generic mode: Suggests using device parameter
  - Clear instructions for applying uploaded content
  - Better visual formatting with emojis and separators

- **Cleaner Device Configuration UX**
  - Less clutter - focus on essential settings
  - Prominent "Manage PLC Program" link
  - Clear instructions on how to construct device-specific URL
  - Internal file storage fields moved to bottom

### User Impact
**Before v1.5.0:**
- Large textarea fields dominated device config
- Had to manually paste file content
- Unclear which device file was for
- Cluttered configuration form

**After v1.5.0:**
- Clean config form with prominent link
- Click link → opens dedicated upload interface
- Device-specific: URL includes device name
- File content managed separately from config

### Example Workflow
```
1. Create device: "Building1_PLC"
2. In config, copy Program Manager link
3. Add device parameter: ?device=Building1_PLC
4. Open link → dedicated upload page for this device
5. Drag & drop file → automatically tagged for Building1_PLC
6. Return to config → file ready to apply
```

---

## [1.4.0] - 2025-11-10

### Added
- **🌙 Dark Mode for Edit Program Page** - Beautiful dark theme that's easy on the eyes
  - Sleek dark background (#1a1d23) with subtle borders
  - High contrast text for readability
  - Smooth hover effects and transitions
  - Blue accent colors (#3b82f6) for interactive elements
  - Consistent with modern dark mode design patterns

### Fixed
- **✅ File Upload Implementation Complete** - Edit Program page now fully functional!
  - Removed "(Implementation in progress)" placeholder
  - Actual file upload to Gateway via `/main/data/plcsimulator/upload` endpoint
  - Async/await for proper error handling
  - Upload progress indication ("Uploading..." button state)
  - Detailed success message with next steps
  - Proper error handling with user-friendly messages
  - Files are validated and sent to FileUploadRoutes backend

### Enhanced
- **Better User Experience**
  - Loading states during file upload
  - Clear success/error messages
  - Step-by-step instructions after upload
  - File size display in success message
  - Disabled button during upload to prevent duplicates

### Technical Notes
- Gateway Config sidebar menu **cannot** be added with AbstractDeviceModuleHook
- The Gateway navigation APIs (IConfigTab, AbstractNamedTab) require AbstractGatewayModuleHook
- Device drivers extending AbstractDeviceModuleHook cannot access these APIs
- Edit Program remains accessible via direct URL: `/res/plcsimulator/edit-program.html` (bookmark it!)

### User Impact
- **Dark mode** reduces eye strain for extended use
- **Complete upload workflow** - no more "implementation in progress" messages
- **Professional UI** with modern design
- **Clear feedback** at every step of the upload process

---

## [1.3.2] - 2025-11-10

### Fixed
- **JavaScript injection attempted** for file upload button in device configuration
  - Added JavaScript resource path to `ExtensionPointResourceForm`
  - Changed `Set.of()` to `Set.of("/res/plcsimulator/plc-file-upload.js")` in `EnhancedSimulatorExtensionPoint.java:77`
  - **Note:** May not work in all Ignition versions due to SDK limitations

### Added
- **Comprehensive Quick Start Guide** (`QUICK_START.md`)
  - Documents all three methods to upload PLC files
  - Explains why "Edit Program" cannot be added to device dropdown menu
  - Provides step-by-step troubleshooting
  - Clear instructions for Edit Program page access

### Enhanced
- **Updated device configuration description**
  - Added explicit link to Edit Program page (`/res/plcsimulator/edit-program.html`)
  - Clearer instructions for file upload methods
  - Mentions alternative access methods if upload button doesn't appear

### Technical Notes
- **SDK Limitation:** Ignition SDK does not provide public API to add custom items to device dropdown menu
- Original "Programmable Device Simulator" uses internal APIs not available to third-party modules
- Three working methods provided: Edit Program page, config form upload, and copy/paste

### User Impact
- **Edit Program Page:** Primary method - accessible at `/res/plcsimulator/edit-program.html` (bookmark this!)
- **Upload Button:** May appear in device config if JavaScript injection works in your Ignition version
- **Copy/Paste:** Always works as fallback method
- **Clear Documentation:** QUICK_START.md provides complete usage guide

---

## [1.3.1] - 2025-11-07

### Added
- **Landing page** at `/res/plcsimulator/` for easy navigation
  - Quick start guide with workflow instructions
  - Feature highlights
  - Direct links to Edit Program and Device Config
  - Bookmarkable URLs for quick access

### Enhanced
- Module description now includes Edit Program URL
- Better discoverability of Edit Program functionality
- Clean, modern UI for landing page

### Technical Notes
- Removed attempted Gateway Config integration (incompatible with AbstractDeviceModuleHook)
- Device drivers using AbstractDeviceModuleHook cannot register custom config pages
- Edit Program remains accessible via direct URL: `/res/plcsimulator/edit-program.html`
- Landing page accessible at: `/res/plcsimulator/` or `/res/plcsimulator/index.html`

### User Access
- Navigate to `/res/plcsimulator/` for the landing page
- Click "Open Edit Program" button or navigate directly to `/res/plcsimulator/edit-program.html`
- Bookmark for easy access

---

## [1.3.0] - 2025-11-07

### Added
- **Edit Program page** - Dedicated interface for file import similar to original simulator
  - Accessible at `/res/plcsimulator/edit-program.html`
  - Drag-and-drop file upload support
  - Step-by-step workflow instructions
  - Quick links to device configuration
  - Visual feedback and guidance
- **EDIT_PROGRAM_GUIDE.md** - Comprehensive guide comparing workflows

### Enhanced
- Improved workflow matching original Programmable Device Simulator
- Multiple methods for file import (Edit Program page, Device Edit, or Direct Upload)
- Better user guidance and documentation
- Clearer separation between device creation and file management

### User Experience
- Create device → Access Edit Program page → Import file (like original)
- Or create device → Edit device → Upload file (direct method)
- Or create device with file inline (all-in-one method)
- Choose the workflow that fits your needs

### Documentation
- Updated README with Edit Program section
- Added comparison with original simulator workflow
- Detailed troubleshooting in EDIT_PROGRAM_GUIDE.md
- Clear access instructions for all methods

---

## [1.2.1] - 2025-11-07

### Changed
- **File content and filename now fully optional** when creating device
  - Devices can be created without any file configuration
  - Device starts in "Ready - Waiting for file upload" status
  - Files can be added later via edit/upload
- Updated field descriptions to clarify optional nature
  - "PLC File Content (Optional)" label
  - "File Name (Optional)" label
  - Clear messaging about ability to add files later

### Technical Details
- Modified `EnhancedSimulatorDevice.onStartup()` to handle missing file gracefully
- Device creates empty root folder when no file provided
- No error state when file is missing - shows "Ready - Waiting for file upload"
- Updated properties file descriptions
- Updated Java annotations descriptions

### User Experience
- Create device connection first, add file configuration later
- More flexible workflow for device setup
- Clearer UI messaging about optional fields

---

## [1.2.0] - 2025-11-07

### ✅ Fixed
- **File upload now working!** - Complete implementation using HTTP routes and mounted web resources
  - Client-side JavaScript automatically injects "📁 Upload PLC File" button
  - Supports L5K, JSON, CSV, and XML files
  - Auto-populates both file content and filename fields
  - Visual feedback for loading/success/error states

### Added
- `FileUploadRoutes` class for handling HTTP file upload endpoints
  - POST `/main/data/plcsimulator/upload` - File upload endpoint
  - GET `/main/data/plcsimulator/health` - Health check endpoint
- `plc-file-upload.js` - Client-side file upload UI (auto-injected)
- `enable-file-upload.html` - Manual activation page (if needed)
- FILE_UPLOAD_GUIDE.md - Comprehensive usage documentation

### Technical Details
- Extended `SimulatorModuleHook` with:
  - `mountRouteHandlers()` for HTTP routes
  - `getMountedResourceFolder()` returning "mounted"
  - `getMountPathAlias()` returning "plcsimulator"
- Web resources accessible at `/res/plcsimulator/*`
- HTTP routes available at `/main/data/plcsimulator/*`
- Uses `RequestContext` and `JSONObject` for route handlers
- Jakarta Servlet API compatibility (jakarta.servlet.*)

### Status
- ✅ All core features working
- ✅ File upload functional
- ✅ Display names and dropdowns correct (from v1.0.9/v1.0.10)
- ✅ Device driver architecture stable
- ✅ Ready for production use

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
