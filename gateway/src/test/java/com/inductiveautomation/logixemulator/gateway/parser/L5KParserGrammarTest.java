package com.inductiveautomation.logixemulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Synthetic-fixture tests for the v10.1 statement-oriented, block-stack-bounded
 * {@link L5KParser} - one test group per fixture in the L5K-GRAMMAR.md §6.2 matrix
 * ({@code src/test/resources/test-files/l5k/synthetic-*.l5k}).
 *
 * <p>Each fixture isolates one construct family and its §4 defect(s); the assertions are the
 * §6.2 checklist rows. The env-gated real-file assertions (§6.1) live in
 * {@link L5KRealFileIntegrationTest}.
 */
@Tag("l5k")
class L5KParserGrammarTest {

    private static final Path FIXTURES = Path.of("src/test/resources/test-files/l5k");

    /** Rung-type names that must never appear as tag names (L5K-GRAMMAR.md §5.1). */
    private static final Set<String> RUNG_NAMES =
        Set.of("N", "I", "D", "IR", "rR", "R", "rI", "rN", "e", "er");

    /** Ladder mnemonics that must never appear as a tag/member data type (§5.1; MESSAGE is
     *  excluded - it is a legitimate predefined type, see the parser's LADDER_MNEMONICS note). */
    private static final Set<String> MNEMONIC_TYPES =
        Set.of("XIC", "XIO", "OTE", "OTL", "OTU", "COP", "EQ", "LBL", "MOV", "TON", "JSR", "MUL");

    private L5KParser parser;

    @BeforeEach
    void setUp() {
        parser = new L5KParser();
    }

    private JsonObject parseFixture(String name) throws IOException {
        String content = Files.readString(FIXTURES.resolve(name));
        return parser.parseContent(content, name);
    }

    // ---------------------------------------------------------------------------------------
    // Fixture: synthetic-block-bounding.l5k (D1/D2 - THE defect)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("block_bounding: AOI ROUTINE rungs never leak as tags; exactly the 2 declared "
        + "controller tags exist and the AOI parses as a type (D1/D2 regression guard)")
    void blockBoundingKeepsRungsOutOfTagContexts() throws IOException {
        JsonObject result = parseFixture("synthetic-block-bounding.l5k");

        assertThat(result).isNotNull();
        assertThat(result.get("controller").getAsString()).isEqualTo("BlockBounding");

        JsonArray globalTags = result.getAsJsonArray("global_tags");
        assertThat(tagNames(globalTags)).containsExactly("FirstTag", "AoiInstance");

        assertNoRungArtefacts(result);

        // The AOI parsed as a TYPE (same shape as an L5X AddOnInstruction), not as tags.
        JsonArray aois = result.getAsJsonArray("aois");
        assertThat(aois).isNotNull();
        assertThat(aois).hasSize(1);
        assertThat(aois.get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("My_Aoi");

        // Instance expansion: EnableIn/EnableOut restored (ADDRESSING.md §3.3), params present,
        // local TIMER expands through the built-in member table.
        JsonObject instance = findTag(globalTags, "AoiInstance");
        List<String> members = memberNames(instance);
        assertThat(members).containsExactly("EnableIn", "EnableOut", "DI", "Sts", "Off_T");
        JsonObject offT = findMember(instance, "Off_T");
        assertThat(memberNames(offT)).contains("PRE", "ACC", "DN");

        // The program exists with zero tags; its ROUTINE contributed nothing.
        JsonArray programs = result.getAsJsonArray("programs");
        assertThat(programs).hasSize(1);
        JsonObject mainProgram = programs.get(0).getAsJsonObject();
        assertThat(mainProgram.get("name").getAsString()).isEqualTo("MainProgram");
        assertThat(mainProgram.getAsJsonArray("tags")).isEmpty();

        JsonObject summary = result.getAsJsonObject("parseSummary");
        assertThat(summary.get("controllerTagCount").getAsInt()).isEqualTo(2);
        assertThat(summary.get("aoiDefCount").getAsInt()).isEqualTo(1);
        assertThat(summary.get("skippedTagLines").getAsInt()).isZero();
        assertThat(summary.get("structurallyClean").getAsBoolean()).isTrue();
    }

    // ---------------------------------------------------------------------------------------
    // Fixture: synthetic-datatype-members.l5k (D3/D4)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("datatype_members: TYPE NAME member order parsed - all non-bit members present, "
        + "member arrays keep dims, ZZZZ hosts hidden but BIT children kept (D3/D4)")
    void datatypeMembersUseTypeNameOrder() throws IOException {
        JsonObject result = parseFixture("synthetic-datatype-members.l5k");

        assertThat(result).isNotNull();
        JsonArray udts = result.getAsJsonArray("udts");
        assertThat(udts).hasSize(2);

        JsonObject myUdt = findByName(udts, "MyUdt");
        JsonArray members = myUdt.getAsJsonArray("members");
        List<String> names = new ArrayList<>();
        for (JsonElement m : members) {
            names.add(m.getAsJsonObject().get("name").getAsString());
        }
        // D3: every non-bit member survives (v10 kept only the BITs); D4: Data keeps [10];
        // hidden ZZZZ host dropped from the browse model.
        assertThat(names).containsExactly(
            "Speed", "Current", "OFL_FILTER", "Nested", "Data", "Fault_A", "Fault_B");

        assertThat(memberByName(members, "Speed").get("data_type").getAsString()).isEqualTo("DINT");
        assertThat(memberByName(members, "Current").get("data_type").getAsString()).isEqualTo("REAL");
        assertThat(memberByName(members, "OFL_FILTER").get("data_type").getAsString()).isEqualTo("TIMER");
        assertThat(memberByName(members, "Nested").get("data_type").getAsString()).isEqualTo("InnerUdt");
        assertThat(memberByName(members, "Data").get("dimensions").getAsString()).isEqualTo("10");
        assertThat(memberByName(members, "Fault_A").get("data_type").getAsString()).isEqualTo("BOOL");

        // Instance expansion carries all of it, recursively.
        JsonObject instance = findTag(result.getAsJsonArray("global_tags"), "Instance1");
        assertThat(memberNames(instance)).containsExactly(
            "Speed", "Current", "OFL_FILTER", "Nested", "Data", "Fault_A", "Fault_B");
        assertThat(findMember(instance, "Data").get("dimensions").getAsString()).isEqualTo("10");
        assertThat(memberNames(findMember(instance, "Nested"))).containsExactly("Val");
        assertThat(memberNames(findMember(instance, "OFL_FILTER"))).contains("PRE", "ACC");
    }

    // ---------------------------------------------------------------------------------------
    // Fixture: synthetic-tag-forms.l5k (D7)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("tag_forms: radix scalars, struct inits, multi-line aggregate initialisers and "
        + "multi-dim arrays all parse as single ';'-terminated statements (D7)")
    void tagFormsAreStatementOriented() throws IOException {
        JsonObject result = parseFixture("synthetic-tag-forms.l5k");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tagNames(tags)).containsExactly(
            "ScalarHex", "ScalarExp", "Timer1", "Recipe1", "PairArr", "Grid");

        assertThat(findTag(tags, "ScalarHex").get("initial_value").getAsString()).isEqualTo("16#00ff");
        assertThat(findTag(tags, "ScalarExp").get("initial_value").getAsString())
            .isEqualTo("1.00000000e+000");

        assertThat(memberNames(findTag(tags, "Timer1"))).contains("PRE", "ACC", "EN", "TT", "DN");

        // The multi-line [...] initialiser must not split the statement: Recipe1 is one tag with
        // its UDT members, and nothing after it was misread.
        JsonObject recipe = findTag(tags, "Recipe1");
        assertThat(recipe.get("data_type").getAsString()).isEqualTo("RecipeType");
        assertThat(memberNames(recipe)).containsExactly("Amount", "Rate", "Mix");

        JsonObject pairArr = findTag(tags, "PairArr");
        assertThat(pairArr.get("isArray").getAsBoolean()).isTrue();
        assertThat(pairArr.get("dimensions").getAsString()).isEqualTo("2");

        JsonObject grid = findTag(tags, "Grid");
        assertThat(grid.get("dimensions").getAsString()).isEqualTo("2,4");

        JsonObject summary = result.getAsJsonObject("parseSummary");
        assertThat(summary.get("arrayTagCount").getAsInt()).isEqualTo(2);
        assertThat(summary.get("skippedTagLines").getAsInt()).isZero();
        assertThat(summary.get("structurallyClean").getAsBoolean()).isTrue();
    }

    // ---------------------------------------------------------------------------------------
    // Fixture: synthetic-alias.l5k (D6/D8)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("alias: OF tags emitted with alias_for targets; bit target resolves BOOL; "
        + "colon-bearing module I/O target survives (D6/D8)")
    void aliasTagsAreEmitted() throws IOException {
        JsonObject result = parseFixture("synthetic-alias.l5k");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tagNames(tags)).containsExactly("Base", "Al", "AlBit", "AlMod");

        assertThat(findTag(tags, "Al").get("alias_for").getAsString()).isEqualTo("Base");

        JsonObject alBit = findTag(tags, "AlBit");
        assertThat(alBit.get("alias_for").getAsString()).isEqualTo("Base.5");
        assertThat(alBit.get("data_type").getAsString()).isEqualTo("BOOL");

        JsonObject alMod = findTag(tags, "AlMod");
        assertThat(alMod.get("alias_for").getAsString()).isEqualTo("EXP_IO:10:I.Ch07.Data");
        assertThat(alMod.get("data_type").getAsString()).isEqualTo("REAL");

        JsonObject summary = result.getAsJsonObject("parseSummary");
        assertThat(summary.get("aliasTagCount").getAsInt()).isEqualTo(3);
        assertThat(summary.get("controllerTagCount").getAsInt()).isEqualTo(4);
        assertThat(summary.get("structurallyClean").getAsBoolean()).isTrue();
    }

    // ---------------------------------------------------------------------------------------
    // Fixture: synthetic-string.l5k (R4)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("string: $NN escapes and ';'/'['/'\"' inside quoted string bodies do not split "
        + "statements; STRING gets .LEN/.DATA; STRING[3] keeps its dimension (R4)")
    void stringLiteralsDoNotBreakTokenising() throws IOException {
        JsonObject result = parseFixture("synthetic-string.l5k");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tagNames(tags)).containsExactly("Greeting", "Msgs");

        JsonObject greeting = findTag(tags, "Greeting");
        assertThat(greeting.get("data_type").getAsString()).isEqualTo("STRING");
        assertThat(memberNames(greeting)).containsExactly("LEN", "DATA");
        assertThat(findMember(greeting, "DATA").get("dimensions").getAsString()).isEqualTo("82");

        JsonObject msgs = findTag(tags, "Msgs");
        assertThat(msgs.get("isArray").getAsBoolean()).isTrue();
        assertThat(msgs.get("dimensions").getAsString()).isEqualTo("3");
        assertThat(memberNames(msgs)).containsExactly("LEN", "DATA");

        JsonObject summary = result.getAsJsonObject("parseSummary");
        assertThat(summary.get("skippedTagLines").getAsInt()).isZero();
        assertThat(summary.get("structurallyClean").getAsBoolean()).isTrue();
    }

    // ---------------------------------------------------------------------------------------
    // Fixture: synthetic-aoi-access.l5k (§3.2/§3.3)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("aoi_access: instance members are EnableIn/EnableOut + Input/Output params + "
        + "ReadOnly/ReadWrite locals; InOut param and ExternalAccess=None locals absent (§3.2/§3.3)")
    void aoiAccessHonoursExternalAccessPerMember() throws IOException {
        JsonObject result = parseFixture("synthetic-aoi-access.l5k");

        assertThat(result).isNotNull();
        JsonObject inst = findTag(result.getAsJsonArray("global_tags"), "Inst");

        // Present: enable pins, visible params, RO+RW locals. Absent: the InOut reference param
        // (not backing storage) and both None locals (incl. the DINT[30] array - the FLOW_HOURS
        // case from real file A).
        assertThat(memberNames(inst)).containsExactly(
            "EnableIn", "EnableOut", "InVal", "OutVal", "RoLocal", "RwLocal");

        assertThat(isReadOnly(findMember(inst, "EnableIn"))).isTrue();
        assertThat(isReadOnly(findMember(inst, "EnableOut"))).isTrue();
        assertThat(isReadOnly(findMember(inst, "OutVal"))).isTrue();
        assertThat(isReadOnly(findMember(inst, "RoLocal"))).isTrue();
        assertThat(isReadOnly(findMember(inst, "InVal"))).isFalse();
        assertThat(isReadOnly(findMember(inst, "RwLocal"))).isFalse();

        JsonObject summary = result.getAsJsonObject("parseSummary");
        assertThat(summary.get("droppedNoneAccess").getAsInt()).isEqualTo(2);
        assertThat(summary.get("structurallyClean").getAsBoolean()).isTrue();
    }

    // ---------------------------------------------------------------------------------------
    // Fixture: synthetic-program-scope.l5k (R5)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("program_scope: tags nest under the right program; an empty TAG block yields a "
        + "valid tag-less program; program ROUTINE rungs never leak (R5)")
    void programScopeIsDecidedByBlockParent() throws IOException {
        JsonObject result = parseFixture("synthetic-program-scope.l5k");

        assertThat(result).isNotNull();
        assertThat(tagNames(result.getAsJsonArray("global_tags"))).containsExactly("GlobalOne");

        JsonArray programs = result.getAsJsonArray("programs");
        assertThat(programs).hasSize(2);

        JsonObject emptyProg = findByName(programs, "EmptyProg");
        assertThat(emptyProg.getAsJsonArray("tags")).isEmpty();

        JsonObject workProg = findByName(programs, "WorkProg");
        assertThat(tagNames(workProg.getAsJsonArray("tags")))
            .containsExactly("LocalCounter", "LocalFlag");

        assertNoRungArtefacts(result);

        JsonObject summary = result.getAsJsonObject("parseSummary");
        JsonObject programCounts = summary.getAsJsonObject("programTagCounts");
        assertThat(programCounts.get("EmptyProg").getAsInt()).isZero();
        assertThat(programCounts.get("WorkProg").getAsInt()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------
    // Fixture: synthetic-fbd-types.l5k (D10)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("fbd_types: modelled FBD backing types expand; unmodelled ones are emitted as "
        + "opaque leaves and counted in unmodelledFbdTypes - never dropped (D10)")
    void fbdTypesAreCountedNotDropped() throws IOException {
        JsonObject result = parseFixture("synthetic-fbd-types.l5k");

        assertThat(result).isNotNull();
        JsonObject drives = findByName(result.getAsJsonArray("programs"), "Drives");
        JsonArray tags = drives.getAsJsonArray("tags");
        assertThat(tagNames(tags)).containsExactly("TONR_01", "LES_01", "OSFI_01");

        // FBD_TIMER has a member table - expands.
        assertThat(memberNames(findTag(tags, "TONR_01"))).contains("PRE", "ACC", "DN");

        // FBD_COMPARE/FBD_ONESHOT have no member table yet - opaque leaves, counted.
        assertThat(findTag(tags, "LES_01").has("udt_members")).isFalse();
        assertThat(findTag(tags, "OSFI_01").has("udt_members")).isFalse();

        JsonObject summary = result.getAsJsonObject("parseSummary");
        assertThat(summary.get("unmodelledFbdTypes").getAsInt()).isEqualTo(2);
        // Unmodelled types are a WARN + counter, not a structural failure.
        assertThat(summary.get("structurallyClean").getAsBoolean()).isTrue();
    }

    // ---------------------------------------------------------------------------------------
    // Fixture family: synthetic-loudfail-*.l5k (§5.1)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("loud_fail (a): a file with content but no CONTROLLER block returns null (§5.1)")
    void loudFailNoController() throws IOException {
        assertThat(parseFixture("synthetic-loudfail-nocontroller.l5k")).isNull();
    }

    @Test
    @DisplayName("loud_fail (b): an unclosed DATATYPE block is structural corruption - parse "
        + "returns null, never a partial tree (§5.1)")
    void loudFailUnbalancedNesting() throws IOException {
        assertThat(parseFixture("synthetic-loudfail-unbalanced.l5k")).isNull();
    }

    @Test
    @DisplayName("loud_fail (c): a stray rung statement inside a TAG block trips the §5.1 "
        + "tripwire - hard fail, no pseudo-tag named N")
    void loudFailRungTripwire() throws IOException {
        assertThat(parseFixture("synthetic-loudfail-tripwire.l5k")).isNull();
    }

    // ---------------------------------------------------------------------------------------
    // Fixture: synthetic-config-depth.l5k (§1.2 rule 2 / §1.5)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("config_depth: CONFIG blocks emitted at column 0 are self-closing, carry no "
        + "tags, and do not end controller scope early (§1.2 rule 2 / §1.5)")
    void configBlocksAtColumnZeroDoNotBreakScope() throws IOException {
        JsonObject result = parseFixture("synthetic-config-depth.l5k");

        assertThat(result).isNotNull();
        assertThat(result.get("controller").getAsString()).isEqualTo("ConfigDepth");
        assertThat(tagNames(result.getAsJsonArray("global_tags"))).containsExactly("OnlyTag");

        JsonObject summary = result.getAsJsonObject("parseSummary");
        assertThat(summary.get("controllerTagCount").getAsInt()).isEqualTo(1);
        assertThat(summary.get("skippedTagLines").getAsInt()).isZero();
        assertThat(summary.get("structurallyClean").getAsBoolean()).isTrue();
    }

    // ---------------------------------------------------------------------------------------
    // Fixture: synthetic-block-comment.l5k (FIX-B)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("block_comment: a (* ... *) block comment between two tag declarations, and "
        + "another spanning an attribute list, are stripped entirely - both neighbouring tags "
        + "parse correctly and no depth/terminator confusion occurs (FIX-B)")
    void blockCommentsInsideTagBlockAreStripped() throws IOException {
        JsonObject result = parseFixture("synthetic-block-comment.l5k");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tagNames(tags)).containsExactly("FirstTag", "SecondTag", "ThirdTag");

        JsonObject thirdTag = findTag(tags, "ThirdTag");
        assertThat(thirdTag.get("data_type").getAsString()).isEqualTo("DINT");
        assertThat(thirdTag.get("initial_value").getAsString()).isEqualTo("0");

        JsonObject summary = result.getAsJsonObject("parseSummary");
        assertThat(summary.get("controllerTagCount").getAsInt()).isEqualTo(3);
        assertThat(summary.get("skippedTagLines").getAsInt()).isZero();
        assertThat(summary.get("structurallyClean").getAsBoolean()).isTrue();
    }

    // ---------------------------------------------------------------------------------------
    // Fixture: synthetic-residue.l5k (FIX-C)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("residue: content following a same-line ';' terminator is never silently "
        + "dropped - it is counted and WARNed (structurallyClean flips false), while the tag "
        + "before the terminator and the next line's tag both still parse (FIX-C)")
    void residueAfterSameLineTerminatorIsCountedNotSilent() throws IOException {
        JsonObject result = parseFixture("synthetic-residue.l5k");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        // A (before the terminator) and C (its own physical line) both parse; B (the residue
        // after A's ';' on the same line) is not parsed as its own tag - but it must be LOUD.
        assertThat(tagNames(tags)).containsExactly("A", "C");

        JsonObject summary = result.getAsJsonObject("parseSummary");
        assertThat(summary.get("skippedTagLines").getAsInt()).isEqualTo(1);
        assertThat(summary.get("structurallyClean").getAsBoolean())
            .as("non-zero skippedTagLines must never read as an unqualified success (§5.3)")
            .isFalse();
    }

    // ---------------------------------------------------------------------------------------
    // Vendored public corpus export (in-repo smoke case for a full real export shape:
    // wrapped CONTROLLER attrs, UDT BIT/host members, ST_ROUTINE ' comments, ladder ROUTINEs)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("corpus: the iotrustlab 1756-L72 export parses structurally clean - no rung "
        + "leakage from its ladder/ST routines, all TAG-block tags present")
    void corpusExportParsesClean() throws IOException {
        String content = Files.readString(Path.of(
            "src/test/resources/corpus/ControlLogix-1756L72-fw37-iotrustlab-controller.L5K"));
        JsonObject result = parser.parseContent(content, "iotrustlab-controller.L5K");

        assertThat(result).isNotNull();
        assertThat(result.get("controller").getAsString()).isEqualTo("Controller_PLC");
        assertNoRungArtefacts(result);

        JsonObject summary = result.getAsJsonObject("parseSummary");
        assertThat(summary.get("structurallyClean").getAsBoolean()).isTrue();
        assertThat(summary.get("skippedTagLines").getAsInt()).isZero();
        assertThat(summary.get("controllerTagCount").getAsInt()).isGreaterThan(0);
        assertThat(summary.get("udtDefCount").getAsInt()).isGreaterThan(0);
    }

    // ---------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------

    /** Asserts the D1/D2 corruption signature is absent everywhere in the parsed model: no tag
     *  or member named like a rung type, none typed as a ladder mnemonic. */
    private static void assertNoRungArtefacts(JsonObject result) {
        List<JsonObject> allTags = new ArrayList<>();
        if (result.has("global_tags")) {
            result.getAsJsonArray("global_tags")
                .forEach(t -> allTags.add(t.getAsJsonObject()));
        }
        if (result.has("programs")) {
            for (JsonElement p : result.getAsJsonArray("programs")) {
                p.getAsJsonObject().getAsJsonArray("tags")
                    .forEach(t -> allTags.add(t.getAsJsonObject()));
            }
        }
        for (JsonObject tag : allTags) {
            assertRungFree(tag);
        }
    }

    private static void assertRungFree(JsonObject node) {
        String name = node.get("name").getAsString();
        String dataType = node.get("data_type").getAsString();
        assertThat(RUNG_NAMES).as("tag/member named like a rung type: %s", name).doesNotContain(name);
        assertThat(MNEMONIC_TYPES)
            .as("tag/member '%s' typed as ladder mnemonic %s", name, dataType)
            .doesNotContain(dataType);
        if (node.has("udt_members")) {
            for (JsonElement m : node.getAsJsonArray("udt_members")) {
                assertRungFree(m.getAsJsonObject());
            }
        }
    }

    private static List<String> tagNames(JsonArray tags) {
        List<String> names = new ArrayList<>();
        if (tags != null) {
            for (JsonElement t : tags) {
                names.add(t.getAsJsonObject().get("name").getAsString());
            }
        }
        return names;
    }

    private static JsonObject findTag(JsonArray tags, String name) {
        JsonObject found = findByNameOrNull(tags, name);
        assertThat(found).as("tag '%s' present", name).isNotNull();
        return found;
    }

    private static JsonObject findByName(JsonArray array, String name) {
        JsonObject found = findByNameOrNull(array, name);
        assertThat(found).as("element '%s' present", name).isNotNull();
        return found;
    }

    private static JsonObject findByNameOrNull(JsonArray array, String name) {
        if (array == null) {
            return null;
        }
        for (JsonElement e : array) {
            JsonObject obj = e.getAsJsonObject();
            if (name.equals(obj.get("name").getAsString())) {
                return obj;
            }
        }
        return null;
    }

    private static JsonObject memberByName(JsonArray members, String name) {
        return findByName(members, name);
    }

    private static List<String> memberNames(JsonObject node) {
        List<String> names = new ArrayList<>();
        if (node.has("udt_members")) {
            for (JsonElement m : node.getAsJsonArray("udt_members")) {
                names.add(m.getAsJsonObject().get("name").getAsString());
            }
        }
        return names;
    }

    private static JsonObject findMember(JsonObject node, String name) {
        return findByName(node.getAsJsonArray("udt_members"), name);
    }

    private static boolean isReadOnly(JsonObject node) {
        return node.has("read_only") && node.get("read_only").getAsBoolean();
    }
}
