package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.eclipse.milo.opcua.stack.core.OpcUaDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for AddressSpaceBuilder pure-logic helpers.
 *
 * Tests in this file cover:
 * <ul>
 *   <li>{@code countTotalTags()} — JSON tag counting logic</li>
 *   <li>{@code mapDataType()} — PLC→OPC-UA type mapping</li>
 *   <li>{@code getInitialValue()} — default and parsed initial values</li>
 * </ul>
 *
 * All three helpers are package-private and require no OPC-UA runtime, so these tests
 * run without any Ignition infrastructure.
 *
 * Integration-level tests for {@code buildAddressSpace()} (which require a live OPC-UA
 * server or deep mock infrastructure) are tracked in Future Work.
 */
class AddressSpaceBuilderTest {

    AddressSpaceBuilder builder;

    @BeforeEach
    void setUp() {
        // nodeAdder is unused for pure-logic tests
        builder = new AddressSpaceBuilder(
            node -> {},
            "TestDevice",
            LoggerFactory.getLogger(AddressSpaceBuilderTest.class)
        );
    }

    // =========================================================================
    // countTotalTags
    // =========================================================================

    @Nested
    @DisplayName("countTotalTags()")
    class CountTotalTagsTests {

        @Test
        @DisplayName("returns 0 for empty JsonObject")
        void testEmptyObject() {
            assertThat(AddressSpaceBuilder.countTotalTags(new JsonObject())).isEqualTo(0);
        }

        @Test
        @DisplayName("counts global_tags correctly")
        void testGlobalTagsOnly() {
            JsonObject plcData = new JsonObject();
            JsonArray globalTags = new JsonArray();
            globalTags.add(makeTag("Tag1", "BOOL"));
            globalTags.add(makeTag("Tag2", "DINT"));
            globalTags.add(makeTag("Tag3", "REAL"));
            plcData.add("global_tags", globalTags);

            assertThat(AddressSpaceBuilder.countTotalTags(plcData)).isEqualTo(3);
        }

        @Test
        @DisplayName("counts program tags from all programs")
        void testProgramTagsOnly() {
            JsonObject plcData = new JsonObject();
            JsonArray programs = new JsonArray();

            JsonObject prog1 = new JsonObject();
            prog1.addProperty("name", "MainProgram");
            JsonArray prog1Tags = new JsonArray();
            prog1Tags.add(makeTag("Counter", "DINT"));
            prog1Tags.add(makeTag("Enable", "BOOL"));
            prog1.add("tags", prog1Tags);

            JsonObject prog2 = new JsonObject();
            prog2.addProperty("name", "SubProgram");
            JsonArray prog2Tags = new JsonArray();
            prog2Tags.add(makeTag("LocalVar", "REAL"));
            prog2.add("tags", prog2Tags);

            programs.add(prog1);
            programs.add(prog2);
            plcData.add("programs", programs);

            assertThat(AddressSpaceBuilder.countTotalTags(plcData)).isEqualTo(3);
        }

        @Test
        @DisplayName("sums global_tags and program tags together")
        void testGlobalAndProgramTags() {
            JsonObject plcData = new JsonObject();

            JsonArray globalTags = new JsonArray();
            globalTags.add(makeTag("G1", "BOOL"));
            globalTags.add(makeTag("G2", "DINT"));
            plcData.add("global_tags", globalTags);

            JsonArray programs = new JsonArray();
            JsonObject prog = new JsonObject();
            prog.addProperty("name", "Main");
            JsonArray progTags = new JsonArray();
            progTags.add(makeTag("P1", "REAL"));
            prog.add("tags", progTags);
            programs.add(prog);
            plcData.add("programs", programs);

            assertThat(AddressSpaceBuilder.countTotalTags(plcData)).isEqualTo(3);
        }

        @Test
        @DisplayName("returns 0 for program with no 'tags' field")
        void testProgramWithNoTagsField() {
            JsonObject plcData = new JsonObject();
            JsonArray programs = new JsonArray();
            JsonObject prog = new JsonObject();
            prog.addProperty("name", "EmptyProgram");
            // no "tags" field
            programs.add(prog);
            plcData.add("programs", programs);

            assertThat(AddressSpaceBuilder.countTotalTags(plcData)).isEqualTo(0);
        }

        @Test
        @DisplayName("returns 0 for empty global_tags array")
        void testEmptyGlobalTagsArray() {
            JsonObject plcData = new JsonObject();
            plcData.add("global_tags", new JsonArray());

            assertThat(AddressSpaceBuilder.countTotalTags(plcData)).isEqualTo(0);
        }
    }

    // =========================================================================
    // mapDataType
    // =========================================================================

    @Nested
    @DisplayName("mapDataType()")
    class MapDataTypeTests {

        @Test
        @DisplayName("maps BOOL → OpcUaDataType.Boolean")
        void testBool() {
            assertThat(builder.mapDataType("BOOL")).isEqualTo(OpcUaDataType.Boolean);
            assertThat(builder.mapDataType("BOOLEAN")).isEqualTo(OpcUaDataType.Boolean);
        }

        @Test
        @DisplayName("maps SINT/BYTE/INT1 → OpcUaDataType.SByte")
        void testSByte() {
            assertThat(builder.mapDataType("SINT")).isEqualTo(OpcUaDataType.SByte);
            assertThat(builder.mapDataType("BYTE")).isEqualTo(OpcUaDataType.SByte);
            assertThat(builder.mapDataType("INT1")).isEqualTo(OpcUaDataType.SByte);
        }

        @Test
        @DisplayName("maps INT/INT2 → OpcUaDataType.Int16")
        void testInt16() {
            assertThat(builder.mapDataType("INT")).isEqualTo(OpcUaDataType.Int16);
            assertThat(builder.mapDataType("INT2")).isEqualTo(OpcUaDataType.Int16);
        }

        @Test
        @DisplayName("maps DINT/INT4 → OpcUaDataType.Int32")
        void testInt32() {
            assertThat(builder.mapDataType("DINT")).isEqualTo(OpcUaDataType.Int32);
            assertThat(builder.mapDataType("INT4")).isEqualTo(OpcUaDataType.Int32);
        }

        @Test
        @DisplayName("maps LINT/INT8 → OpcUaDataType.Int64")
        void testInt64() {
            assertThat(builder.mapDataType("LINT")).isEqualTo(OpcUaDataType.Int64);
            assertThat(builder.mapDataType("INT8")).isEqualTo(OpcUaDataType.Int64);
        }

        @Test
        @DisplayName("maps REAL/FLOAT/FLOAT4 → OpcUaDataType.Float")
        void testFloat() {
            assertThat(builder.mapDataType("REAL")).isEqualTo(OpcUaDataType.Float);
            assertThat(builder.mapDataType("FLOAT")).isEqualTo(OpcUaDataType.Float);
            assertThat(builder.mapDataType("FLOAT4")).isEqualTo(OpcUaDataType.Float);
        }

        @Test
        @DisplayName("maps LREAL/DOUBLE/FLOAT8 → OpcUaDataType.Double")
        void testDouble() {
            assertThat(builder.mapDataType("LREAL")).isEqualTo(OpcUaDataType.Double);
            assertThat(builder.mapDataType("DOUBLE")).isEqualTo(OpcUaDataType.Double);
            assertThat(builder.mapDataType("FLOAT8")).isEqualTo(OpcUaDataType.Double);
        }

        @Test
        @DisplayName("maps STRING → OpcUaDataType.String")
        void testString() {
            assertThat(builder.mapDataType("STRING")).isEqualTo(OpcUaDataType.String);
        }

        @Test
        @DisplayName("maps unknown type → OpcUaDataType.String (safe default)")
        void testUnknown() {
            assertThat(builder.mapDataType("UNDEFINED_TYPE")).isEqualTo(OpcUaDataType.String);
        }

        @Test
        @DisplayName("is case-insensitive (lowercase input works)")
        void testCaseInsensitive() {
            assertThat(builder.mapDataType("bool")).isEqualTo(OpcUaDataType.Boolean);
            assertThat(builder.mapDataType("dint")).isEqualTo(OpcUaDataType.Int32);
            assertThat(builder.mapDataType("real")).isEqualTo(OpcUaDataType.Float);
        }
    }

    // =========================================================================
    // getInitialValue
    // =========================================================================

    @Nested
    @DisplayName("getInitialValue()")
    class GetInitialValueTests {

        @Test
        @DisplayName("returns false (Boolean) as default for BOOL with no initial_value")
        void testDefaultBool() {
            JsonObject tag = makeTag("MyBit", "BOOL");
            assertThat(builder.getInitialValue(tag, "BOOL")).isEqualTo(false);
        }

        @Test
        @DisplayName("returns 0 (Integer) as default for DINT with no initial_value")
        void testDefaultDint() {
            JsonObject tag = makeTag("MyInt", "DINT");
            assertThat(builder.getInitialValue(tag, "DINT")).isEqualTo(0);
        }

        @Test
        @DisplayName("returns 0L (Long) as default for LINT with no initial_value")
        void testDefaultLint() {
            JsonObject tag = makeTag("MyLong", "LINT");
            assertThat(builder.getInitialValue(tag, "LINT")).isEqualTo(0L);
        }

        @Test
        @DisplayName("returns 0.0f (Float) as default for REAL with no initial_value")
        void testDefaultReal() {
            JsonObject tag = makeTag("MyFloat", "REAL");
            assertThat(builder.getInitialValue(tag, "REAL")).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("returns 0.0 (Double) as default for LREAL with no initial_value")
        void testDefaultLreal() {
            JsonObject tag = makeTag("MyDouble", "LREAL");
            assertThat(builder.getInitialValue(tag, "LREAL")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("returns empty string as default for STRING with no initial_value")
        void testDefaultString() {
            JsonObject tag = makeTag("MyStr", "STRING");
            assertThat(builder.getInitialValue(tag, "STRING")).isEqualTo("");
        }

        @Test
        @DisplayName("parses initial_value=true for BOOL")
        void testParsedBool() {
            JsonObject tag = makeTag("MyBit", "BOOL");
            tag.addProperty("initial_value", true);
            assertThat(builder.getInitialValue(tag, "BOOL")).isEqualTo(true);
        }

        @Test
        @DisplayName("parses initial_value=42 for DINT")
        void testParsedDint() {
            JsonObject tag = makeTag("MyInt", "DINT");
            tag.addProperty("initial_value", 42);
            assertThat(builder.getInitialValue(tag, "DINT")).isEqualTo(42);
        }

        @Test
        @DisplayName("parses initial_value=3.14 for REAL (as Float)")
        void testParsedReal() {
            JsonObject tag = makeTag("MyFloat", "REAL");
            tag.addProperty("initial_value", 3.14f);
            Object result = builder.getInitialValue(tag, "REAL");
            assertThat(result).isInstanceOf(Float.class);
            assertThat((Float) result).isCloseTo(3.14f, within(0.001f));
        }

        @Test
        @DisplayName("parses initial_value=\"hello\" for STRING")
        void testParsedString() {
            JsonObject tag = makeTag("MyStr", "STRING");
            tag.addProperty("initial_value", "hello");
            assertThat(builder.getInitialValue(tag, "STRING")).isEqualTo("hello");
        }

        @Test
        @DisplayName("returns default when initial_value is JsonNull")
        void testJsonNullInitialValue() {
            JsonObject tag = makeTag("MyTag", "DINT");
            tag.add("initial_value", com.google.gson.JsonNull.INSTANCE);
            assertThat(builder.getInitialValue(tag, "DINT")).isEqualTo(0);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static JsonObject makeTag(String name, String dataType) {
        JsonObject tag = new JsonObject();
        tag.addProperty("name", name);
        tag.addProperty("data_type", dataType);
        return tag;
    }
}
