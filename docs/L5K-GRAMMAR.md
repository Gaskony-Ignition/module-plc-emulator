# L5K-GRAMMAR.md — Normative L5K Parse Grammar for the Logix PLC Emulator

This is the single normative document the `L5KParser` and its `@Tag("l5k")` tests
are written against. It defines the exact block grammar, tag-line grammar, and
semantic mapping the parser must implement so that a Studio 5000 **`.L5K`** export
produces the same vendor-neutral parsed-tag model an equivalent `.L5X` export does,
feeding `ADDRESSING.md` unchanged.

**Companion:** `ADDRESSING.md` (NodeId/expansion contract — this document stops at the
parsed-tag model; NodeId emission stays in `RockwellLogixPolicy`).

Each rule is tagged **FILE-CONFIRMED** (dissected from a real site export; snippets are
anonymised — no site-identifying tag names) or **MANUAL-CONFIRMED** (Rockwell
*Logix5000 Controllers Import/Export Reference Manual*, publication **1756-RM084K-EN-P**,
the fetched revision; the current series is 1756-RM084). Where the two disagree, the file
wins for parsing behaviour and the discrepancy is flagged.

**Primary sources dissected**
- Real file **A** = `DemoWWTP-sample-b.L5K` (5.44 MB, 81 215 lines, CRLF, tab-indented).
- Real file **C** = `DemoPlant-PLC.L5K` (1.87 MB, 31 963 lines, CRLF, tab-indented).
- 1756-RM084K-EN-P §§3–4 (block structure, tag/datatype/routine grammar). The 2005
  revision predates Add-On Instructions (v16, 2007); the AOI block grammar below is
  FILE-CONFIRMED and cross-checked against the newer 1756-RM084 TOC entry
  "L5K ADD_ON_INSTRUCTION_DEFINITION structure".

> **The private files never enter the repo.** In-repo tests use synthetic fixtures
> (§6.2). The real-file expectations (§6.1) are asserted only by the env-gated
> integration test (`PLC_EMU_PRIVATE_L5K_DIR`).

---

## 0. Why v10.0.0 corrupts these files — the one-sentence root cause

A ladder rung and a tag declaration are **lexically identical** at the token level:

| L5K line (MANUAL-CONFIRMED §4-2 / §3-12) | Shape |
| --- | --- |
| tag: `SomeTag : TIMER (RADIX := Decimal) := [0,0,0];` | `<name> : <type> ( … ) := … ;` |
| rung: `N: XIC(Sim)OTL(Sts);` | `<name> : <text> ( … ) ;` |

The rung type letter `N` sits where a tag name sits, the colon is the same colon, and the
first ladder instruction `XIC` sits where the data type sits. **The only thing that
distinguishes them is the enclosing block.** v10.0.0's `L5KParser` parses tag lines by
regex over the whole file with no block stack (`parseTagsWithUDTs`,
`L5KParser.java:246–304`), so its "controller tag" pattern
(`CONTROLLER_TAG_PATTERN`, `:58–59`) matches every AOI/program rung `N: XIC(…)` and emits
a pseudo-tag named `N` with `data_type` = the first mnemonic it sees (`XIC`, `XIO`, `COP`,
`EQ`, `MESSAGE`, `OTU`, `LBL`, …). That is the 43 013-"tag" corruption in `SUMMARY.txt`.

**The fix is architectural, not a regex tweak: parse tag declarations ONLY inside
whitelisted tag-bearing blocks, tracked by an explicit block stack.** §1 defines that
stack; §1.4 is the normative bounding rule.

---

## 1. Block structure

### 1.1 File shell — MANUAL-CONFIRMED (§3, "Create a Complete Import/Export File")

An L5K is a flat text file, **UTF-8, CRLF line endings, hard-TAB indentation** (both real
files). It opens with a short preamble and one top-level `CONTROLLER` block that contains
everything else:

```
IE_VER := <n>;                         (* preamble, not always present *)
CONTROLLER <Name> ( <attrs, comma-separated, may span many lines, closed by ')' > )
    <DATATYPE …>                       (* zero or more *)
    <MODULE …>                         (* zero or more *)
    <ADD_ON_INSTRUCTION_DEFINITION …>  (* zero or more *)
    TAG … END_TAG                      (* exactly one controller-scope tag block *)
    <PROGRAM …>                        (* zero or more *)
    <TASK …>                           (* zero or more *)
    CONFIG <Object>( … ) END_CONFIG    (* zero or more, single-line *)
END_CONTROLLER
```

FILE-CONFIRMED nesting map (file **A**, tab-depth in the left margin):

```
depth0  CONTROLLER AV_… (              line 14      (attrs wrap to line 33)
depth1    DATATYPE …/END_DATATYPE      27 blocks    (lines 34–751)
depth1    MODULE …/END_MODULE          67 blocks    (lines 752–4234)
depth1    ADD_ON_INSTRUCTION_DEFINITION … /END_…    137 blocks (4235–61070)
depth1    TAG / END_TAG                1 block       (61072–80654)  <- all controller tags
depth1    PROGRAM …/END_PROGRAM        3 blocks      (80656–81178)
depth1    TASK …/END_TASK              1 block       (81180–81189)
depth0    CONFIG(…) END_CONFIG         7 lines       (81191–81213)  <- see 1.2 note
depth0  END_CONTROLLER                 line 81214
```

### 1.2 Begin/end markers and termination rules

| Block | Begin token (line-leading, after tabs) | End token | Nesting | Source |
| --- | --- | --- | --- | --- |
| Controller | `CONTROLLER <name> (` | `END_CONTROLLER` | root; contains all | MANUAL §3 / FILE A,C |
| Data type (UDT) | `DATATYPE <name> [(attrs)]` | `END_DATATYPE` | in CONTROLLER | MANUAL §3 "Define a Data Type" |
| Module | `MODULE <name> (attrs)` | `END_MODULE` | in CONTROLLER; may contain `CONNECTION … END_CONNECTION` | MANUAL §3 "Define a Module" |
| AOI definition | `ADD_ON_INSTRUCTION_DEFINITION <name> [(attrs)]` | `END_ADD_ON_INSTRUCTION_DEFINITION` | in CONTROLLER; contains PARAMETERS, LOCAL_TAGS, ROUTINE(s) | FILE A,C (newer 1756-RM084 §) |
| AOI parameters | `PARAMETERS` | `END_PARAMETERS` | in AOI def | FILE A,C |
| AOI local tags | `LOCAL_TAGS` | `END_LOCAL_TAGS` | in AOI def | FILE A,C |
| Tag block | `TAG` (bare) or `TAG` line | `END_TAG` | in CONTROLLER (controller scope) or in PROGRAM (program scope) | MANUAL §3-12 |
| Program | `PROGRAM <name> [(attrs)]` | `END_PROGRAM` | in CONTROLLER; contains TAG, ROUTINE(s), CHILD_PROGRAMS | MANUAL §3 "Define a Program" |
| Child programs | `CHILD_PROGRAMS` | `END_CHILD_PROGRAMS` | in PROGRAM (may be empty) | FILE C |
| Task | `TASK <name> (attrs)` | `END_TASK` | in CONTROLLER; body = program-name refs `Name;` | MANUAL §3 "Define a Task" |
| Config object | `CONFIG <Obj>( … ) END_CONFIG` | same line | single-line; in CONTROLLER | MANUAL §3 "Define Controller Objects" |
| Ladder routine | `ROUTINE <name> [(attrs)]` | `END_ROUTINE` | in PROGRAM or AOI def | MANUAL §4 |
| Structured-text routine | `ST_ROUTINE <name>` | `END_ST_ROUTINE` | in PROGRAM or AOI def | FILE C (11×) |
| Function-block routine | `FBD_ROUTINE <name> (attrs)` | `END_FBD_ROUTINE` | in PROGRAM or AOI def; contains `SHEET … END_SHEET` | FILE C (36×) |
| (SFC routine) | `SFC_ROUTINE <name>` | `END_SFC_ROUTINE` | in PROGRAM/AOI | MANUAL §5 (not in A,C — handle defensively) |

Rules:
1. **Every block is closed by its own `END_*` keyword.** Blocks nest; a child's `END_*`
   appears before the parent's. Termination is by keyword, **not** by indentation
   (indentation is cosmetic and must not be relied on for correctness — see §1.5).
   FILE-CONFIRMED.
2. **`CONFIG` is self-closing on one line** and, in file A, is emitted at tab-depth 0 even
   though it is logically inside `CONTROLLER` (it appears between `END_TASK` and
   `END_CONTROLLER`). The parser must treat any `CONFIG … END_CONFIG` seen after
   `CONTROLLER` and before `END_CONTROLLER` as controller-level and **carrying no tags**.
   FILE-CONFIRMED (A, lines 81191–81213). *(This is why a depth-based parser breaks — see
   §1.5.)*
3. A `CONTROLLER` attribute list, a `PROGRAM`/`TASK`/`MODULE`/`DATATYPE` attribute list,
   and every tag declaration's attribute list may **wrap across many physical lines**,
   closed by the matching `)`. Do not treat wrapped attribute lines as new statements.
4. Blocks not present in A/C but defined by the manual (`TREND`, `QUICK_WATCH_LIST`,
   `WATCH_TAG`, `SFC_ROUTINE`, `ENCODED_DATA`, `DEPENDENCIES`) must be **recognised as
   opaque and skipped** — they carry no controller/program tags the emulator emits. Treat
   any unknown `<KEYWORD> … END_<KEYWORD>` pair the same way (see §1.4 rule W).

### 1.3 The AOI definition — full structure (FILE-CONFIRMED, file A first AOI, anonymised)

```
ADD_ON_INSTRUCTION_DEFINITION <AoiName> (Description := "…", Revision := "1.0",
                                          ExecutePrescan := No, … SoftwareRevision := "v37.00")
    PARAMETERS
        EnableIn  : BOOL (Description := "…", Usage := Input,  RADIX := Decimal,
                          Required := No, Visible := No, ExternalAccess := Read Only);
        EnableOut : BOOL (Description := "…", Usage := Output, RADIX := Decimal,
                          Required := No, Visible := No, ExternalAccess := Read Only);
        DI        : BOOL (… Usage := Input,  Required := Yes, Visible := Yes, DefaultData := 0);
        Sts       : BOOL (… Usage := Output, Visible := Yes, ExternalAccess := Read/Write, DefaultData := 0);
        On_Time   : REAL (… Usage := Output, RADIX := Float, DefaultData := 1.00000000e+000);
        …
    END_PARAMETERS

    LOCAL_TAGS
        Off_T : TIMER (ExternalAccess := Read/Write, DefaultData := "[0,0,0]");
        On_T  : TIMER (ExternalAccess := Read/Write, DefaultData := "[0,0,0]");
    END_LOCAL_TAGS

    ROUTINE Logic
        RC: "…rung comment, may span quoted lines…";
        N: XIO(Sim)[XIO(NC) XIC(DI) ,XIC(NC) XIO(DI) ]OTL(Sts);
        N: XIC(Sts)MUL(On_Time,1000.0,On_T.PRE)TON(On_T,?,?)XIC(On_T.DN)OTE(HighD);
        …
    END_ROUTINE
END_ADD_ON_INSTRUCTION_DEFINITION
```

Key structural facts:
- The **only tag-bearing sub-blocks are `PARAMETERS` and `LOCAL_TAGS`.** Everything between
  `END_LOCAL_TAGS` and `END_ADD_ON_INSTRUCTION_DEFINITION` is one or more `ROUTINE`/
  `ST_ROUTINE`/`FBD_ROUTINE` blocks whose bodies are logic, never tags.
- An AOI may have **PARAMETERS but no LOCAL_TAGS**, or multiple ROUTINEs (`Logic`,
  `Prescan`, `EnableInFalse`, …). Order in A/C is always PARAMETERS → LOCAL_TAGS →
  ROUTINE(s), but the parser must key off the explicit begin/end keywords, not order.

### 1.4 THE bounding rule (normative — this is the defect fix)

Maintain an explicit **block stack**. The parser reads a line as a **tag/member
declaration only when the innermost open block is one of the four whitelisted
tag-bearing contexts**; in every other context the line is opaque and skipped.

```
WHITELIST (a line may start a tag/member declaration iff top-of-stack ∈):
   CTRL_TAG      — a TAG block whose parent is CONTROLLER      → controller-scope tag
   PROG_TAG      — a TAG block whose parent is a PROGRAM       → program-scope tag (that program)
   AOI_PARAM     — a PARAMETERS block (parent = AOI def)       → AOI type parameter
   AOI_LOCAL     — a LOCAL_TAGS block (parent = AOI def)       → AOI type local tag
   DATATYPE      — a DATATYPE block                            → UDT member
```

Normative rules:
- **R1 — Whitelist, not blacklist.** Never infer a tag from context outside the whitelist.
  In particular, **delete v10's "controller tags outside a TAG block" path**
  (`L5KParser.java:281–288`): in both real files there are **zero** controller tags
  outside the `TAG` block, and that path only ever matched rung/parameter lines.
  FILE-CONFIRMED (A: 1224 controller tags, 100 % inside the single TAG block; C: 545,
  100 % inside the TAG block).
- **R2 — Push on any recognised begin keyword, pop on its matching `END_*`.** This
  includes ROUTINE/ST_ROUTINE/FBD_ROUTINE/SHEET/CONNECTION/CHILD_PROGRAMS. While any of
  those is top-of-stack, tag parsing is off. Rung lines `N:`/`RC:` are therefore never in
  a tag context and can never leak. This alone eliminates the SUMMARY corruption.
- **R3 — A tag/member declaration is one logical statement terminated by `;`** at bracket
  depth 0 and outside string quotes. Statements **span multiple physical lines** (§2.5);
  accumulate until the terminating `;`. Continuation lines are deeper-indented but the
  parser must terminate on `;`, not on indentation.
- **R4 — Statement terminator scanning must respect quotes and brackets.** `;` and `:`
  can appear inside `"…"` descriptions and inside `'…'` string literals (rare: file A has
  5 descriptions containing `;`). Track `"`, `'`, `[`, `]` state when locating the
  terminator. FILE-CONFIRMED.
- **R5 — `TAG` scope is decided by the block stack parent**, not by a running
  `inControllerScope` flag: `TAG` under `CONTROLLER` = controller scope; `TAG` under
  `PROGRAM <P>` = program scope for `<P>`. FILE-CONFIRMED (a program's `TAG`/`END_TAG` is
  at depth 2 inside `PROGRAM`).
- **W — Unknown block keywords are opaque.** If a line's leading token is
  `<UPPER_TOKEN>` followed (this or a later line) by a matching `END_<UPPER_TOKEN>` and it
  is not whitelisted, skip its contents entirely. Prevents future Studio 5000 constructs
  from leaking.

### 1.5 Do NOT parse by indentation — MANUAL/FILE-CONFIRMED

Indentation is a reliable *hint* but not the contract:
- File A emits `CONFIG` blocks at tab-depth 0 while they are logically inside
  `CONTROLLER` (§1.2 rule 2). A depth-based "inside controller" test misfires here.
- Multi-line initialisers wrap to deeper, irregular indentation (tabs **and** spaces).
- The manual specifies keyword-delimited blocks, never indentation.

Use indentation only as a cheap pre-filter for "is this line a statement start" (a
declaration start has a name character immediately after the block's child-indent;
continuation lines are strictly deeper). The **authority is the `;` terminator + block
stack**, not the tab count.

---

## 2. Tag-line grammar

### 2.1 Non-alias tag declaration — MANUAL-CONFIRMED (§3-12)

```
<tag_name> : <type>[<array_spec>] [(<attributes>)] [:= <initial_value>] [, <tag_force_data>] ;
```

- **Mandatory space around the colon** (manual, verbatim): *"There must be a space between
  the tag name and the colon and another space between that same colon and the type name.
  This is because type names can contain a colon"* — e.g. module reference types
  `AB:1756_DI:C:1`. The parser must therefore split on ` : ` (space-colon-space), and the
  **type token may itself contain colons** (do not stop the type at the first `:`).
- `tag_name`: `[A-Za-z_][A-Za-z0-9_]*` (≤40 chars).
- `type`: atomic (`BOOL SINT INT DINT LINT REAL LREAL`), `STRING`/string-family, predefined
  (`TIMER COUNTER CONTROL MESSAGE PID AXIS_* CAM MOTION_GROUP …`), a function-block type
  (`FBD_TIMER FBD_COMPARE FBD_ONESHOT …`), a UDT name, or an AOI name. MANUAL §3-12 lists
  the predefined/FB/SFC families verbatim.
- FILE-CONFIRMED forms (file A controller TAG block, anonymised):
  ```
  SomeBool  : BOOL (RADIX := Decimal) := 0;
  SomeTimer : TIMER  := [4165810,10000,10000];         (* struct init, no attrs *)
  SomeUdt   : <UdtName> (Description := "…") := [3,5.0e+003,…,[0,2000,0],…];
  SomeArr   : DINT[2] (COMMENT[0].0 := "…");           (* array + member comment attr *)
  ```

### 2.2 Alias tag declaration — MANUAL-CONFIRMED (§3-13)

```
<tag_name> OF <alias_target> [(<attributes>)] ;
```

- The keyword is a **space-delimited `OF`**; `alias_target` is a base tag with an optional
  specifier: `.bit`, `[element]`, or `.member` (manual). Real targets include module I/O
  channels, e.g. `<Name> OF EXP_IO:10:I.Ch07.Data (RADIX := Float, DataExchangeId := {…});`
  FILE-CONFIRMED (A: **572** alias tags in the controller block; C: **0**).
- Manual example (verbatim): `overflow OF bits.MyBit0 (RADIX := Binary);`
- **v10 drops all alias tags** — neither `CONTROLLER_TAG_PATTERN` nor
  `TAG_DEFINITION_PATTERN` matches `name OF target` (`:56–59`). See §4 defect D6.
- Parsed-model mapping: an alias is a tag whose value/type is that of its resolved target.
  Minimum viable behaviour (v10.1): emit the alias as a leaf node named `<tag_name>`,
  data type resolved from the target's declared type where determinable (module I/O
  channel → the channel's atomic type), else opaque leaf; record `alias_for` = target
  string. Full alias→target value mirroring is a Phase-2 decision, not a grammar rule.

### 2.3 Array specification — MANUAL-CONFIRMED (§3-14)

```
[ <element> [, <element> [, <element> ] ] ]      up to 3 dims, comma-separated, no inner space
```

- Attaches directly to the type with **no whitespace** (`DINT[2]`, `Recipe[40]`,
  `STRING[5]`). FILE-CONFIRMED.
- Feeds `ADDRESSING.md` §3.4/§3.5 (element expansion `Tag[i]`, `Tag[i,j]`) unchanged. The
  parser records `dimensions` verbatim (e.g. `"2"`, `"3,5"`); it does **not** itself expand
  elements (that is `AddressSpaceBuilder`/policy). It MUST also record **member-array
  dimensions** (§2.6) — v10 drops those.

### 2.4 Attributes and radix — FILE/MANUAL-CONFIRMED

Attribute list is `( key := value , … )`, may wrap lines. Attributes seen on real tag
lines: `Description`, `RADIX` (`Decimal | Hex | Octal | Binary | Float | Exponential |
ASCII | DateTime`), `Constant`, `ExternalAccess` (**present in L5K — answer to the task's
"check!":** `Read/Write | Read Only | None`), `DataExchangeId := {GUID}`, and
member-comment pseudo-attributes `COMMENT.<bit> := "…"`, `COMMENT[<i>] := "…"`,
`COMMENT.<member> := "…"` (MANUAL §3-28). Numeric literals use radix prefixes
`16#hex`, `2#binary`, `8#octal`, with `_` group separators (`16#ffff_ffff_ffff_ffff`,
`2#0000_0000_0001_1111`). FILE-CONFIRMED (A).

**`ExternalAccess` is authoritative and MUST be captured per tag/parameter/local** — it
drives visibility exactly as in the L5X path (`ADDRESSING.md` §3.12). `Constant := 1` →
read-only (`ADDRESSING.md` §3.12). There is **no `OpcUaAccess` attribute in these L5K
files** (it is an L5X-8.3 addition); nothing to ignore here.

### 2.5 Initial values — MANUAL-CONFIRMED (§3-27) + FILE-CONFIRMED

*"The initial_value format follows the C-language initialization syntax, except that you
use **square brackets instead of curly brackets**."* (Manual, verbatim.) **L5K aggregate
initialisers use `[...]`, not `{...}`.**

| Tag shape | Initial-value form | Source |
| --- | --- | --- |
| atomic scalar | `:= 0` / `:= 1.00000000e+000` / `:= 16#00ff` | MANUAL §3-27 |
| structure (n members) | `:= [v1, v2, v3]` | MANUAL §3-27 |
| nested structure | `:= [v1, [v2, v3], v4]` | MANUAL §3-27 |
| structure w/ nested array | `:= [v1, [a1, a2], v3]` | MANUAL §3-27 |
| predefined `TIMER` | `:= [ctl_word, PRE, ACC]` e.g. `[0,5000,0]` | FILE A |
| STRING scalar | `:= [<len>, 'text$00…$00']` (padded to DATA size, single quotes) | MANUAL §3-27 |
| STRING array `[n]` | `:= [[len,'…'],[len,'…'], …]` (one bracketed string per element) | FILE A |
| big UDT/AOI instance | deeply nested `[…]`, substructures as nested `[…]`, embedded strings as `[len,'…']` | FILE C (`P_DIn`, `P_ValveMO`) |

- **String escapes:** `$NN` = hex byte (`$00` NUL padding), `$N` = newline, `$Q` = quote,
  `$'` etc. String bodies are in **single quotes**; descriptions in **double quotes**.
  FILE-CONFIRMED.
- **Multi-line initialisers are the norm** for UDT/AOI instances and long strings: file C's
  `P_DIn`/`P_ValveMO` instance initialisers span ~10–20 physical lines each, wrapping at
  commas inside the `[...]`. Statement ends at the `;` **after** the outermost `]`. This is
  the single most important reason the parser must be statement-oriented (R3), not
  line-oriented.
- **Scope note (inherited from `ADDRESSING.md` §3.10a):** deriving per-member/per-element
  values from these initialisers is deferred post-v10 for L5X and the same boundary applies
  to L5K. The **grammar** requires the parser to *tokenise past* the whole initialiser
  correctly (so it doesn't mis-split the statement); it need not *interpret* every value in
  v10.1. Top-level array element values MAY be surfaced (parity with L5X FIX-15) but that is
  optional for this phase.

### 2.6 DATATYPE (UDT) member grammar — MANUAL-CONFIRMED (§3-2)

**Member order is `TYPE NAME`, NOT `NAME : TYPE`** (this is the opposite of a tag line and
of an AOI parameter line — the single most error-prone asymmetry):

```
<TypeName> <MemberName>[<array_spec>] [(Attributes)];          (* non-bit member *)
BIT <BitName> <HostMemberName> : <BitPosition> [(Attributes)]; (* single-bit member *)
```

- BOOLs are not stored standalone; a bit is a `BIT` member overlaying a hidden host byte.
  The host is a normal member flagged `Hidden := 1`, conventionally named
  `ZZZZZZZZZZ<Type><n>`. FILE-CONFIRMED + MANUAL example (`MyBits`).
- Members may be predefined (`TIMER OFL_FILTER (…);`), nested UDTs, or arrays
  (`DINT Data[10] (…);`). Member arrays MUST record their dimension (§3.7 of ADDRESSING).
- Drop `ZZZZ*` hidden host members from the browse model but keep them for bit backing
  (they are not browsable; the `BIT` children are). FILE/ADDRESSING-consistent.
- **v10 uses `NAME : TYPE` for DATATYPE members (`:36–37`), so it silently drops every
  non-bit member** (DINT/REAL/TIMER/nested-UDT) and keeps only `BIT` members. See §4 D3.
- String-family UDTs: `DATATYPE <Name> (FamilyType := StringFamily) … END_DATATYPE` with
  a `SINT DATA[n]` + `DINT LEN` shape; treat as a STRING-type per `ADDRESSING.md` §3.10.
  FILE-CONFIRMED (A: `TeSysDOL_20/32/64/7`).

### 2.7 AOI PARAMETERS / LOCAL_TAGS member grammar — FILE-CONFIRMED

Unlike DATATYPE members, AOI parameters and local tags use the **tag-line order
`NAME : TYPE`** (same as §2.1, minus the `:=` initial — initial is `DefaultData := …`):

```
<name> : <type>[<array_spec>] (Usage := <Input|Output|InOut>, RADIX := …, Required := …,
                               Visible := <Yes|No>, ExternalAccess := <Read/Write|Read Only|None>,
                               DefaultData := <value or "[…]">);
```

- `PARAMETERS` members carry `Usage` (Input/Output/InOut); `LOCAL_TAGS` members do not
  (they are always locals). Both carry `ExternalAccess`. FILE-CONFIRMED.
- Array locals exist and MUST keep their dimension, e.g.
  `FLOW_HOURS : DINT[30] (RADIX := Decimal, ExternalAccess := None, DefaultData := "[0,…,0]");`
  FILE-CONFIRMED (A). v10 drops the `[30]` (§4 D4) so every AOI-local array collapses.

### 2.8 Rung / routine body grammar (what must be SKIPPED) — MANUAL-CONFIRMED (§4)

```
<RungType> : <RungNeutralText> ;      RungType ∈ { N I D IR rR R rI rN e er }
RC: "<rung comment, may be several concatenated quoted segments>" ;
```

- Manual example, verbatim: `N: XIC(input1)XIC(input2)OTE(output1)OTE(output2);`. Branches
  are `[ …, … ]`. FILE-CONFIRMED (A: 6 854 `N:` rungs, 239 `RC:` comments).
- `ST_ROUTINE` bodies are Structured Text lines (`'` line comments, `x := y;` assignments
  referencing module paths). `FBD_ROUTINE` bodies are `SHEET`/`IREF`/`OREF`/`WIRE`/block
  records. **None of these are ever tags** — the block stack (R2) keeps them out.

---

## 3. Semantics → parsed-tag model mapping

The L5K parser emits the **same vendor-neutral model** the L5X parser does (JSON:
`global_tags[]`, `programs[].tags[]`, `udts[]`, and — new for parity — `aois[]` /
type definitions). NodeId emission and member expansion remain in
`RockwellLogixPolicy`/`AddressSpaceBuilder` per `ADDRESSING.md`.

### 3.1 UDT `DATATYPE` blocks → `udtDefinitions`
Each `DATATYPE` → a type with an ordered member list `(name, type, dims, hidden, bit?,
externalAccess)`. Hidden host bytes retained for bit backing but non-browsable. Feeds
`ADDRESSING.md` §3.3/§3.7. **DOC-CONFIRMED** (mirrors L5X `<DataType>` handling).

### 3.2 AOI `ADD_ON_INSTRUCTION_DEFINITION` blocks → **types** (same shape as L5X `AddOnInstruction`)
An AOI is a **type**, not a tag. It maps to the identical structure the L5X
`AddOnInstruction` produces, so instances expand through the same machinery
(`ADDRESSING.md` §3.3). Member set of an AOI instance:

1. **`EnableIn`, `EnableOut`** — emit both (BOOL). `ADDRESSING.md` §3.3 requires restoring
   them; v10 explicitly drops them (`:235`). **DOC-CONFIRMED** (ADDRESSING §5.3).
2. **Input / Output parameters** — emit, honouring `ExternalAccess` (§3.3 below) and
   recursively expanding UDT/predefined/array-typed parameters.
3. **InOut parameters — EXCLUDE.** InOut is a *reference*, not backing storage
   (`ADDRESSING.md` §3.3). FILE-CONFIRMED counts: A has 136 InOut params, C has 1 — these
   must not become instance members. **DOC-CONFIRMED.**
4. **Local tags — visibility by `ExternalAccess`** (see §3.3). **FILE-CONFIRMED decision.**

### 3.3 LOCAL_TAGS / parameter visibility — **the decision, FILE-CONFIRMED**

**Decision: key every AOI parameter's and local tag's browsability off its explicit
`ExternalAccess` attribute, per-member — do NOT apply a blanket "locals are hidden" rule.**

- `ExternalAccess := None` → **not emitted** (no node; excluded from browse and I/O),
  exactly as the real Ignition Logix (CIP) driver drops no-external-access tags
  (`ADDRESSING.md` §3.12, DOC-CONFIRMED: pturmel §3.1 + IA support).
- `ExternalAccess := Read Only` → node created `READ_ONLY`.
- `ExternalAccess := Read/Write` (or absent, default for parameters) → read-write node.

**Evidence this must be per-attribute, not assumed None** (FILE-CONFIRMED, dissected):

| File | AOI local `ExternalAccess := None` | `Read Only` | `Read/Write` |
| --- | ---: | ---: | ---: |
| A | 2 632 (98.9 %) | 0 | 29 |
| C | 255 (46 %) | 79 | 220 (40 %) |

`ADDRESSING.md` §3.3 states AOI locals *default* to `None` and are usually not browsable —
true for file A (a bespoke site library) but **false for file C**, whose PlantPAx
`P_*` AOIs expose 299 of 554 locals as Read Only / Read/Write. A blanket "hide all locals"
rule would wrongly delete ~54 % of file C's AOI-local surface; a blanket "show all" would
wrongly expose file A's 2 632 hidden scratch tags. **Honour the attribute.** This is the
same rule the L5X path already uses, so AOI expansion stays identical across formats
(plan §"Ground rules"). Confidence: **FILE-CONFIRMED / DOC-CONFIRMED** (both the files and
ADDRESSING §3.12 agree on the mechanism).

Parameters follow the same rule; `EnableIn`/`EnableOut` are `Read Only, Visible := No` in
the files but are emitted anyway per ADDRESSING §3.3 (they are the AOI's system-defined
enable pins the driver exposes).

### 3.4 Controller `TAG` block → `global_tags`
Bare NodeId (`ADDRESSING.md` §3.1). Includes atomic, predefined, UDT-instance, AOI-instance,
array, string, and **alias** tags (§2.2). **DOC-CONFIRMED.**

### 3.5 Program `TAG` block → `programs[<name>].tags`
NodeId `Program:<name>.<tag>` (`ADDRESSING.md` §3.2). Scope = the enclosing `PROGRAM`.
A program with an **empty** `TAG`/`END_TAG` yields zero program tags (still a valid,
tag-less program). **FILE-CONFIRMED** (A: all 3 programs empty; C: 4 of 7 empty).

### 3.6 `MODULE` blocks → I/O module policy (`ADDRESSING.md` §3.13)
`MODULE` blocks are **not** tag declarations and declare no controller tags directly; skip
their bodies for tag parsing (they contain `CONNECTION`/`ConfigData`/`ExtendedProp`
records with `[...]` data). They ARE the source for synthesising I/O module tags
(`<Module>:I.Data`, `Local:s:I.Data`) per `ADDRESSING.md` §3.13 — capture
`(name, CatalogNumber, Parent, Slot/Address)` for that policy. Alias tags (§2.2) reference
these module I/O points. Whether v10.1 synthesises module I/O tags is the §3.13 decision;
the grammar only requires MODULE bodies be skipped, not mis-parsed. **INFERRED** (naming
per ADDRESSING §3.13, flagged there).

### 3.7 FBD instruction backing types → predefined member tables
`FBD_TIMER`, `FBD_COMPARE`, `FBD_ONESHOT`, `FBD_COUNTER`, `FBD_MASK_EQUAL`, … are predefined
function-block types that back real tag storage. They appear as ordinary program/controller
tags (FILE-CONFIRMED — C: `TONR_* : FBD_TIMER`, `LES_* : FBD_COMPARE`, `OSFI_* :
FBD_ONESHOT`). `RockwellBuiltInTypes` must define member sets for all FB types present, not
only the TIMER-shaped one (SUMMARY finding #5). Missing FB type → unmodelled-type counter
(§5), emit as opaque leaf. **DOC-CONFIRMED (gap) / member sets INFERRED** (derive from a
real FBD export or the manual §5 "Enter Parameters for Function Block Instructions" table).

### 3.8 Ground-truth counts — what a correct parse should yield

Derived by block-aware dissection (scratchpad `analyse.py`, cross-checked against an
independent tab-anchored `awk` count — both agree on instance totals). **Instance-level
counts are exact/high-confidence; expanded-node totals are policy-sensitive estimates.**

#### File A — `DemoWWTP-sample-b.L5K`

| Quantity | Count | Confidence |
| --- | ---: | --- |
| UDT (`DATATYPE`) definitions | **27** | exact |
| AOI definitions | **137** | exact |
| Controller tag instances | **1 224** | exact |
|  — of which alias (`OF`) | 572 | exact |
|  — AOI instances | 251 | exact |
|  — UDT instances | 30 | exact |
|  — predefined (TIMER/COUNTER/MESSAGE/…) | 118 | exact |
|  — atomic (incl. STRING) | 253 | exact |
| Program tag instances (3 programs, all empty) | **0** | exact |
| Top-level array tags | **12** → **102** elements | exact |
|  — `<Name> : DINT[2]` | 1 → 2 elems |  |
|  — 10 × `STRING[5]` + 1 × `STRING[50]` | 11 → 100 string elems |  |
| Expanded browsable node estimate (visible-only) | **≈ 42 000** | LOW (see note) |

Arithmetic check: 572 alias + 251 AOI + 30 UDT + 118 predef + 253 atomic = 1 224 ✓.

#### File C — `DemoPlant-PLC.L5K`

| Quantity | Count | Confidence |
| --- | ---: | --- |
| UDT definitions | **11** | exact |
| AOI definitions | **13** | exact |
| Controller tag instances | **545** (0 alias) | exact |
|  — AOI instances / UDT / predefined / FBD / atomic | 96 / 5 / 7 / 21 / 416 | exact |
| Program tag instances | **29** total | exact |
|  — `Drives` | 17 (13 FBD + 4 AOI) | exact |
|  — `LoadCells` | 5 (AOI) | exact |
|  — `Valves` | 7 (6 FBD + 1 AOI) | exact |
|  — `DiscreteDevices`, `HMI`, `Sequencers`, `Vibrators` | 0 each (empty TAG) | exact |
| Top-level array tags | **2** → **85** elements | exact |
|  — `<Name> : <Udt>[45]` + `<Name> : <Udt>[40]` (arrays-of-UDT) | 45 + 40 | exact |
| Expanded browsable node estimate (visible-only) | **≈ 85 000** | LOW (see note) |

Arithmetic check: 96 + 5 + 7 + 21 + 416 = 545 ✓; 17 + 5 + 7 = 29 ✓; program folders
`{Drives, LoadCells, Valves}` exactly matches the live REST result in `SUMMARY.txt`.

**Why the expanded-node estimate is LOW confidence and must NOT be a test oracle.** The
fully-expanded leaf count is dominated by policy choices this document deliberately leaves
to `ADDRESSING.md`: whether `STRING` expands to `LEN`+`DATA[82]` (84 nodes each), how deep
AOI/UDT nesting is browsed, and whether hidden `None` members count. Under the visible-only
policy (drop `None`, expand STRING per §3.10, expand arrays-of-UDT) the estimates are ≈42 k
(A) and ≈85 k (C); these swing by 3–5× with STRING and nesting policy. **The acceptance
oracle is the instance-level counts above plus the structural assertions in §6, not a
single expanded total.** (For context: v10 reported 43 013 for A and 44 116 for C — both
inflated by rung-token pseudo-tags and duplicate flattening, not real expansion.)

---

## 4. Current-parser defect catalogue (`L5KParser.java`)

| # | Defect | Location | Effect | Spec ref |
| --- | --- | --- | --- | --- |
| **D1** | **No block stack; tag lines parsed by whole-file regex.** `parseTagsWithUDTs` tracks only `inControllerScope`/`currentProgram`/`inTagBlock` flags; never tracks AOI/ROUTINE/DATATYPE/MODULE nesting. | `:246–304` | AOI & program **rung** lines `N: XIC(…)` read as tags. | §1.4 R1/R2 |
| **D2** | **"Controller tag outside a TAG block" path** matches `CONTROLLER_TAG_PATTERN` (`^\s+name\s*:\s*type\s*\(`) against every controller-scope line. | `:281–288`, pattern `:58–59` | Emits pseudo-tag `N` with `data_type` = first mnemonic (`XIC`/`XIO`/`COP`/`EQ`/`MESSAGE`/`OTU`/`LBL`…); thousands of bogus/duplicate entries. **The SUMMARY corruption.** In real files this path never matches a legitimate tag (all controller tags are inside the TAG block). Delete it. | §1.4 R1 |
| **D3** | **DATATYPE member regex is `NAME : TYPE`** but L5K members are `TYPE NAME`. | pattern `:36–37`, used `:194–200` | Every non-bit UDT member (DINT/REAL/TIMER/nested-UDT) **dropped**; only `BIT` members survive → UDT instances expand with bits only. | §2.6 |
| **D4** | **Member/parameter/local array dimensions dropped.** `DATATYPE_MEMBER_PATTERN` and the AOI member path capture only `(name,type)`; `UDTDefinition.addMember` has no dims arg. | `:36–37`, `:232–236`, `:335–365` | Every UDT-member array and **every AOI-local array (incl. `FLOW_HOURS : DINT[30]`) collapses to a scalar.** | §2.3/§2.6/§2.7 |
| **D5** | **AOI parameter semantics ignored:** no `ExternalAccess` (→ can't hide `None` locals), no `Usage` (→ InOut not excluded), and `EnableIn`/`EnableOut` **explicitly dropped**. | `:235`; no attr capture `:231–238` | Wrong AOI instance member set (missing enable pins, InOut refs leaked, no `None` filtering). | §3.2/§3.3 |
| **D6** | **Alias (`OF`) tags unhandled.** No pattern matches `name OF target`. | patterns `:56–59` | All 572 alias tags in file A **silently dropped**. | §2.2 |
| **D7** | **Line-oriented, not statement-oriented.** Each `for (String line …)` iteration is a candidate declaration; no accumulation to the `;` terminator. | `:251`, `:291–301` | Multi-line UDT/AOI/string initialisers (file C `P_DIn`, etc.) mis-split; continuation lines mis-read or tags miscounted. | §1.4 R3/R4, §2.5 |
| **D8** | **Type token stops at first non-word char**, cannot represent colon-bearing module types; combined with D6 aliases to module I/O are lost. | pattern `:56–59` | Module-referenced tags lost/misparsed. | §2.1 |
| **D9** | **No loud failure / no per-construct counters.** Only a total-zero guard exists; rung-token matches produce no WARN. | `:121–138` (only zero-guard); no skip counters anywhere | Silent corruption reported as `success:true` / HTTP 200 — the exact FAIL-LOUDLY breach in `SUMMARY.txt`. | §5 |
| **D10** | **FBD/predefined type coverage gap** (consumes `RockwellBuiltInTypes`). `FBD_COMPARE`/`FBD_ONESHOT` etc. have no member table → opaque leaves. | `RockwellBuiltInTypes` (via `:165`) | FBD-authored programs lose backing-tag members (SUMMARY finding #5). | §3.7 |
| **D11** | **AOI-def parsing has no explicit ROUTINE bound**, relying solely on `inParameters`/`inLocalTags` flags. Currently safe *only* because it also stops at `END_ADD_ON_INSTRUCTION_DEFINITION`; brittle if a routine precedes params or flags desync. | `:207–244` | Latent; harden with the block stack. | §1.4 R2 |

---

## 5. Loud-failure rules

The FAIL-LOUDLY contract the plan restores. Two response classes:

### 5.1 HARD FAIL — reject the file, parse returns `null`, caller returns HTTP 4xx naming file+parser
- **No `CONTROLLER` block** found. (Not an L5K.)
- **Unbalanced block nesting**: any recognised begin keyword with no matching `END_*`
  before EOF, or an `END_*` with no open matching block. Structural corruption — never
  emit a partial tree.
- **Zero recognised tag instances AND zero type definitions** (retain existing FIX-6
  guard, `:132–138`).
- **Tripwire: a rung-shaped token in a tag context.** With correct bounding this is
  impossible; if the parser is ever about to record a tag whose name matches a rung type
  `^(N|I|D|IR|rR|R|rI|rN|e|er)$` **or** whose "type" is a known ladder mnemonic
  (`XIC XIO OTE OTL OTU MOV MOVE COP CPS TON TOF RTO CTU CTD JSR MSG ADD SUB MUL DIV
  EQU NEQ GEQ LEQ GRT LES LIM ONS OSR OSF CLR LBL NOP …`), it means the block stack is
  broken → **hard fail** with a diagnostic naming the offending line. This turns the v10
  silent corruption into an immediate, unmissable error.

### 5.2 SKIP-WITH-COUNTER — WARN, increment a named counter, continue; counters surfaced in the upload response
- `skippedTagLines` — a line inside a whitelisted tag block that matches neither a
  non-alias tag, an alias, nor a recognised continuation.
- `unknownTypeTags` — a tag/member whose type is not atomic/string/predefined/FB/UDT/AOI;
  emitted as an opaque leaf and counted.
- `unmodelledFbdTypes` — an `FBD_*`/predefined type with no member table (§3.7).
- `droppedNoneAccess` — members/tags omitted because `ExternalAccess := None` (informational;
  expected to be large — 2 632 for file A — so it is a *count*, not a warning).

### 5.3 Upload response surface (parser summary object)
The `/upload` response and the gateway log MUST include, per file:
`controllerTagCount`, `programTagCounts{}`, `udtDefCount`, `aoiDefCount`,
`aliasTagCount`, `arrayTagCount`, `skippedTagLines`, `unknownTypeTags`,
`unmodelledFbdTypes`, `droppedNoneAccess`, and `structurallyClean` (true iff no
tripwire fired and `skippedTagLines == 0`). Any non-zero `skippedTagLines`/`unknownTypeTags`/
`unmodelledFbdTypes` → a visible warning banner. A `structurallyClean == false` upload must
never read as an unqualified success.

---

## 6. Test-assertion checklist

### 6.1 Real-file integration test (`PLC_EMU_PRIVATE_L5K_DIR`, skipped when unset)

Assert the **exact instance counts** from §3.8 and the **structural invariants** below.
Do NOT assert the expanded-node totals (policy-sensitive) or hard-code private tag names in
the repo — the gated test reads names live from the file.

**File A (`DemoWWTP_…`):**
1. `controllerTagCount == 1224`; `aliasTagCount == 572`; `arrayTagCount == 12`.
2. Program folders = exactly `{MainProgram, TeSysIsland_DOL, TeSysIsland_SS1}` (names may
   vary — assert **3 programs, each with 0 tags**).
3. `udtDefCount == 27`; `aoiDefCount == 137`.
4. **Zero rung-token tags:** no tag named `N`/`RC`/any rung type; no tag or member whose
   `data_type` is a ladder/FBD mnemonic (`XIC XIO OTE OTL OTU COP EQ MESSAGE OTU LBL MOV
   TON JSR …`). *(Directly refutes the SUMMARY corruption.)*
5. `structurallyClean == true`; `skippedTagLines == 0`.
6. At least one alias tag exists and resolves (pick the first `OF` tag live); its NodeId is
   the bare alias name.
7. A top-level `DINT[2]` array tag expands to `[0]`,`[1]`; a `STRING[50]` array tag exists
   with 50 elements, each exposing `.LEN` + `.DATA[i]`.
8. **Absence:** the AOI-local `DINT[30]` array with `ExternalAccess := None` (e.g.
   `FLOW_HOURS`) is **NOT** browsable (correctly hidden by §3.3) — assert it does not
   appear as a controller tag, and equally is not leaked as a bogus `N`/mnemonic tag.

**File C (`DemoPlant_…`):**
1. `controllerTagCount == 545`; `aliasTagCount == 0`; `arrayTagCount == 2`.
2. Program tag counts: `Drives == 17`, `LoadCells == 5`, `Valves == 7`; the other four
   programs have 0 tags. Program folders present = `{Drives, LoadCells, Valves}` (non-empty).
3. `udtDefCount == 11`; `aoiDefCount == 13`.
4. Zero rung-token tags (as A.4); `structurallyClean == true`.
5. Program `Drives` contains `FBD_TIMER` instances that expand to their member set, and
   `FBD_COMPARE`/`FBD_ONESHOT` instances that are either expanded (if a member table is
   added) or counted in `unmodelledFbdTypes` (never silently dropped).
6. The two controller arrays-of-UDT (`<Udt>[45]`, `<Udt>[40]`) expand to 45 and 40
   element structures (`Tag[i].<member>`), per `ADDRESSING.md` §3.6.
7. AOI-local visibility honoured: at least one PlantPAx AOI instance exposes Read/Write
   locals (file C has 220) while `None` locals are absent.

### 6.2 Synthetic in-repo fixtures (`gateway/src/test/resources/l5k/`)

Small hand-written `.L5K` files, one construct each; assert against the parsed model.

| Fixture | Constructs | Key assertions |
| --- | --- | --- |
| `block_bounding.l5k` | CONTROLLER → 1 AOI (PARAMETERS+LOCAL_TAGS+ROUTINE with `N:`/`RC:` rungs whose operands are `XIC`/`OTE`) → controller TAG with 2 tags | **No tag named `N`; no `data_type` = `XIC`/`OTE`;** exactly 2 controller tags; AOI parsed as a type. *The regression guard for D1/D2.* |
| `datatype_members.l5k` | UDT with `DINT`, `REAL`, `TIMER`, a nested UDT, `DINT Data[10]`, and `BIT`+hidden host | All non-bit members present (not just bits — D3); `Data` keeps `[10]` (D4); `ZZZZ*` host hidden but its `BIT` children present. |
| `tag_forms.l5k` | atomic scalar w/ radix, `TIMER := [0,5000,0]`, UDT instance w/ nested `[…]` init, `DINT[2]`, multi-dim `INT[2,4]` | Types, dims (`"2"`, `"2,4"`), and multi-line init all parsed; statement boundary at `;` (D7). |
| `alias.l5k` | `Base : DINT; Al OF Base (…); AlBit OF Base.5; AlMod OF Mod:1:I.Data` | 3 alias tags emitted with `alias_for` targets (D6); colon-bearing module target survives (D8). |
| `string.l5k` | `STRING` scalar `:= [5,'hello$00…']`, `STRING[3]` array `:= [[…],[…],[…]]` | String value + `.LEN` + `.DATA[i]`; array = 3 string elements; `$00` padding tokenised, not split (R4). |
| `aoi_access.l5k` | AOI w/ Input/Output/InOut params + `EnableIn/Out` + locals `None` / `Read Only` / `Read/Write`; one instance | Instance members = `EnableIn`,`EnableOut`,visible Input/Output params, `Read Only`+`Read/Write` locals; **absent:** InOut param, `None` locals (§3.2/§3.3). |
| `program_scope.l5k` | 2 programs, one empty TAG, one with 2 tags + a ROUTINE with rungs | Program-scoped tags nested under the right program (R5); empty program yields 0 tags; rungs not leaked. |
| `fbd_types.l5k` | program tags `FBD_TIMER`, `FBD_COMPARE`, `FBD_ONESHOT` | Modelled FB types expand; unmodelled ones increment `unmodelledFbdTypes` (never dropped) — D10. |
| `loud_fail.l5k` | (a) file with no CONTROLLER; (b) file with `DATATYPE` and no matching `END_DATATYPE`; (c) a doctored file with a stray `N: XIC(x);` inside a TAG block | (a),(b) → parse returns null / HTTP 4xx; (c) → hard-fail tripwire (§5.1). |
| `config_depth.l5k` | CONTROLLER with `CONFIG X() END_CONFIG` emitted at column 0 before `END_CONTROLLER` | CONFIG carries no tags and does not end controller scope early (§1.2 rule 2 / §1.5). |

---

## 7. Trickiest rules — summary for the implementer

1. **AOI/ROUTINE bounding (§1.4)** — the whole defect. A rung `N: XIC(…);` is
   token-identical to a tag `Name : Type(…);`; only the enclosing block tells them apart.
   Fix = whitelist tag parsing to `{CTRL_TAG, PROG_TAG, AOI_PARAM, AOI_LOCAL, DATATYPE}`
   via an explicit keyword-delimited block stack, delete the "tags outside a TAG block"
   path, and treat all `*ROUTINE`/unknown blocks as opaque. Add the rung-token tripwire
   (§5.1) so any regression fails loud instead of silent.
2. **Two opposite member orders** — DATATYPE members are `TYPE NAME`; tag lines and AOI
   params/locals are `NAME : TYPE`. v10 uses `NAME : TYPE` for DATATYPE members and thus
   drops all non-bit UDT members.
3. **Statement-oriented, quote/bracket-aware tokenising (§1.4 R3/R4)** — UDT/AOI/string
   initialisers span 10–20 lines with nested `[…]` and `'…$NN…'`; the terminator is the
   `;` after the outermost `]`, and `;`/`:` can hide inside strings.
4. **`OF` aliases and colon-bearing module types** — a whole tag class (572 in file A) v10
   ignores; the type token can contain `:` (module refs).
5. **Aggregate initialisers use `[...]`, not `{...}`** (manual, verbatim) — the task's
   curly-brace hypothesis is wrong for L5K.

---

## Appendix A — confidence / manual-vs-file reconciliation

- **Manual vs files agree** on: block keywords and `END_*` termination; DATATYPE member
  `TYPE NAME` + `BIT` syntax; tag line `name : type[dims] (attrs) := init`; `OF` alias
  syntax; array spec `[e,e,e]`; `[...]` (not `{...}`) initialisers; STRING
  `[len,'…$00']`; rung `<RungType>: text;`.
- **In the files but not the 2005 manual revision:** `ADD_ON_INSTRUCTION_DEFINITION` /
  `PARAMETERS` / `LOCAL_TAGS` (AOIs postdate it — v16/2007; present as a section in the
  current 1756-RM084), `ST_ROUTINE`/`FBD_ROUTINE`, `ExternalAccess`/`DataExchangeId`
  attributes, `CHILD_PROGRAMS`. All are **FILE-CONFIRMED** from both real exports.
- **Contradiction flagged:** `ADDRESSING.md` §3.3 says AOI local tags *default to
  `ExternalAccess = None` (usually not browsable)*. True for file A (98.9 % None) but
  **false for file C** (PlantPAx AOIs: 54 % of locals are Read Only/Read/Write). The
  resolution (§3.3) — honour the per-member `ExternalAccess` attribute, never assume — is
  the same mechanism the L5X path uses, so it does not contradict ADDRESSING's *mechanism*,
  only its *"default"* framing. No behavioural conflict for the parser.
- **`OpcUaAccess`:** the L5X-8.3 trap (`ADDRESSING.md` §3.12) does **not** occur in these
  L5K files — the attribute is absent. Nothing to ignore on the L5K path.

## Appendix B — sources
- Rockwell Automation, *Logix5000 Controllers Import/Export Reference Manual*,
  publication **1756-RM084K-EN-P** (fetched mirror; current series 1756-RM084) — §3
  (CONTROLLER/DATATYPE/MODULE/TAG/PROGRAM/TASK/CONFIG structure, tag & datatype grammar,
  initial-value & alias syntax), §4 (ladder ROUTINE & rung neutral-text grammar).
  <https://literature.rockwellautomation.com/idc/groups/literature/documents/rm/1756-rm084_-en-p.pdf>
- `docs/ADDRESSING.md` — NodeId / expansion / ExternalAccess contract this parser feeds.
- Real site exports **A** and **C** (PRIVATE — never committed; dissected in place).
- Dissection scripts (scratchpad, not committed): block-aware `count.awk`, `analyse.py`,
  `expand.py`; counts cross-checked by two independent methods.
```
