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

        // =====================================================================================
        // v32+ unsigned atomics and Date/Time-family types (ADDRESSING.md §3.14, defect C6) -
        // before this fix every one of these degraded to OpcUaDataType.String.
        // =====================================================================================

        @Test
        @DisplayName("maps USINT → OpcUaDataType.Byte (C6)")
        void testUsint() {
            assertThat(builder.mapDataType("USINT")).isEqualTo(OpcUaDataType.Byte);
        }

        @Test
        @DisplayName("maps UINT/WORD → OpcUaDataType.UInt16 (C6)")
        void testUint16() {
            assertThat(builder.mapDataType("UINT")).isEqualTo(OpcUaDataType.UInt16);
            assertThat(builder.mapDataType("WORD")).isEqualTo(OpcUaDataType.UInt16);
        }

        @Test
        @DisplayName("maps UDINT/DWORD → OpcUaDataType.UInt32 (C6)")
        void testUint32() {
            assertThat(builder.mapDataType("UDINT")).isEqualTo(OpcUaDataType.UInt32);
            assertThat(builder.mapDataType("DWORD")).isEqualTo(OpcUaDataType.UInt32);
        }

        @Test
        @DisplayName("maps ULINT/LWORD → OpcUaDataType.UInt64 (C6)")
        void testUint64() {
            assertThat(builder.mapDataType("ULINT")).isEqualTo(OpcUaDataType.UInt64);
            assertThat(builder.mapDataType("LWORD")).isEqualTo(OpcUaDataType.UInt64);
        }

        @Test
        @DisplayName("maps DT/LDT/LTIME/TIME → OpcUaDataType.Int64 (C6, INFERRED safe default)")
        void testTimeFamily() {
            assertThat(builder.mapDataType("DT")).isEqualTo(OpcUaDataType.Int64);
            assertThat(builder.mapDataType("LDT")).isEqualTo(OpcUaDataType.Int64);
            assertThat(builder.mapDataType("LTIME")).isEqualTo(OpcUaDataType.Int64);
            assertThat(builder.mapDataType("TIME")).isEqualTo(OpcUaDataType.Int64);
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

        // =====================================================================================
        // v32+ unsigned atomics and Date/Time-family types (ADDRESSING.md §3.14, defect C6)
        // =====================================================================================

        @Test
        @DisplayName("returns UByte(0)/UShort(0)/UInteger(0)/ULong(0) defaults for the new unsigned types (C6)")
        void testUnsignedDefaults() {
            assertThat(builder.getInitialValue(makeTag("T", "USINT"), "USINT"))
                .isEqualTo(org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte(0));
            assertThat(builder.getInitialValue(makeTag("T", "UINT"), "UINT"))
                .isEqualTo(org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ushort(0));
            assertThat(builder.getInitialValue(makeTag("T", "UDINT"), "UDINT"))
                .isEqualTo(org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint(0));
            assertThat(builder.getInitialValue(makeTag("T", "ULINT"), "ULINT"))
                .isEqualTo(org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ulong(0L));
        }

        @Test
        @DisplayName("returns 0L default for DT/LDT/LTIME/TIME (C6)")
        void testTimeFamilyDefaults() {
            assertThat(builder.getInitialValue(makeTag("T", "DT"), "DT")).isEqualTo(0L);
            assertThat(builder.getInitialValue(makeTag("T", "LDT"), "LDT")).isEqualTo(0L);
            assertThat(builder.getInitialValue(makeTag("T", "LTIME"), "LTIME")).isEqualTo(0L);
            assertThat(builder.getInitialValue(makeTag("T", "TIME"), "TIME")).isEqualTo(0L);
        }

        @Test
        @DisplayName("parses a real initial_value for each new unsigned type (C6/C8)")
        void testUnsignedParsedValues() {
            JsonObject usint = makeTag("T", "USINT");
            usint.addProperty("initial_value", "255");
            assertThat(builder.getInitialValue(usint, "USINT"))
                .isEqualTo(org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte(255));

            JsonObject udint = makeTag("T", "UDINT");
            udint.addProperty("initial_value", "4000000000");
            assertThat(builder.getInitialValue(udint, "UDINT"))
                .isEqualTo(org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint(4000000000L));
        }

        @Test
        @DisplayName("garbage initial_value for a new unsigned type falls back to default instead of throwing (B1-style leniency)")
        void testUnsignedGarbageFallsBackToDefault() {
            JsonObject usint = makeTag("T", "USINT");
            usint.addProperty("initial_value", "not-a-number");
            assertThat(builder.getInitialValue(usint, "USINT"))
                .isEqualTo(org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte(0));
        }
    }

    // =========================================================================
    // getInitialValue() lenient parsing — regression tests for defect B1
    // (docs/plans/V10_FIDELITY_PLAN.md). Before the fix, a non-numeric initial_value
    // (notably the "{structure}" sentinel previously emitted by L5XParser.extractValue()
    // for array/UDT/STRING Data elements, and empty strings) caused
    // JsonPrimitive.getAsInt()/getAsFloat()/getAsLong()/getAsDouble() to throw
    // NumberFormatException, aborting the entire address-space build - see
    // plc-dod/item2-addressspace-error.txt and AddressSpaceBuilderIntegrationTest.
    // =========================================================================

    @Nested
    @DisplayName("getInitialValue() lenient parsing (defect B1)")
    class GetInitialValueLenientParsingTests {

        @Test
        @DisplayName("\"{structure}\" for BOOL returns default false")
        void testStructureSentinelBool() {
            JsonObject tag = makeTag("MyBit", "BOOL");
            tag.addProperty("initial_value", "{structure}");
            assertThat(builder.getInitialValue(tag, "BOOL")).isEqualTo(false);
        }

        @Test
        @DisplayName("\"{structure}\" for DINT returns default 0 instead of throwing")
        void testStructureSentinelDint() {
            JsonObject tag = makeTag("MyInt", "DINT");
            tag.addProperty("initial_value", "{structure}");
            assertThat(builder.getInitialValue(tag, "DINT")).isEqualTo(0);
        }

        @Test
        @DisplayName("\"{structure}\" for INT returns default 0 instead of throwing")
        void testStructureSentinelInt16() {
            JsonObject tag = makeTag("MyShort", "INT");
            tag.addProperty("initial_value", "{structure}");
            // Matches the pre-existing no-initial_value default for this type family (Integer 0),
            // not the Short the parsed-value branch produces - a pre-existing quirk, not a
            // regression introduced by the B1 fix.
            assertThat(builder.getInitialValue(tag, "INT")).isEqualTo(0);
        }

        @Test
        @DisplayName("\"{structure}\" for LINT returns default 0L instead of throwing")
        void testStructureSentinelLint() {
            JsonObject tag = makeTag("MyLong", "LINT");
            tag.addProperty("initial_value", "{structure}");
            assertThat(builder.getInitialValue(tag, "LINT")).isEqualTo(0L);
        }

        @Test
        @DisplayName("\"{structure}\" for REAL returns default 0.0f instead of throwing")
        void testStructureSentinelReal() {
            JsonObject tag = makeTag("MyFloat", "REAL");
            tag.addProperty("initial_value", "{structure}");
            assertThat(builder.getInitialValue(tag, "REAL")).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("\"{structure}\" for LREAL returns default 0.0 instead of throwing")
        void testStructureSentinelLreal() {
            JsonObject tag = makeTag("MyDouble", "LREAL");
            tag.addProperty("initial_value", "{structure}");
            assertThat(builder.getInitialValue(tag, "LREAL")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("\"{structure}\" for STRING is returned as-is (getAsString never throws)")
        void testStructureSentinelString() {
            JsonObject tag = makeTag("MyStr", "STRING");
            tag.addProperty("initial_value", "{structure}");
            assertThat(builder.getInitialValue(tag, "STRING")).isEqualTo("{structure}");
        }

        @Test
        @DisplayName("empty string for DINT returns default 0 instead of throwing")
        void testEmptyStringDint() {
            JsonObject tag = makeTag("MyInt", "DINT");
            tag.addProperty("initial_value", "");
            assertThat(builder.getInitialValue(tag, "DINT")).isEqualTo(0);
        }

        @Test
        @DisplayName("empty string for LINT returns default 0L instead of throwing")
        void testEmptyStringLint() {
            JsonObject tag = makeTag("MyLong", "LINT");
            tag.addProperty("initial_value", "");
            assertThat(builder.getInitialValue(tag, "LINT")).isEqualTo(0L);
        }

        @Test
        @DisplayName("empty string for REAL returns default 0.0f instead of throwing")
        void testEmptyStringReal() {
            JsonObject tag = makeTag("MyFloat", "REAL");
            tag.addProperty("initial_value", "");
            assertThat(builder.getInitialValue(tag, "REAL")).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("empty string for LREAL returns default 0.0 instead of throwing")
        void testEmptyStringLreal() {
            JsonObject tag = makeTag("MyDouble", "LREAL");
            tag.addProperty("initial_value", "");
            assertThat(builder.getInitialValue(tag, "LREAL")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("garbage string for DINT returns default 0 instead of throwing")
        void testGarbageStringDint() {
            JsonObject tag = makeTag("MyInt", "DINT");
            tag.addProperty("initial_value", "not-a-number");
            assertThat(builder.getInitialValue(tag, "DINT")).isEqualTo(0);
        }

        @Test
        @DisplayName("garbage string for INT returns default 0 instead of throwing")
        void testGarbageStringInt16() {
            JsonObject tag = makeTag("MyShort", "INT");
            tag.addProperty("initial_value", "not-a-number");
            assertThat(builder.getInitialValue(tag, "INT")).isEqualTo(0);
        }

        @Test
        @DisplayName("garbage string for LINT returns default 0L instead of throwing")
        void testGarbageStringLint() {
            JsonObject tag = makeTag("MyLong", "LINT");
            tag.addProperty("initial_value", "not-a-number");
            assertThat(builder.getInitialValue(tag, "LINT")).isEqualTo(0L);
        }

        @Test
        @DisplayName("garbage string for REAL returns default 0.0f instead of throwing")
        void testGarbageStringReal() {
            JsonObject tag = makeTag("MyFloat", "REAL");
            tag.addProperty("initial_value", "not-a-number");
            assertThat(builder.getInitialValue(tag, "REAL")).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("garbage string for LREAL returns default 0.0 instead of throwing")
        void testGarbageStringLreal() {
            JsonObject tag = makeTag("MyDouble", "LREAL");
            tag.addProperty("initial_value", "not-a-number");
            assertThat(builder.getInitialValue(tag, "LREAL")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("garbage string for BOOL is treated as false (no exception)")
        void testGarbageStringBool() {
            JsonObject tag = makeTag("MyBit", "BOOL");
            tag.addProperty("initial_value", "not-a-boolean");
            assertThat(builder.getInitialValue(tag, "BOOL")).isEqualTo(false);
        }

        @Test
        @DisplayName("valid numeric strings still parse correctly for every numeric type family")
        void testValidValuesStillParseAfterLeniencyChange() {
            JsonObject boolTag = makeTag("MyBit", "BOOL");
            boolTag.addProperty("initial_value", "true");
            assertThat(builder.getInitialValue(boolTag, "BOOL")).isEqualTo(true);

            JsonObject sintTag = makeTag("MySint", "SINT");
            sintTag.addProperty("initial_value", "7");
            assertThat(builder.getInitialValue(sintTag, "SINT")).isEqualTo((short) 7);

            JsonObject dintTag = makeTag("MyDint", "DINT");
            dintTag.addProperty("initial_value", "123");
            assertThat(builder.getInitialValue(dintTag, "DINT")).isEqualTo(123);

            JsonObject lintTag = makeTag("MyLint", "LINT");
            lintTag.addProperty("initial_value", "123456789012");
            assertThat(builder.getInitialValue(lintTag, "LINT")).isEqualTo(123456789012L);

            JsonObject realTag = makeTag("MyReal", "REAL");
            realTag.addProperty("initial_value", "2.5");
            assertThat(builder.getInitialValue(realTag, "REAL")).isEqualTo(2.5f);

            JsonObject lrealTag = makeTag("MyLreal", "LREAL");
            lrealTag.addProperty("initial_value", "2.5");
            assertThat(builder.getInitialValue(lrealTag, "LREAL")).isEqualTo(2.5);

            JsonObject stringTag = makeTag("MyStr", "STRING");
            stringTag.addProperty("initial_value", "hello");
            assertThat(builder.getInitialValue(stringTag, "STRING")).isEqualTo("hello");
        }
    }

    // =========================================================================
    // FIX-15 — arrayElementValue() / arrayElementSource() (array-element initial values)
    // =========================================================================

    @Nested
    @DisplayName("arrayElementValue() / arrayElementSource()")
    class ArrayElementValueTests {

        @Test
        @DisplayName("returns null when the tag has no element_values at all (pre-FIX-15 shape)")
        void testNoElementValues() {
            JsonObject arrayTag = makeTag("RealArray", "REAL");
            assertThat(AddressSpaceBuilder.arrayElementValue(arrayTag, "[2]")).isNull();
        }

        @Test
        @DisplayName("returns the element's decoded value when present, by exact bracket key")
        void testElementValuePresent() {
            JsonObject arrayTag = makeTag("RealArray", "REAL");
            JsonObject elementValues = new JsonObject();
            elementValues.addProperty("[2]", "42.5");
            arrayTag.add("element_values", elementValues);

            assertThat(AddressSpaceBuilder.arrayElementValue(arrayTag, "[2]")).isEqualTo("42.5");
            assertThat(AddressSpaceBuilder.arrayElementValue(arrayTag, "[0]"))
                .as("an index the export didn't cover must return null, not a stale/default value")
                .isNull();
        }

        @Test
        @DisplayName("multi-dim bracket keys ([1,3]) round-trip verbatim")
        void testMultiDimElementValue() {
            JsonObject arrayTag = makeTag("multiArray", "INT");
            JsonObject elementValues = new JsonObject();
            elementValues.addProperty("[1,3]", "194993");
            arrayTag.add("element_values", elementValues);

            assertThat(AddressSpaceBuilder.arrayElementValue(arrayTag, "[1,3]")).isEqualTo("194993");
        }

        @Test
        @DisplayName("arrayElementSource() falls back to the array tag itself when no element "
            + "value is present - preserving the pre-FIX-15 initial_value/type-default behaviour")
        void testSourceFallsBackToArrayTag() {
            JsonObject arrayTag = makeTag("Arr", "DINT");
            JsonObject source = AddressSpaceBuilder.arrayElementSource(arrayTag, "DINT", "[0]");
            assertThat(source).isSameAs(arrayTag);
        }

        @Test
        @DisplayName("arrayElementSource() synthesises a fresh node carrying the element's own "
            + "value, data_type and the array's read_only flag")
        void testSourceSynthesisesElementNode() {
            JsonObject arrayTag = makeTag("RealArray", "REAL");
            arrayTag.addProperty("read_only", true);
            JsonObject elementValues = new JsonObject();
            elementValues.addProperty("[2]", "42.5");
            arrayTag.add("element_values", elementValues);

            JsonObject source = AddressSpaceBuilder.arrayElementSource(arrayTag, "REAL", "[2]");

            assertThat(source).isNotSameAs(arrayTag);
            assertThat(source.get("data_type").getAsString()).isEqualTo("REAL");
            assertThat(source.get("initial_value").getAsString()).isEqualTo("42.5");
            assertThat(source.get("read_only").getAsBoolean()).isTrue();
            assertThat(builder.getInitialValue(source, "REAL")).isEqualTo(42.5f);
        }
    }

    // =========================================================================
    // FIX-15 — bracket() (shared array-element key format)
    // =========================================================================

    @Nested
    @DisplayName("bracket()")
    class BracketTests {

        @Test
        @DisplayName("1-D index formats as [n]")
        void testOneDim() {
            assertThat(AddressSpaceBuilder.bracket(new int[]{2})).isEqualTo("[2]");
        }

        @Test
        @DisplayName("multi-dim indices format comma-separated with no spaces, matching the L5X "
            + "decorated Index attribute verbatim")
        void testMultiDim() {
            assertThat(AddressSpaceBuilder.bracket(new int[]{1, 3})).isEqualTo("[1,3]");
            assertThat(AddressSpaceBuilder.bracket(new int[]{0, 0, 1})).isEqualTo("[0,0,1]");
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
