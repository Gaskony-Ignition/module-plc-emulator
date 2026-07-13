package com.inductiveautomation.logixemulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIX-A swap-fidelity contract test: an Add-On Instruction with an {@code InOut} parameter,
 * defined identically in L5X and L5K, must expand to the IDENTICAL instance member set through
 * {@link L5XParser} and {@link L5KParser} respectively.
 *
 * <p>This is the "cross-format equivalence" test called for by the v10.1 L5K parser review
 * (FIX-A): the review found that {@link L5XParser#parse} added every AOI {@code <Parameter>} -
 * Input, Output, AND InOut - to an instance's expanded members, while the (correct) new
 * {@link L5KParser} already excludes {@code Usage := InOut} per L5K-GRAMMAR.md §2.7/§3.2(3). An
 * InOut parameter is a *reference* to the caller's tag, not backing storage in the AOI instance
 * (ADDRESSING.md §3.3), so the real CIP driver never exposes it as an instance member - L5K was
 * right, L5X was wrong. The vendored corpus's only same-controller L5X+L5K pair
 * ({@code ControlLogix-1756L72-fw37-iotrustlab-controller.[L5X|L5K]}) contains zero AOI
 * definitions in either file (verified: {@code AddOnInstructionDefinitions} is empty/self-closing
 * in the L5X, and no {@code ADD_ON_INSTRUCTION_DEFINITION} keyword appears in the L5K), so per the
 * task's own fallback this test constructs a minimal SYNTHETIC L5X+L5K pair defining the same AOI.
 *
 * <p><b>Before FIX-A</b> this test fails: the L5X-parsed instance carries an extra
 * {@code PassThru} member the L5K-parsed instance does not. <b>After FIX-A</b> the two member
 * name sets are identical.
 */
class AoiCrossFormatEquivalenceTest {

    private static final String L5X = """
        <?xml version="1.0"?>
        <RSLogix5000Content>
            <Controller Name="XFormatTest" ProcessorType="Test">
                <AddOnInstructionDefinitions>
                    <AddOnInstructionDefinition Name="My_Aoi" Revision="1.0">
                        <Parameters>
                            <Parameter Name="EnableIn" TagType="Base" DataType="BOOL" Usage="Input" ExternalAccess="Read Only"/>
                            <Parameter Name="EnableOut" TagType="Base" DataType="BOOL" Usage="Output" ExternalAccess="Read Only"/>
                            <Parameter Name="InVal" TagType="Base" DataType="DINT" Usage="Input" ExternalAccess="Read/Write"/>
                            <Parameter Name="PassThru" TagType="Base" DataType="DINT" Usage="InOut" ExternalAccess="Read/Write"/>
                            <Parameter Name="OutVal" TagType="Base" DataType="DINT" Usage="Output" ExternalAccess="Read/Write"/>
                        </Parameters>
                        <LocalTags>
                            <LocalTag Name="Local1" DataType="DINT" ExternalAccess="Read/Write"/>
                        </LocalTags>
                    </AddOnInstructionDefinition>
                </AddOnInstructionDefinitions>
                <Tags>
                    <Tag Name="Inst" DataType="My_Aoi" ExternalAccess="Read/Write"/>
                </Tags>
            </Controller>
        </RSLogix5000Content>
        """;

    private static final String L5K = """
        CONTROLLER XFormatTest (Description := "cross-format equivalence fixture")
        \tADD_ON_INSTRUCTION_DEFINITION My_Aoi (Revision := "1.0")
        \t\tPARAMETERS
        \t\t\tEnableIn : BOOL (Usage := Input, ExternalAccess := Read Only);
        \t\t\tEnableOut : BOOL (Usage := Output, ExternalAccess := Read Only);
        \t\t\tInVal : DINT (Usage := Input, ExternalAccess := Read/Write);
        \t\t\tPassThru : DINT (Usage := InOut, ExternalAccess := Read/Write);
        \t\t\tOutVal : DINT (Usage := Output, ExternalAccess := Read/Write);
        \t\tEND_PARAMETERS
        \t\tLOCAL_TAGS
        \t\t\tLocal1 : DINT (ExternalAccess := Read/Write);
        \t\tEND_LOCAL_TAGS
        \tEND_ADD_ON_INSTRUCTION_DEFINITION
        \tTAG
        \t\tInst : My_Aoi;
        \tEND_TAG
        END_CONTROLLER
        """;

    @Test
    @DisplayName("FIX-A: the same AOI (with an InOut parameter) expands to the IDENTICAL instance "
        + "member set whether parsed from L5X or L5K - the swap-fidelity contract made executable")
    void inOutExclusionIsIdenticalAcrossFormats() {
        JsonObject l5xResult = new L5XParser().parseContent(L5X, "xformat.l5x");
        JsonObject l5kResult = new L5KParser().parseContent(L5K, "xformat.l5k");

        assertThat(l5xResult).as("L5X parses").isNotNull();
        assertThat(l5kResult).as("L5K parses").isNotNull();

        JsonObject l5xInstance = findTag(l5xResult.getAsJsonArray("global_tags"), "Inst");
        JsonObject l5kInstance = findTag(l5kResult.getAsJsonArray("global_tags"), "Inst");

        List<String> l5xMembers = memberNames(l5xInstance);
        List<String> l5kMembers = memberNames(l5kInstance);

        // The actual swap-fidelity assertion: both formats agree on the canonical member set.
        assertThat(l5xMembers)
            .as("L5X and L5K must expand the same AOI instance to the same member set")
            .containsExactlyInAnyOrderElementsOf(l5kMembers);

        // And that set is exactly the expected one: EnableIn/EnableOut + Input/Output params +
        // the visible local - PassThru (InOut) excluded from BOTH.
        assertThat(l5xMembers).containsExactlyInAnyOrder(
            "EnableIn", "EnableOut", "InVal", "OutVal", "Local1");
        assertThat(l5xMembers).doesNotContain("PassThru");
        assertThat(l5kMembers).doesNotContain("PassThru");
    }

    private static JsonObject findTag(JsonArray tags, String name) {
        for (JsonElement t : tags) {
            JsonObject obj = t.getAsJsonObject();
            if (name.equals(obj.get("name").getAsString())) {
                return obj;
            }
        }
        throw new AssertionError("tag '" + name + "' not found");
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
