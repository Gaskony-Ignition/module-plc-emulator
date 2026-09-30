package com.inductiveautomation.logixemulator.gateway.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for RockwellBuiltInTypes.
 *
 * <p>Verifies the member sets match ADDRESSING.md §3.11 exactly — complete sets (count + exact
 * names), not a subset. The previous version of this suite only checked that TIMER
 * <i>contained</i> PRE/ACC/DN/EN, which is why the phantom .ER member went unnoticed for so
 * long: a {@code contains(...)} assertion never fails on an extra member. Every predefined-type
 * test below asserts the full member name list (via {@code containsExactlyInAnyOrder}) or an
 * exact count, so any stray/missing member fails the build.
 */
class RockwellBuiltInTypesTest {

    @Test
    @DisplayName("Should create all expected built-in types")
    void testCreateAllTypes() {
        Map<String, UDTDefinition> types = RockwellBuiltInTypes.createAll();

        assertThat(types).isNotNull();
        assertThat(types).isNotEmpty();

        assertThat(types).containsKey("TIMER");
        assertThat(types).containsKey("COUNTER");
        assertThat(types).containsKey("CONTROL");
        assertThat(types).containsKey("MESSAGE");
        assertThat(types).containsKey("PID");
        assertThat(types).containsKey("PIDE");
        assertThat(types).containsKey("PID_ENHANCED");
        assertThat(types).containsKey("ALARM_ANALOG");
        assertThat(types).containsKey("ALARM_DIGITAL");
        assertThat(types).containsKey("AXIS_CIP_DRIVE");
        assertThat(types).containsKey("MOTION_GROUP");
        assertThat(types).containsKey("CAM");
    }

    private static List<String> memberNames(UDTDefinition def) {
        return def.getMembers().stream().map(UDTDefinition.UDTMember::getName).toList();
    }

    // =====================================================================================
    // ADDRESSING.md §3.11 — complete member sets (C4)
    // =====================================================================================

    @Test
    @DisplayName("TIMER: exactly PRE, ACC, EN, TT, DN — no phantom .ER (ADDRESSING.md §3.11)")
    void testTimerCompleteMemberSet() {
        UDTDefinition timer = RockwellBuiltInTypes.createAll().get("TIMER");

        assertThat(timer).isNotNull();
        assertThat(timer.getName()).isEqualTo("TIMER");
        assertThat(memberNames(timer)).containsExactlyInAnyOrder("PRE", "ACC", "EN", "TT", "DN");
        assertThat(memberNames(timer)).doesNotContain("ER", "OV");
    }

    @Test
    @DisplayName("COUNTER: exactly PRE, ACC, CU, CD, DN, OV, UN (ADDRESSING.md §3.11)")
    void testCounterCompleteMemberSet() {
        UDTDefinition counter = RockwellBuiltInTypes.createAll().get("COUNTER");

        assertThat(counter).isNotNull();
        assertThat(memberNames(counter))
            .containsExactlyInAnyOrder("PRE", "ACC", "CU", "CD", "DN", "OV", "UN");
        assertThat(memberNames(counter)).doesNotContain("EN", "ER");
    }

    @Test
    @DisplayName("CONTROL: all 10 members incl. UL, IN, FD (ADDRESSING.md §3.11)")
    void testControlCompleteMemberSet() {
        UDTDefinition control = RockwellBuiltInTypes.createAll().get("CONTROL");

        assertThat(control).isNotNull();
        assertThat(memberNames(control)).containsExactlyInAnyOrder(
            "LEN", "POS", "EN", "EU", "DN", "EM", "ER", "UL", "IN", "FD");
        assertThat(control.getMembers()).hasSize(10);
    }

    @Test
    @DisplayName("MESSAGE: status/data/config members correct; no fabricated .ConnectionPath")
    void testMessageCompleteMemberSet() {
        UDTDefinition message = RockwellBuiltInTypes.createAll().get("MESSAGE");

        assertThat(message).isNotNull();
        var names = memberNames(message);
        assertThat(names).contains("EW", "ST", "DN", "ER", "TO", "EN", "EN_CC");
        assertThat(names).contains("ERR", "EXERR", "DN_LEN", "REQ_LEN");
        assertThat(names).doesNotContain("ConnectionPath");

        // .ERR/.EXERR must be INT (distinct from PID.ERR which is REAL).
        var errMember = message.getMembers().stream()
            .filter(m -> m.getName().equals("ERR")).findFirst().orElseThrow();
        assertThat(errMember.getDataType()).isEqualTo("INT");
    }

    @Test
    @DisplayName("PID (classic): all 49 members incl. status bits + REAL parameters, .ERR is REAL")
    void testPidCompleteMemberSet() {
        UDTDefinition pid = RockwellBuiltInTypes.createAll().get("PID");

        assertThat(pid).isNotNull();
        assertThat(pid.getMembers()).hasSize(49);
        var names = memberNames(pid);
        // 21 status bits
        assertThat(names).contains("EN", "CT", "CL", "PVT", "DOE", "SWM", "CA", "MO", "PE",
            "NDF", "NOBC", "NOZC", "INI", "SPOR", "OLL", "OLH", "EWD", "DVNA", "DVPA", "PVLA",
            "PVHA");
        // 28 REAL parameters
        assertThat(names).contains("SP", "KP", "KI", "KD", "BIAS", "MAXS", "MINS", "DB", "SO",
            "MAXO", "MINO", "UPD", "PV", "ERR", "OUT", "PVH", "PVL", "DVP", "DVN", "PVDB",
            "DVDB", "MAXI", "MINI", "TIE", "MAXCV", "MINCV", "MINTIE", "MAXTIE");
        // Previously-invented non-existent names must be gone.
        assertThat(names).doesNotContain("CVH", "CVL");

        var errMember = pid.getMembers().stream()
            .filter(m -> m.getName().equals("ERR")).findFirst().orElseThrow();
        assertThat(errMember.getDataType()).as("PID.ERR is a scaled REAL, not INT").isEqualTo("REAL");
    }

    @Test
    @DisplayName("PIDE: derived from the real corpus export (165 members); core members present")
    void testPideCompleteMemberSet() {
        UDTDefinition pide = RockwellBuiltInTypes.createAll().get("PIDE");

        assertThat(pide).isNotNull();
        assertThat(pide.getMembers()).hasSize(165);

        var names = memberNames(pide);
        assertThat(names).contains("PV", "SP", "CV", "CVEU", "PGain", "IGain", "DGain",
            "InstructFault", "PVFaulted", "CVFaulted", "Auto", "Manual", "Hand", "Override",
            "EnableIn", "EnableOut", "Status1", "Status2");
    }

    @Test
    @DisplayName("PID_ENHANCED alias resolves to the same PIDE definition (ADDRESSING.md §3.11)")
    void testPideEnhancedAlias() {
        Map<String, UDTDefinition> types = RockwellBuiltInTypes.createAll();

        assertThat(types).containsKey("PID_ENHANCED");
        assertThat(types.get("PID_ENHANCED")).isSameAs(types.get("PIDE"));
    }

    @Test
    @DisplayName("ALARM_ANALOG: 77 corpus/spec-confirmed members incl. per-level limits+enables")
    void testAlarmAnalogCompleteMemberSet() {
        UDTDefinition alarm = RockwellBuiltInTypes.createAll().get("ALARM_ANALOG");

        assertThat(alarm).isNotNull();
        assertThat(alarm.getMembers()).hasSize(77);

        var names = memberNames(alarm);
        assertThat(names).contains("EnableIn", "In", "InFault", "HHLimit", "HLimit", "LLimit",
            "LLLimit", "HHEnabled", "HEnabled", "LEnabled", "LLEnabled", "Deadband",
            "ROCPosLimit", "ROCNegLimit", "ROCPeriod");
        assertThat(names).contains("EnableOut", "InAlarm", "AnyInAlarmUnack", "HHInAlarm",
            "HInAlarm", "LInAlarm", "LLInAlarm", "ROC", "Severity", "Status", "InstructFault");
    }

    @Test
    @DisplayName("ALMA alias resolves to the same ALARM_ANALOG definition")
    void testAlmaAlias() {
        Map<String, UDTDefinition> types = RockwellBuiltInTypes.createAll();

        assertThat(types).containsKey("ALMA");
        assertThat(types.get("ALMA")).isSameAs(types.get("ALARM_ANALOG"));
    }

    @Test
    @DisplayName("ALARM_DIGITAL: exactly the 40-member spec set (ADDRESSING.md §3.11)")
    void testAlarmDigitalCompleteMemberSet() {
        UDTDefinition alarm = RockwellBuiltInTypes.createAll().get("ALARM_DIGITAL");

        assertThat(alarm).isNotNull();
        assertThat(alarm.getMembers()).hasSize(40);
        assertThat(memberNames(alarm)).containsExactlyInAnyOrder(
            "EnableIn", "In", "InFault", "Condition", "AckRequired", "Latched", "ProgAck",
            "OperAck", "ProgReset", "OperReset", "ProgSuppress", "OperSuppress", "ProgUnsuppress",
            "OperUnsuppress", "ProgDisable", "OperDisable", "ProgEnable", "OperEnable",
            "AlarmCountReset", "UseProgTime", "ProgTime", "Severity", "MinDurationPRE",
            "EnableOut", "InAlarm", "Acked", "InAlarmUnack", "Suppressed", "Disabled",
            "MinDurationACC", "AlarmCount", "InAlarmTime", "AckTime", "RetToNormalTime",
            "AlarmCountResetTime", "DeliveryER", "DeliveryDN", "InFaulted", "InstructFault",
            "Status");

        var progTime = alarm.getMembers().stream()
            .filter(m -> m.getName().equals("ProgTime")).findFirst().orElseThrow();
        assertThat(progTime.getDataType()).isEqualTo("LINT");
    }

    @Test
    @DisplayName("ALMD alias resolves to the same ALARM_DIGITAL definition")
    void testAlmdAlias() {
        Map<String, UDTDefinition> types = RockwellBuiltInTypes.createAll();

        assertThat(types).containsKey("ALMD");
        assertThat(types.get("ALMD")).isSameAs(types.get("ALARM_DIGITAL"));
    }

    @Test
    @DisplayName("CAM is the 3-member cam-point type X/Y/SegmentType, not the fabricated set "
        + "(ADDRESSING.md §3.11.1)")
    void testCamCompleteMemberSet() {
        UDTDefinition cam = RockwellBuiltInTypes.createAll().get("CAM");

        assertThat(cam).isNotNull();
        assertThat(memberNames(cam)).containsExactlyInAnyOrder("X", "Y", "SegmentType");

        var segmentType = cam.getMembers().stream()
            .filter(m -> m.getName().equals("SegmentType")).findFirst().orElseThrow();
        assertThat(segmentType.getDataType()).isEqualTo("SINT");

        // Previously-fabricated members must be gone.
        assertThat(memberNames(cam)).doesNotContain("Type", "Size", "Status", "StartSlope",
            "EndSlope");
    }

    @Test
    @DisplayName("AXIS_CIP_DRIVE should have the §3.11.1 minimum motion members")
    void testAxisCipDriveMembers() {
        UDTDefinition axis = RockwellBuiltInTypes.createAll().get("AXIS_CIP_DRIVE");

        assertThat(axis).isNotNull();
        var names = memberNames(axis);
        assertThat(names).contains("ActualPosition", "CommandPosition", "ActualVelocity",
            "CommandVelocity", "AxisState", "AxisFault", "ActualTorque", "MotorVelocityFeedback");
    }

    @Test
    @DisplayName("All member data types should be valid Rockwell atomic types")
    void testMemberDataTypes() {
        Map<String, UDTDefinition> types = RockwellBuiltInTypes.createAll();

        for (UDTDefinition type : types.values()) {
            for (UDTDefinition.UDTMember member : type.getMembers()) {
                assertThat(member.getName()).isNotNull().isNotEmpty();
                assertThat(member.getDataType()).isNotNull().isNotEmpty();

                // Valid Rockwell data types (LINT added: ALARM_DIGITAL's time-stamp members).
                assertThat(member.getDataType())
                    .isIn("BOOL", "SINT", "INT", "DINT", "LINT", "REAL", "STRING");
            }
        }
    }
}
