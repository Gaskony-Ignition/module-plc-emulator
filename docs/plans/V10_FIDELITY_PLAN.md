# v10.0.0 Execution Plan — "Make it work, make it faithful"

**Adopted 09/07/2026** after the Definition-of-Done verification of v9.2.14
(items 2, 3, 7 FAILED) and the Logix-driver fidelity gap analysis. Maintainer
decisions (09/07/2026): one combined v10.0.0 release (no interim 9.3.0); leave
the portal's 9.2.14 alone until v10 ships; charter §2.2 amended to require
swap-compatible NodeId paths.

**Maintainer decisions 10/07/2026:** (a) one primary export format per vendor
— for Rockwell that is **L5X** (modern Studio 5000 default; the corpus is
L5X-heavy; the DoD crash is in shared code so the fix covers both formats
anyway). L5K becomes best-effort: it must fail loudly, nothing more (C7
descoped). (b) Long-term hope is **other vendors' export files** becoming
addable. No non-Rockwell work in v10, but it is an architecture constraint:
the parser boundary (ParserFactory → common parsed-tag JSON model) stays
pluggable, and Stage C fixes must keep Rockwell-specific addressing rules out
of vendor-neutral code (driver-matching NodeId emission would be per-vendor
policy, not hardcoded). A charter Won't-Do amendment for this awaits the
maintainer's explicit sign-off.

This plan is agent-executable: a lead session should be able to resume from
this document alone. Work is delegated to Sonnet agents (implementation,
tests, mechanical work) and Opus agents (design, address-scheme decisions,
deep review), per the standing delegation preference. The lead orchestrates,
reviews, and communicates; it does not implement.

## Source material (all preserved in `~/Downloads/plc-v10-artifacts/`)

| Artefact | Where | What |
| -------- | ----- | ---- |
| DoD evidence (phases 1+2) | `plc-dod/item*.txt`, `pw-*.png` | Per-item verdicts + repro evidence for every defect below |
| Gateway automation tooling | `plc-dod/login.sh`, `csrf-finding.txt`, `pw-captured-posts.json`, `deploy-webdev.py`, `wd_opctool.py`, `create-device6.mjs` | Reusable: 8.3 session login; **config-API POSTs need `x-csrf-token` header**; Playwright device-creation; WebDev Jython OPC read/write tool |
| Fidelity gap matrix | `plc-fidelity/gap-analysis.md` | Construct-by-construct divergence vs Ignition Logix driver, with file:line refs (top findings spot-verified by lead) |
| Real-world corpus (9 files) | `plc-fidelity/corpus/` + `manifest.md` | 5 controller families; licences commit-pinned. **The 1768/PIDE file is UNLICENSED — local testing only, never vendor/commit/redistribute.** Other 8 are MIT/Apache — vendorable with attribution |
| Signed 9.2.14 modl | `plc-dod/LogixPLCEmulator-9.2.14.modl` | Baseline release artefact |

## Ground rules

- Branch: create `v10-fidelity` off `main`; all stages land there; merge to
  main only at Stage D release time.
- Harness-first: every fix must land against a failing-then-passing test.
- Parallel agents use worktree isolation; **never run Gradle concurrently in
  the same directory** (daemon lock). Lead builds/tests once per wave.
- Load the module skills before the matching action (committing-changes,
  writing-tests, building-modules, security-checking, managing-versions,
  creating-releases).
- Signed release only, via `modules/release.sh`; version bumped once, at
  Stage D, via `syncVersion`.
- Ground truth for driver addressing = Inductive Automation docs + published
  driver behaviour (no hardware / Logix Echo available). Mark each fidelity
  assertion with confidence (DOC-CONFIRMED vs INFERRED). If IA bench
  resources become available, upgrade INFERRED items to a real driver diff.

## Stage A — Test harness (blocks everything; 1 Sonnet agent)

1. Vendor the 8 licence-clean corpus files into
   `gateway/src/test/resources/corpus/` with an `ATTRIBUTION.md` (source URL,
   commit, licence per file). Exclude the unlicensed 1768 file.
2. New integration suite `AddressSpaceBuilderIT` (or extend
   `AddressSpaceBuilderTest`): parse each corpus file end-to-end
   (ParserFactory → buildAddressSpace against a mock/stub UaNodeContext) and
   assert: no exception, expected tag counts from the manifest, spot NodeId
   paths per file.
3. Add a JUnit `@Tag("fidelity")` suite encoding the TARGET (driver-matching)
   path assertions from the gap matrix — excluded from the default `test`
   task via Gradle config, enabled per-fix as Stage C lands. Document the
   enable mechanism in the suite's Javadoc.
4. Crash repro test first: `simple.l5x` and the real-world L5X must build
   without exception (fails until B1 lands — this is the wave's gate test).

## Stage B — Defect fixes (charter §2 failures; Sonnet agents, B1 first)

| ID | Defect (evidence file) | Fix outline |
| -- | ---------------------- | ----------- |
| B1 **CRITICAL** | All L5X crashes address-space build: `AddressSpaceBuilder.getInitialValue()` (~L447-472) strict `getAsInt()` on non-numeric `initial_value` (`"{structure}"` from `L5XParser.extractValue()` ~L532, or `""`); no per-tag guard (`item2-addressspace-error.txt`) | Lenient typed parsing with type-appropriate defaults on failure; stop emitting `"{structure}"` sentinel into `initial_value` (parser-side); wrap per-tag node creation so one bad tag logs a warning instead of aborting the build |
| B2 HIGH | JSON parser yields zero OPC tags despite parsing OK (`item2-formats.txt`) | Trace JsonParser output shape vs what `buildAddressSpace` expects (`global_tags`/`programs` keys); align + integration test |
| B3 HIGH | Simulation engine never updates node values — "0 initial tags" registry/engine wiring gap; device `simulation.enabled` defaults off and is labelled `[EXPERIMENTAL]` (`item3-simulation-FAIL.txt`) | Fix engine↔address-space registration so simulated tags get initial registration and per-tick writes; decide default + drop `[EXPERIMENTAL]` once verified; end-to-end test: assign RAMP, two timed reads differ |
| B4 MEDIUM | Upload returns HTTP 200 `{"success":true}` even when parse/build fails | Propagate parse/build result to the response (4xx/5xx + error body); test |
| B5 HIGH | Version manager inert: `saveVersion` never called on REST upload path; `getVersions`/`restoreVersion` dead code; no REST route/UI; on restart device reloads stale/arbitrary file; uploads accumulate unpruned (`item7-versioning-FAIL.txt`) | Call `saveVersion` on successful upload; add REST routes (list versions, revert) + minimal web-UI surface; fix most-recent-file selection on restart; prune per retention policy (5) |
| B6 LOW-MED | NUL-byte 11MB file misread as "File content is empty" (`item6-validation.txt`) | Read-then-validate structurally; correct error message |
| B7 DOCS | `API_REFERENCE.md`: Basic auth documented but non-functional (real auth = session cookie + `x-csrf-token` for config POSTs); phantom routes (`/status`, `/devices/{name}`); size limit is 50MB not 10MB; module `CLAUDE.md` says upload 60/hr, actual 100/hr (60 is the write limiter); `/upload` implies it creates devices (device must pre-exist via Config → OPC UA → Device Connections) | Rewrite the auth section honestly; correct every route/limit; document device-creation prerequisite prominently |
| B8 TEST-ASSET | `malicious-xxe.l5x` (235B) never reaches the XML parser (pre-parse ~10KB size gate) — XXE test was toothless | Replace with the working ≥10KB fixture from the DoD run (`plc-dod/xxe-big.l5x`); REST-level test asserts DOCTYPE rejection, no entity resolution |

## Stage C — Fidelity fixes (gap matrix; Opus designs C0, Sonnet implements)

**C0 (Opus, first):** distil `gap-analysis.md` + IA Logix-driver addressing
docs into a normative ADDRESSING.md spec: exact NodeId grammar for every
construct (controller/program scope, UDT/AOI members, arrays incl. multi-dim,
BOOL packing, predefined members, I/O module tags), alias strategy, and the
migration note for pre-v10 bindings. Every rule tagged DOC-CONFIRMED or
INFERRED. This spec is what the `@Tag("fidelity")` tests assert.

Then, in breakage-likelihood order (each: enable its fidelity tests → fix →
green):

| ID | Gap (verified refs) | Fix |
| -- | ------------------- | --- |
| C1 | Program-scoped tags emit `Programs.<Prog>.Tag`, real driver uses `Program:<Prog>.Tag`; no short alias for program tags (`AddressSpaceBuilder.java:135`, alias gate at `:197` requires `Controller:Global`) | Emit driver-format paths + aliases for program scope |
| C2 | Array-of-UDT/predefined collapses to one instance (`udt_members` branch returns at `:232` before array branch `:237`); UDT members with `dimensions` collapse to scalar (`addUdtMember` ignores dims); multi-dim keeps `dims[0]` only, `[i]` not `[i,j]` (`:241-244`) | Full expansion: `Tag[i].Member`, `[i,j]`, member arrays |
| C3 | BOOL arrays emit per-element `Tag[i]` nodes (`:237-253`) — form the real driver rejects; driver DWORD-packs (`Tag[0].0…`) | Model per driver packing |
| C4 | Predefined member sets hand-approximated (`RockwellBuiltInTypes.java`): TIMER has phantom `.ER` (:52-59); PID 14/~46 members; CONTROL missing `.UL/.IN/.FD`; AXIS/CAM/PHASE abbreviated (:99-343) | Correct member tables from Rockwell docs (research task) |
| C5 | `<Modules>` never parsed → no `Local:1:I.Data` I/O tags; `ExternalAccess=None` tags exposed writable (driver hides them) (`L5XParser.java:190-207`) | Parse Modules section → I/O tag nodes; honour ExternalAccess |
| C6 | v32+ unsigned atomics (USINT/UINT/UDINT/ULINT) and DT/LDT/LTIME degrade to String | Proper type mapping |
| C8 | **(found during Stage A, 10/07)** Initial values from real exports are silently discarded: Studio 5000 emits self-closing `<DataValue ... Value="42"/>` and `L5XParser.extractValue()` only reads `.getTextContent()` (always empty), so every tag starts at the type default regardless of the export | Read the `Value` attribute (fall back to text content); decide per ADDRESSING.md what structured/array member initial values should be; corpus-based test asserting a known export value appears on the node |
| C7 | L5K parser only ever validated against synthetic grammar; real `.L5K` failing the regex silently degrades to a single `L5K_ParseError` demo tag (`L5KParser.java:125,389`) | **DESCOPED 10/07/2026** (maintainer: one primary format per vendor). L5X is the primary, fully-verified Rockwell format; L5K is best-effort — the only required change is failing loudly (clear error to the user) instead of the silent demo-tag fallback. No equivalence testing, no L5K-specific hardening |

Corpus gaps (no public GuardLogix/motion/produced-consumed/SoftLogix exports
found): synthesise targeted L5X fragments for those constructs, marked
SYNTHETIC in the manifest; ask maintainer whether IA bench exports can fill
them properly.

## Stage D — Verification & release

1. Full build + test + fidelity suite green; `./gradlew build` clean.
2. **DoD re-run** on a fresh throwaway gateway (reuse `plc-dod` tooling:
   scripted install recipe from the memory note, `login.sh`, Playwright
   device creation with the `x-csrf-token` finding) — all 7 charter §2 items
   must PASS, including real-world L5X → simulation → hot-reload → writes →
   version revert.
3. Security review (security-checking skill) over the new REST surface
   (version routes) and parser changes.
4. Independent Opus review of the full v10 diff before release.
5. Version → 10.0.0 (managing-versions skill; `syncVersion`); update
   `PLAN.md`, `KNOWN_ISSUES.md`, `CHANGELOG.md`, `README.md` (breaking-change
   note: NodeId scheme now matches the real driver; pre-v10 bindings to old
   paths must be re-pointed).
6. Release via `modules/release.sh ignition-module-plc-emulator` (signed,
   private repo + portal). Record DoD verification result + date in the
   charter.

## Defects deliberately deferred (post-v10, charter-gated)

- Process note only: malformed WebDev `resource.json` (`timestamp: 1`) faults
  the entire gateway — platform behaviour, not ours; captured in the workspace
  memory notes.

**Status: PLANNED — execution scheduled to resume 10/07/2026 ~01:00 ACST.**
