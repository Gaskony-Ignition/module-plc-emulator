# v10.1.0 Execution Plan — Real-world L5K parsing

**Adopted 12/07/2026** (maintainer decision) after live testing v10.0.0 against
two genuine site exports showed the L5K parser silently corrupts real files:
it cannot bound AOI `PARAMETERS`/`LOCAL_TAGS` blocks against the `ROUTINE`
rung text that follows, so it drops every AOI-local tag (including all
arrays), leaks ladder/FBD instruction mnemonics into the tag tree as bogus
tags (43,013 "tags" reported for a ~5,000-tag program), collides same-named
AOI-locals in one flat namespace, and logs no warning at all. Charter
justification: a Logix export construct that fails to parse (§3 Maintenance
Policy). This supersedes the 10/07 "L5K best-effort" decision — the
maintainer's real site files are L5K, so L5K becomes a first-class parsed
format alongside L5X.

Evidence: `~/Downloads/plc-v10-artifacts/real-l5k/test-results/` (SUMMARY.txt,
raw REST/OPC output). Test files (PRIVATE, never committed):
`~/Downloads/plc-v10-artifacts/real-l5k/DemoWWTP-sample-b.L5K`
(5.4MB, ~5k tags expected) and `DemoPlant-PLC.L5K` (1.9MB).

## Ground rules

- Same operating model as V10_FIDELITY_PLAN.md: lead orchestrates; Opus for
  spec/design/deep review; Sonnet for implementation; failing-test-first;
  worktree isolation for parallel work; no version bump until release.
- **The private site files never enter the repo.** In-repo tests use small
  synthetic L5K fixtures replicating each construct. A local-only integration
  test reads the real files from an environment variable
  (`PLC_EMU_PRIVATE_L5K_DIR`) and is skipped when unset — CI-safe,
  maintainer-runnable.
- The parsed-tag JSON model and ADDRESSING.md remain the contract: the L5K
  parser feeds the same vendor-neutral model; NodeId emission stays in
  RockwellLogixPolicy. AOI/UDT expansion semantics must match the L5X path
  (same member sets, same ExternalAccess handling, same §5.3 EnableIn/Out
  behaviour).

## Phase 1 — Grammar spec (Opus): docs/plans/L5K-GRAMMAR.md

Dissect the two real files plus Rockwell's L5K/Import-Export reference (public
manual 1756-RM084) and produce a normative spec: block structure
(CONTROLLER/DATATYPE/MODULE/AOI definition/TAG/PROGRAM/ROUTINE/TASK/TREND...),
exactly how AOI definitions embed PARAMETERS, LOCAL_TAGS and ROUTINE rung text
and how each block terminates (END_* markers, nesting), tag-line grammar
(name, type, dimensions, radix, initial values incl. array initialisers and
structure literals), program-scoped TAG blocks, alias syntax, and the
scoping/expansion rules (AOI definitions are TYPES; instances expand per
ADDRESSING.md — decide and record how LOCAL_TAGS map to members vs hidden).
Every rule tagged FILE-CONFIRMED (with a line sample from the real files,
anonymised) or MANUAL-CONFIRMED (with the manual section). Ends with a
test-assertion checklist like ADDRESSING.md §5: expected counts and sample
canonical paths per construct for both private files (derived from reading the
files, since they are the acceptance ground truth) plus the synthetic fixture
matrix the in-repo tests need.

## Phase 2 — Parser implementation (Sonnet, likely 2 waves)

Rewrite/replace L5KParser per the spec: proper block-bounded parsing (no
regex-over-the-whole-file), AOI definitions parsed as types and instances
expanded through the same machinery as L5X, program scoping, arrays incl.
initial values, loud per-construct skip counters (WARN + summary + surfaced in
the upload response), and a hard failure when structural parsing cannot
proceed. In-repo synthetic fixture tests per construct; the env-var-gated
integration test asserts the Phase-1 checklist against the real files
(counts, zero rung-token tags, arrays present, AOI members, program paths).

## Phase 3 — Verify & release

Full build + fidelity + private-file integration green; upload both real
files on the shared test gateway and spot-verify trees via OPC (counts match
spec, canonical paths hold, no bogus tags); independent review of the parser
diff; version 10.1.0, CHANGELOG, release.sh, charter DoD note appended.

**Status: PLANNED — Phase 1 dispatched 12/07/2026.**
