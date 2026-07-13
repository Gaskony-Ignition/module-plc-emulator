package com.inductiveautomation.logixemulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-file ground-truth assertions for the v10.1 {@link L5KParser} (L5K-GRAMMAR.md §6.1 /
 * §3.8), gated on the {@code PLC_EMU_PRIVATE_L5K_DIR} environment variable and skipped cleanly
 * when it is unset (CI-safe - the private site exports never enter the repo, and no private tag
 * NAME is hard-coded here; names are read live from the files).
 *
 * <p>Run locally with:
 * <pre>PLC_EMU_PRIVATE_L5K_DIR=~/Downloads/plc-v10-artifacts/real-l5k ./gradlew :gateway:test \
 *     --tests "*.L5KRealFileIntegrationTest"</pre>
 *
 * <p>The acceptance oracle is the INSTANCE-level counts plus structural invariants; the
 * expanded-node totals are deliberately NOT asserted (policy-sensitive - §3.8's LOW-confidence
 * note).
 */
@Tag("l5k")
@EnabledIfEnvironmentVariable(named = "PLC_EMU_PRIVATE_L5K_DIR", matches = ".+")
class L5KRealFileIntegrationTest {

    private static final String FILE_A = "DemoWWTP-sample-b.L5K";
    private static final String FILE_C = "DemoPlant-PLC.L5K";

    /** §5.1/§6.1(4) rung-token blacklist. MESSAGE is deliberately absent - it is a legitimate
     *  predefined type (see L5KParser.LADDER_MNEMONICS Javadoc). */
    private static final Set<String> RUNG_NAMES =
        Set.of("N", "I", "D", "IR", "rR", "R", "rI", "rN", "e", "er", "RC");
    private static final Set<String> MNEMONIC_TYPES = Set.of(
        "XIC", "XIO", "OTE", "OTL", "OTU", "COP", "CPS", "EQ", "EQU", "NEQ", "LBL", "MOV",
        "TON", "TOF", "RTO", "JSR", "MSG", "MUL", "DIV", "ADD", "SUB", "CLR", "NOP", "ONS");

    private L5KParser parser;

    @BeforeEach
    void setUp() {
        parser = new L5KParser();
    }

    private JsonObject parseReal(String fileName) throws IOException {
        Path dir = Path.of(System.getenv("PLC_EMU_PRIVATE_L5K_DIR")
            .replaceFirst("^~", System.getProperty("user.home")));
        Path file = dir.resolve(fileName);
        assertThat(file).as("private L5K file present: %s", file).exists();
        String content = Files.readString(file);
        return parser.parseContent(content, fileName);
    }

    // ---------------------------------------------------------------------------------------
    // File A - DemoWWTP (§6.1 File A rows 1-8)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("File A: 1224 controller tags (572 aliases, 12 arrays -> 102 elements), 3 empty "
        + "programs, 27 UDTs, 137 AOIs, zero rung-token tags, structurally clean")
    void fileAGroundTruth() throws IOException {
        JsonObject result = parseReal(FILE_A);
        assertThat(result).as("file A parses (no hard fail)").isNotNull();

        JsonObject summary = result.getAsJsonObject("parseSummary");

        // A.1 - instance counts
        assertThat(summary.get("controllerTagCount").getAsInt()).isEqualTo(1224);
        assertThat(summary.get("aliasTagCount").getAsInt()).isEqualTo(572);
        assertThat(summary.get("arrayTagCount").getAsInt()).isEqualTo(12);

        // A.2 - 3 programs, each with 0 tags
        JsonArray programs = result.getAsJsonArray("programs");
        assertThat(programs).hasSize(3);
        for (JsonElement p : programs) {
            assertThat(p.getAsJsonObject().getAsJsonArray("tags"))
                .as("program %s has no tags", p.getAsJsonObject().get("name").getAsString())
                .isEmpty();
        }

        // A.3 - type definition counts
        assertThat(summary.get("udtDefCount").getAsInt()).isEqualTo(27);
        assertThat(summary.get("aoiDefCount").getAsInt()).isEqualTo(137);

        // A.4 - zero rung-token tags anywhere in the model
        assertNoRungArtefacts(result);

        // A.5 - loud accounting is clean
        assertThat(summary.get("structurallyClean").getAsBoolean()).isTrue();
        assertThat(summary.get("skippedTagLines").getAsInt()).isZero();

        // A.6 - at least one alias tag exists, carrying its target; NodeId is the bare alias
        // name (the parsed model's "name" field IS the bare identifier the policy emits).
        JsonArray globalTags = result.getAsJsonArray("global_tags");
        JsonObject firstAlias = null;
        for (JsonElement t : globalTags) {
            if (t.getAsJsonObject().has("alias_for")) {
                firstAlias = t.getAsJsonObject();
                break;
            }
        }
        assertThat(firstAlias).isNotNull();
        assertThat(firstAlias.get("name").getAsString()).isNotEmpty();
        assertThat(firstAlias.get("name").getAsString()).doesNotContain(".", "[", ":");
        assertThat(firstAlias.get("alias_for").getAsString()).isNotEmpty();

        // A.7 - array shape: exactly one DINT[2], ten STRING[5] + one STRING[50]; 102 elements
        // in total (2 + 10*5 + 50).
        Map<String, List<String>> arrayDimsByType = new LinkedHashMap<>();
        int totalElements = 0;
        for (JsonElement t : globalTags) {
            JsonObject tag = t.getAsJsonObject();
            if (tag.has("isArray") && tag.get("isArray").getAsBoolean()) {
                String type = tag.get("data_type").getAsString();
                String dims = tag.get("dimensions").getAsString();
                arrayDimsByType.computeIfAbsent(type, k -> new ArrayList<>()).add(dims);
                int product = 1;
                for (String d : dims.split(",")) {
                    product *= Integer.parseInt(d.trim());
                }
                totalElements += product;
            }
        }
        assertThat(arrayDimsByType.get("DINT")).containsExactly("2");
        assertThat(arrayDimsByType.get("STRING"))
            .hasSize(11)
            .contains("50");
        assertThat(arrayDimsByType.get("STRING").stream().filter("5"::equals).count()).isEqualTo(10);
        assertThat(totalElements).isEqualTo(102);

        // STRING array elements each expose .LEN + .DATA[i] through the model's udt_members.
        for (JsonElement t : globalTags) {
            JsonObject tag = t.getAsJsonObject();
            if (tag.has("isArray") && "STRING".equals(tag.get("data_type").getAsString())) {
                assertThat(memberNames(tag)).containsExactly("LEN", "DATA");
            }
        }

        // A.8 - absence: no AOI-local DINT[30] ExternalAccess=None array leaked to controller
        // scope (nor as a bogus rung tag). The AOI-local None population is large and counted.
        for (JsonElement t : globalTags) {
            JsonObject tag = t.getAsJsonObject();
            boolean dint30 = "DINT".equals(tag.get("data_type").getAsString())
                && tag.has("dimensions") && "30".equals(tag.get("dimensions").getAsString());
            assertThat(dint30)
                .as("AOI-local DINT[30] (e.g. FLOW_HOURS) must not surface as a controller tag")
                .isFalse();
        }
        assertThat(summary.get("droppedNoneAccess").getAsInt())
            .as("file A's AOI locals are overwhelmingly ExternalAccess=None (§3.3: 2632)")
            .isGreaterThan(2000);
    }

    // ---------------------------------------------------------------------------------------
    // File C - DemoPlant (§6.1 File C rows 1-7)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("File C: 545 controller tags (0 aliases, 2 arrays-of-UDT 45+40), program tags "
        + "Drives 17 / LoadCells 5 / Valves 7, 11 UDTs, 13 AOIs, zero rung-token tags, clean")
    void fileCGroundTruth() throws IOException {
        JsonObject result = parseReal(FILE_C);
        assertThat(result).as("file C parses (no hard fail)").isNotNull();

        JsonObject summary = result.getAsJsonObject("parseSummary");

        // C.1
        assertThat(summary.get("controllerTagCount").getAsInt()).isEqualTo(545);
        assertThat(summary.get("aliasTagCount").getAsInt()).isZero();
        assertThat(summary.get("arrayTagCount").getAsInt()).isEqualTo(2);

        // C.2 - program tag counts
        JsonObject programCounts = summary.getAsJsonObject("programTagCounts");
        assertThat(programCounts.get("Drives").getAsInt()).isEqualTo(17);
        assertThat(programCounts.get("LoadCells").getAsInt()).isEqualTo(5);
        assertThat(programCounts.get("Valves").getAsInt()).isEqualTo(7);
        int totalProgramTags = 0;
        for (String key : programCounts.keySet()) {
            totalProgramTags += programCounts.get(key).getAsInt();
        }
        assertThat(totalProgramTags).isEqualTo(29);
        assertThat(programCounts.keySet()).hasSize(7);

        // C.3
        assertThat(summary.get("udtDefCount").getAsInt()).isEqualTo(11);
        assertThat(summary.get("aoiDefCount").getAsInt()).isEqualTo(13);

        // C.4
        assertNoRungArtefacts(result);
        assertThat(summary.get("structurallyClean").getAsBoolean()).isTrue();
        assertThat(summary.get("skippedTagLines").getAsInt()).isZero();

        // C.5 - Drives: FBD_TIMER instances expand to their member set; FBD_COMPARE/FBD_ONESHOT
        // are either expanded or counted in unmodelledFbdTypes - never silently dropped.
        JsonObject drives = findByName(result.getAsJsonArray("programs"), "Drives");
        JsonArray drivesTags = drives.getAsJsonArray("tags");
        int fbdTimerCount = 0;
        int otherFbdCount = 0;
        int unexpandedOtherFbd = 0;
        for (JsonElement t : drivesTags) {
            JsonObject tag = t.getAsJsonObject();
            String type = tag.get("data_type").getAsString();
            if ("FBD_TIMER".equals(type)) {
                fbdTimerCount++;
                assertThat(memberNames(tag)).contains("PRE", "ACC", "DN");
            } else if (type.startsWith("FBD_")) {
                otherFbdCount++;
                if (!tag.has("udt_members")) {
                    unexpandedOtherFbd++;
                }
            }
        }
        assertThat(fbdTimerCount).isGreaterThan(0);
        assertThat(otherFbdCount).isGreaterThan(0);
        if (unexpandedOtherFbd > 0) {
            assertThat(summary.get("unmodelledFbdTypes").getAsInt())
                .as("unexpanded FBD instances must be counted, never silently dropped")
                .isGreaterThanOrEqualTo(unexpandedOtherFbd);
        }

        // C.6 - the two controller arrays-of-UDT keep their 45/40 dimensions and expand members.
        List<JsonObject> arrayTags = new ArrayList<>();
        for (JsonElement t : result.getAsJsonArray("global_tags")) {
            JsonObject tag = t.getAsJsonObject();
            if (tag.has("isArray") && tag.get("isArray").getAsBoolean()) {
                arrayTags.add(tag);
            }
        }
        assertThat(arrayTags).hasSize(2);
        List<String> dims = arrayTags.stream().map(t -> t.get("dimensions").getAsString()).toList();
        assertThat(dims).containsExactlyInAnyOrder("45", "40");
        for (JsonObject arrayTag : arrayTags) {
            assertThat(arrayTag.has("udt_members"))
                .as("array-of-UDT %s expands element structures (ADDRESSING.md §3.6)",
                    arrayTag.get("name").getAsString())
                .isTrue();
        }

        // C.7 - AOI-local visibility honoured per member: PlantPAx AOI instances expose
        // Read/Write + Read Only locals (file C: 220 + 79) while None locals are dropped
        // (counted, 255). A blanket hide-all-locals rule would zero the member surface.
        assertThat(summary.get("droppedNoneAccess").getAsInt()).isGreaterThan(0);
        Set<String> aoiNames = new java.util.HashSet<>();
        for (JsonElement a : result.getAsJsonArray("aois")) {
            aoiNames.add(a.getAsJsonObject().get("name").getAsString());
        }
        boolean someAoiInstanceHasMembers = false;
        for (JsonElement t : result.getAsJsonArray("global_tags")) {
            JsonObject tag = t.getAsJsonObject();
            if (aoiNames.contains(tag.get("data_type").getAsString())
                && memberNames(tag).size() > 2) { // more than just EnableIn/EnableOut
                someAoiInstanceHasMembers = true;
                break;
            }
        }
        assertThat(someAoiInstanceHasMembers)
            .as("at least one AOI instance exposes visible params/locals beyond the enable pins")
            .isTrue();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers (kept local; no private tag names hard-coded)
    // ---------------------------------------------------------------------------------------

    private static void assertNoRungArtefacts(JsonObject result) {
        List<JsonObject> allTags = new ArrayList<>();
        if (result.has("global_tags")) {
            result.getAsJsonArray("global_tags").forEach(t -> allTags.add(t.getAsJsonObject()));
        }
        if (result.has("programs")) {
            for (JsonElement p : result.getAsJsonArray("programs")) {
                p.getAsJsonObject().getAsJsonArray("tags")
                    .forEach(t -> allTags.add(t.getAsJsonObject()));
            }
        }
        assertThat(allTags).isNotEmpty();
        for (JsonObject tag : allTags) {
            assertRungFree(tag);
        }
    }

    private static void assertRungFree(JsonObject node) {
        String name = node.get("name").getAsString();
        String dataType = node.get("data_type").getAsString();
        assertThat(RUNG_NAMES).as("tag/member named like a rung token: %s", name).doesNotContain(name);
        assertThat(MNEMONIC_TYPES)
            .as("tag/member '%s' typed as a ladder mnemonic: %s", name, dataType)
            .doesNotContain(dataType);
        if (node.has("udt_members")) {
            for (JsonElement m : node.getAsJsonArray("udt_members")) {
                assertRungFree(m.getAsJsonObject());
            }
        }
    }

    private static JsonObject findByName(JsonArray array, String name) {
        for (JsonElement e : array) {
            JsonObject obj = e.getAsJsonObject();
            if (name.equals(obj.get("name").getAsString())) {
                return obj;
            }
        }
        throw new AssertionError("element '" + name + "' not found");
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
}
