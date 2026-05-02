package com.inductiveautomation.logixemulator.gateway.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for RockwellBuiltInTypes.
 * Verifies all Rockwell built-in type definitions are correctly created.
 */
class RockwellBuiltInTypesTest {

    @Test
    @DisplayName("Should create all expected built-in types")
    void testCreateAllTypes() {
        Map<String, UDTDefinition> types = RockwellBuiltInTypes.createAll();

        assertThat(types).isNotNull();
        assertThat(types).isNotEmpty();

        // Verify expected types exist
        assertThat(types).containsKey("TIMER");
        assertThat(types).containsKey("COUNTER");
        assertThat(types).containsKey("CONTROL");
        assertThat(types).containsKey("MESSAGE");
        assertThat(types).containsKey("PID");
        assertThat(types).containsKey("PIDE");
        assertThat(types).containsKey("ALARM_ANALOG");
        assertThat(types).containsKey("ALARM_DIGITAL");
        assertThat(types).containsKey("AXIS_CIP_DRIVE");
        assertThat(types).containsKey("MOTION_GROUP");
    }

    @Test
    @DisplayName("TIMER should have correct members")
    void testTimerMembers() {
        Map<String, UDTDefinition> types = RockwellBuiltInTypes.createAll();
        UDTDefinition timer = types.get("TIMER");

        assertThat(timer).isNotNull();
        assertThat(timer.getName()).isEqualTo("TIMER");
        assertThat(timer.getMembers()).isNotEmpty();

        // TIMER should have PRE, ACC, DN, EN, TT, ER
        var memberNames = timer.getMembers().stream()
            .map(UDTDefinition.UDTMember::getName)
            .toList();

        assertThat(memberNames).contains("PRE", "ACC", "DN", "EN", "TT");
    }

    @Test
    @DisplayName("COUNTER should have correct members")
    void testCounterMembers() {
        Map<String, UDTDefinition> types = RockwellBuiltInTypes.createAll();
        UDTDefinition counter = types.get("COUNTER");

        assertThat(counter).isNotNull();
        assertThat(counter.getName()).isEqualTo("COUNTER");

        var memberNames = counter.getMembers().stream()
            .map(UDTDefinition.UDTMember::getName)
            .toList();

        assertThat(memberNames).contains("PRE", "ACC", "CU", "CD", "DN");
    }

    @Test
    @DisplayName("PIDE should have process control members")
    void testPideMembers() {
        Map<String, UDTDefinition> types = RockwellBuiltInTypes.createAll();
        UDTDefinition pide = types.get("PIDE");

        assertThat(pide).isNotNull();

        var memberNames = pide.getMembers().stream()
            .map(UDTDefinition.UDTMember::getName)
            .toList();

        // PIDE should have PV, SP, CV, Kp, Ki, Kd and alarm members
        assertThat(memberNames).contains("PV", "SP", "CV", "Kp", "Ki", "Kd");
        assertThat(memberNames).contains("PVHHAlarm", "PVHAlarm", "PVLAlarm", "PVLLAlarm");
    }

    @Test
    @DisplayName("AXIS_CIP_DRIVE should have motion members")
    void testAxisCipDriveMembers() {
        Map<String, UDTDefinition> types = RockwellBuiltInTypes.createAll();
        UDTDefinition axis = types.get("AXIS_CIP_DRIVE");

        assertThat(axis).isNotNull();

        var memberNames = axis.getMembers().stream()
            .map(UDTDefinition.UDTMember::getName)
            .toList();

        assertThat(memberNames).contains("ActualPosition", "CommandPosition",
            "ActualVelocity", "CommandVelocity", "AxisState", "AxisFault");
    }

    @Test
    @DisplayName("ALARM_ANALOG should have alarm members")
    void testAlarmAnalogMembers() {
        Map<String, UDTDefinition> types = RockwellBuiltInTypes.createAll();
        UDTDefinition alarm = types.get("ALARM_ANALOG");

        assertThat(alarm).isNotNull();

        var memberNames = alarm.getMembers().stream()
            .map(UDTDefinition.UDTMember::getName)
            .toList();

        assertThat(memberNames).contains("In", "HHLimit", "HLimit", "LLimit", "LLLimit",
            "HHAlarm", "HAlarm", "LAlarm", "LLAlarm");
    }

    @Test
    @DisplayName("ALMA alias should exist for ALARM_ANALOG")
    void testAlmaAlias() {
        Map<String, UDTDefinition> types = RockwellBuiltInTypes.createAll();

        assertThat(types).containsKey("ALMA");
        UDTDefinition alma = types.get("ALMA");
        UDTDefinition alarmAnalog = types.get("ALARM_ANALOG");

        // Both should be the same object
        assertThat(alma).isSameAs(alarmAnalog);
    }

    @Test
    @DisplayName("All member data types should be valid")
    void testMemberDataTypes() {
        Map<String, UDTDefinition> types = RockwellBuiltInTypes.createAll();

        for (UDTDefinition type : types.values()) {
            for (UDTDefinition.UDTMember member : type.getMembers()) {
                assertThat(member.getName()).isNotNull().isNotEmpty();
                assertThat(member.getDataType()).isNotNull().isNotEmpty();

                // Valid Rockwell data types
                assertThat(member.getDataType()).isIn("BOOL", "SINT", "INT", "DINT", "REAL", "STRING");
            }
        }
    }
}
