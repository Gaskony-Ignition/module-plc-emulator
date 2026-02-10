package com.inductiveautomation.logixemulator.gateway.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;
import static com.inductiveautomation.logixemulator.gateway.parser.DataTypeUtils.*;

/**
 * Unit tests for DataTypeUtils.
 * Tests data type normalization and default value generation.
 */
class DataTypeUtilsTest {

    // ========== Data Type Normalization Tests ==========

    @Test
    @DisplayName("Should normalize BOOL types")
    void testNormalizeBool() {
        assertThat(normalizeDataType("BOOL")).isEqualTo("BOOL");
        assertThat(normalizeDataType("BIT")).isEqualTo("BOOL");
    }

    @Test
    @DisplayName("Should normalize INT types")
    void testNormalizeInt() {
        assertThat(normalizeDataType("INT")).isEqualTo("INT");
        assertThat(normalizeDataType("INT2")).isEqualTo("INT");
    }

    @Test
    @DisplayName("Should normalize SINT types")
    void testNormalizeSint() {
        assertThat(normalizeDataType("SINT")).isEqualTo("SINT");
        assertThat(normalizeDataType("INT1")).isEqualTo("SINT");
        assertThat(normalizeDataType("BYTE")).isEqualTo("SINT");
    }

    @Test
    @DisplayName("Should normalize DINT types")
    void testNormalizeDint() {
        assertThat(normalizeDataType("DINT")).isEqualTo("DINT");
        assertThat(normalizeDataType("INT4")).isEqualTo("DINT");
    }

    @Test
    @DisplayName("Should normalize REAL types")
    void testNormalizeReal() {
        assertThat(normalizeDataType("REAL")).isEqualTo("REAL");
        assertThat(normalizeDataType("FLOAT")).isEqualTo("REAL");
        assertThat(normalizeDataType("FLOAT4")).isEqualTo("REAL");
    }

    @Test
    @DisplayName("Should normalize STRING types")
    void testNormalizeString() {
        assertThat(normalizeDataType("STRING")).isEqualTo("STRING");
    }

    @Test
    @DisplayName("Should normalize LINT types")
    void testNormalizeLint() {
        assertThat(normalizeDataType("LINT")).isEqualTo("LINT");
    }

    @Test
    @DisplayName("Should normalize LREAL types")
    void testNormalizeLreal() {
        assertThat(normalizeDataType("LREAL")).isEqualTo("LREAL");
    }

    @Test
    @DisplayName("Should return original for unknown types")
    void testUnknownTypes() {
        // Unknown types are returned as-is (for UDTs)
        assertThat(normalizeDataType("CustomType")).isEqualTo("CustomType");
        assertThat(normalizeDataType("MyUDT")).isEqualTo("MyUDT");
    }

    @Test
    @DisplayName("Should handle STRING with length specifier")
    void testStringWithLength() {
        assertThat(normalizeDataType("STRING(82)")).isEqualTo("STRING");
        assertThat(normalizeDataType("STRING(255)")).isEqualTo("STRING");
    }

    // ========== Default Value Tests ==========

    @Test
    @DisplayName("Should return correct default for BOOL")
    void testDefaultBool() {
        assertThat(getDefaultValue("BOOL")).isEqualTo("false");
        assertThat(getDefaultValue("BOOLEAN")).isEqualTo("false");
    }

    @Test
    @DisplayName("Should return correct default for INT types")
    void testDefaultInt() {
        assertThat(getDefaultValue("INT")).isEqualTo("0");
        assertThat(getDefaultValue("SINT")).isEqualTo("0");
        assertThat(getDefaultValue("DINT")).isEqualTo("0");
        assertThat(getDefaultValue("LINT")).isEqualTo("0");
    }

    @Test
    @DisplayName("Should return correct default for REAL types")
    void testDefaultReal() {
        assertThat(getDefaultValue("REAL")).isEqualTo("0.0");
        assertThat(getDefaultValue("FLOAT4")).isEqualTo("0.0");
    }

    @Test
    @DisplayName("Should return correct default for STRING")
    void testDefaultString() {
        assertThat(getDefaultValue("STRING")).isEqualTo("");
    }

    @Test
    @DisplayName("Should return 0 for unknown types")
    void testDefaultUnknown() {
        assertThat(getDefaultValue("CustomType")).isEqualTo("0");
        assertThat(getDefaultValue("SomeUDT")).isEqualTo("0");
    }

    // ========== Structured Types Tests ==========

    @Test
    @DisplayName("Should preserve structured type names")
    void testStructuredTypes() {
        assertThat(normalizeDataType("TIMER")).isEqualTo("TIMER");
        assertThat(normalizeDataType("COUNTER")).isEqualTo("COUNTER");
        assertThat(normalizeDataType("CONTROL")).isEqualTo("CONTROL");
        assertThat(normalizeDataType("MESSAGE")).isEqualTo("MESSAGE");
    }

    @Test
    @DisplayName("Should preserve process control types")
    void testProcessControlTypes() {
        assertThat(normalizeDataType("PID")).isEqualTo("PID");
        assertThat(normalizeDataType("PIDE")).isEqualTo("PIDE");
        assertThat(normalizeDataType("ALARM_ANALOG")).isEqualTo("ALARM_ANALOG");
        assertThat(normalizeDataType("ALMA")).isEqualTo("ALARM_ANALOG");
        assertThat(normalizeDataType("ALARM_DIGITAL")).isEqualTo("ALARM_DIGITAL");
        assertThat(normalizeDataType("ALMD")).isEqualTo("ALARM_DIGITAL");
    }

    @Test
    @DisplayName("Should preserve motion control types")
    void testMotionControlTypes() {
        assertThat(normalizeDataType("AXIS_CIP_DRIVE")).isEqualTo("AXIS_CIP_DRIVE");
        assertThat(normalizeDataType("AXIS_VIRTUAL")).isEqualTo("AXIS_VIRTUAL");
        assertThat(normalizeDataType("MOTION_GROUP")).isEqualTo("MOTION_GROUP");
    }

    @Test
    @DisplayName("Should handle case insensitivity")
    void testCaseInsensitivity() {
        assertThat(normalizeDataType("bool")).isEqualTo("BOOL");
        assertThat(normalizeDataType("Bool")).isEqualTo("BOOL");
        assertThat(normalizeDataType("dint")).isEqualTo("DINT");
        assertThat(normalizeDataType("Dint")).isEqualTo("DINT");
        assertThat(normalizeDataType("real")).isEqualTo("REAL");
        assertThat(normalizeDataType("REAL")).isEqualTo("REAL");
    }
}
