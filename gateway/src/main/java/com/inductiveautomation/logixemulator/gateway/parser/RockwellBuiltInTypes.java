package com.inductiveautomation.logixemulator.gateway.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Factory for Rockwell Allen-Bradley built-in structured type definitions.
 * These are predefined types that exist in all Rockwell PLCs and need
 * UDT-style expansion when used as tag types.
 *
 * Covers 22 predefined types across:
 * - Basic types: TIMER, COUNTER, CONTROL, MESSAGE
 * - Process control: PID, PIDE, ALARM_ANALOG, ALARM_DIGITAL
 * - Motion control: AXIS_CIP_DRIVE, AXIS_VIRTUAL, AXIS_SERVO_DRIVE, MOTION_GROUP, CAM, CAM_PROFILE
 * - Specialty: COORDINATE_SYSTEM, PHASE, EQUIPMENT_SEQUENCE, FBD_TIMER, FBD_COUNTER
 */
public final class RockwellBuiltInTypes {

    private static final Logger logger = LoggerFactory.getLogger(RockwellBuiltInTypes.class);

    private static final Map<String, UDTDefinition> BUILT_IN_TYPES;

    static {
        Map<String, UDTDefinition> types = new HashMap<>();
        addBasicTypes(types);
        addProcessControlTypes(types);
        addMotionControlTypes(types);
        addSpecialtyTypes(types);
        BUILT_IN_TYPES = Collections.unmodifiableMap(types);
        logger.debug("Created {} built-in Rockwell type definitions", BUILT_IN_TYPES.size());
    }

    private RockwellBuiltInTypes() {
        // Utility class - prevent instantiation
    }

    /**
     * Get all built-in Rockwell structured type definitions.
     * Returns a cached, immutable map.
     * @return Map of type name to UDT definition
     */
    public static Map<String, UDTDefinition> createAll() {
        return BUILT_IN_TYPES;
    }

    private static void addBasicTypes(Map<String, UDTDefinition> types) {
        // TIMER structure
        UDTDefinition timer = new UDTDefinition("TIMER");
        timer.addMember("PRE", "DINT");
        timer.addMember("ACC", "DINT");
        timer.addMember("DN", "BOOL");
        timer.addMember("EN", "BOOL");
        timer.addMember("TT", "BOOL");
        timer.addMember("ER", "BOOL");
        types.put("TIMER", timer);

        // COUNTER structure
        UDTDefinition counter = new UDTDefinition("COUNTER");
        counter.addMember("PRE", "DINT");
        counter.addMember("ACC", "DINT");
        counter.addMember("CU", "BOOL");
        counter.addMember("CD", "BOOL");
        counter.addMember("DN", "BOOL");
        counter.addMember("OV", "BOOL");
        counter.addMember("UN", "BOOL");
        types.put("COUNTER", counter);

        // CONTROL structure
        UDTDefinition control = new UDTDefinition("CONTROL");
        control.addMember("LEN", "DINT");
        control.addMember("POS", "DINT");
        control.addMember("EN", "BOOL");
        control.addMember("EU", "BOOL");
        control.addMember("DN", "BOOL");
        control.addMember("EM", "BOOL");
        control.addMember("ER", "BOOL");
        types.put("CONTROL", control);

        // MESSAGE structure
        UDTDefinition message = new UDTDefinition("MESSAGE");
        message.addMember("DN", "BOOL");
        message.addMember("EN", "BOOL");
        message.addMember("ER", "BOOL");
        message.addMember("EW", "BOOL");
        message.addMember("ST", "BOOL");
        message.addMember("TO", "BOOL");
        message.addMember("ERR", "INT");
        message.addMember("EXERR", "INT");
        message.addMember("DN_LEN", "INT");
        message.addMember("REQ_LEN", "INT");
        message.addMember("ConnectionPath", "STRING");
        types.put("MESSAGE", message);
    }

    private static void addProcessControlTypes(Map<String, UDTDefinition> types) {
        // PID - Standard PID control
        UDTDefinition pid = new UDTDefinition("PID");
        pid.addMember("EN", "BOOL");
        pid.addMember("CT", "BOOL");
        pid.addMember("PV", "REAL");
        pid.addMember("SP", "REAL");
        pid.addMember("CVH", "REAL");
        pid.addMember("CVL", "REAL");
        pid.addMember("KP", "REAL");
        pid.addMember("KI", "REAL");
        pid.addMember("KD", "REAL");
        pid.addMember("BIAS", "REAL");
        pid.addMember("TIE", "REAL");
        pid.addMember("MINTIE", "REAL");
        pid.addMember("MAXTIE", "REAL");
        pid.addMember("OUT", "REAL");
        types.put("PID", pid);

        // PIDE - Enhanced PID control
        UDTDefinition pide = new UDTDefinition("PIDE");
        // Process variables
        pide.addMember("PV", "REAL");
        pide.addMember("PVFault", "BOOL");
        pide.addMember("SP", "REAL");
        pide.addMember("SPProg", "REAL");
        pide.addMember("SPCascade", "REAL");
        pide.addMember("SPHLimit", "REAL");
        pide.addMember("SPLLimit", "REAL");
        // Control variables
        pide.addMember("CV", "REAL");
        pide.addMember("CVEU", "REAL");
        pide.addMember("CVHLimit", "REAL");
        pide.addMember("CVLLimit", "REAL");
        pide.addMember("CVROCLimit", "REAL");
        // Tuning
        pide.addMember("Kp", "REAL");
        pide.addMember("Ki", "REAL");
        pide.addMember("Kd", "REAL");
        pide.addMember("KFF", "REAL");
        pide.addMember("Bias", "REAL");
        // Mode
        pide.addMember("ProgOper", "DINT");
        pide.addMember("ProgAutoReq", "BOOL");
        pide.addMember("ProgManualReq", "BOOL");
        pide.addMember("ProgCasReq", "BOOL");
        pide.addMember("ProgValueReset", "BOOL");
        // Alarms
        pide.addMember("PVHHAlarm", "BOOL");
        pide.addMember("PVHAlarm", "BOOL");
        pide.addMember("PVLAlarm", "BOOL");
        pide.addMember("PVLLAlarm", "BOOL");
        pide.addMember("DevHAlarm", "BOOL");
        pide.addMember("DevLAlarm", "BOOL");
        pide.addMember("PVROCPosAlarm", "BOOL");
        pide.addMember("PVROCNegAlarm", "BOOL");
        // Status
        pide.addMember("EN", "BOOL");
        pide.addMember("EU", "BOOL");
        pide.addMember("DN", "BOOL");
        types.put("PIDE", pide);

        // ALARM_ANALOG
        UDTDefinition alarmAnalog = new UDTDefinition("ALARM_ANALOG");
        alarmAnalog.addMember("EnableIn", "BOOL");
        alarmAnalog.addMember("In", "REAL");
        alarmAnalog.addMember("InFault", "BOOL");
        alarmAnalog.addMember("HHEnabled", "BOOL");
        alarmAnalog.addMember("HEnabled", "BOOL");
        alarmAnalog.addMember("LEnabled", "BOOL");
        alarmAnalog.addMember("LLEnabled", "BOOL");
        alarmAnalog.addMember("ROCPosEnabled", "BOOL");
        alarmAnalog.addMember("ROCNegEnabled", "BOOL");
        alarmAnalog.addMember("HHLimit", "REAL");
        alarmAnalog.addMember("HLimit", "REAL");
        alarmAnalog.addMember("LLimit", "REAL");
        alarmAnalog.addMember("LLLimit", "REAL");
        alarmAnalog.addMember("Deadband", "REAL");
        alarmAnalog.addMember("ROCPosLimit", "REAL");
        alarmAnalog.addMember("ROCNegLimit", "REAL");
        alarmAnalog.addMember("ROCPeriod", "REAL");
        alarmAnalog.addMember("HHAlarm", "BOOL");
        alarmAnalog.addMember("HAlarm", "BOOL");
        alarmAnalog.addMember("LAlarm", "BOOL");
        alarmAnalog.addMember("LLAlarm", "BOOL");
        alarmAnalog.addMember("ROCPosAlarm", "BOOL");
        alarmAnalog.addMember("ROCNegAlarm", "BOOL");
        alarmAnalog.addMember("Status", "DINT");
        alarmAnalog.addMember("InstructFault", "BOOL");
        alarmAnalog.addMember("Severity", "DINT");
        types.put("ALARM_ANALOG", alarmAnalog);
        types.put("ALMA", alarmAnalog);

        // ALARM_DIGITAL
        UDTDefinition alarmDigital = new UDTDefinition("ALARM_DIGITAL");
        alarmDigital.addMember("EnableIn", "BOOL");
        alarmDigital.addMember("In", "BOOL");
        alarmDigital.addMember("InFault", "BOOL");
        alarmDigital.addMember("Condition", "BOOL");
        alarmDigital.addMember("AckRequired", "BOOL");
        alarmDigital.addMember("Latched", "BOOL");
        alarmDigital.addMember("ProgAck", "BOOL");
        alarmDigital.addMember("OperAck", "BOOL");
        alarmDigital.addMember("ProgReset", "BOOL");
        alarmDigital.addMember("OperReset", "BOOL");
        alarmDigital.addMember("ProgSuppress", "BOOL");
        alarmDigital.addMember("OperSuppress", "BOOL");
        alarmDigital.addMember("ProgUnsuppress", "BOOL");
        alarmDigital.addMember("OperUnsuppress", "BOOL");
        alarmDigital.addMember("Alarm", "BOOL");
        alarmDigital.addMember("AckAll", "BOOL");
        alarmDigital.addMember("Acked", "BOOL");
        alarmDigital.addMember("InAlarm", "BOOL");
        alarmDigital.addMember("Suppressed", "BOOL");
        alarmDigital.addMember("Severity", "DINT");
        alarmDigital.addMember("Status", "DINT");
        alarmDigital.addMember("InstructFault", "BOOL");
        types.put("ALARM_DIGITAL", alarmDigital);
        types.put("ALMD", alarmDigital);
    }

    private static void addMotionControlTypes(Map<String, UDTDefinition> types) {
        // AXIS_CIP_DRIVE
        UDTDefinition axisCipDrive = new UDTDefinition("AXIS_CIP_DRIVE");
        axisCipDrive.addMember("ActualPosition", "REAL");
        axisCipDrive.addMember("CommandPosition", "REAL");
        axisCipDrive.addMember("ActualVelocity", "REAL");
        axisCipDrive.addMember("CommandVelocity", "REAL");
        axisCipDrive.addMember("ActualAcceleration", "REAL");
        axisCipDrive.addMember("CommandAcceleration", "REAL");
        axisCipDrive.addMember("CIPAxisState", "DINT");
        axisCipDrive.addMember("CIPAxisFaults", "DINT");
        axisCipDrive.addMember("CIPAxisStatus", "DINT");
        axisCipDrive.addMember("AxisState", "DINT");
        axisCipDrive.addMember("ServoActionStatus", "DINT");
        axisCipDrive.addMember("AxisFault", "BOOL");
        axisCipDrive.addMember("PhysicalAxisFault", "BOOL");
        axisCipDrive.addMember("ModuleFault", "BOOL");
        axisCipDrive.addMember("ConfigurationFault", "BOOL");
        axisCipDrive.addMember("MasterOffset", "REAL");
        axisCipDrive.addMember("PositionError", "REAL");
        axisCipDrive.addMember("VelocityError", "REAL");
        axisCipDrive.addMember("MaximumSpeed", "REAL");
        axisCipDrive.addMember("MaximumAcceleration", "REAL");
        axisCipDrive.addMember("MaximumDeceleration", "REAL");
        axisCipDrive.addMember("DriveStatus", "DINT");
        axisCipDrive.addMember("OutputCam", "DINT");
        axisCipDrive.addMember("OutputCamExecutionTargets", "DINT");
        types.put("AXIS_CIP_DRIVE", axisCipDrive);

        // AXIS_VIRTUAL
        UDTDefinition axisVirtual = new UDTDefinition("AXIS_VIRTUAL");
        axisVirtual.addMember("ActualPosition", "REAL");
        axisVirtual.addMember("CommandPosition", "REAL");
        axisVirtual.addMember("ActualVelocity", "REAL");
        axisVirtual.addMember("CommandVelocity", "REAL");
        axisVirtual.addMember("ActualAcceleration", "REAL");
        axisVirtual.addMember("AxisState", "DINT");
        axisVirtual.addMember("AxisFault", "BOOL");
        axisVirtual.addMember("MaximumSpeed", "REAL");
        axisVirtual.addMember("MaximumAcceleration", "REAL");
        axisVirtual.addMember("MaximumDeceleration", "REAL");
        types.put("AXIS_VIRTUAL", axisVirtual);

        // AXIS_SERVO_DRIVE
        UDTDefinition axisServoDrive = new UDTDefinition("AXIS_SERVO_DRIVE");
        axisServoDrive.addMember("ActualPosition", "REAL");
        axisServoDrive.addMember("CommandPosition", "REAL");
        axisServoDrive.addMember("ActualVelocity", "REAL");
        axisServoDrive.addMember("CommandVelocity", "REAL");
        axisServoDrive.addMember("AxisState", "DINT");
        axisServoDrive.addMember("AxisFault", "BOOL");
        types.put("AXIS_SERVO_DRIVE", axisServoDrive);

        // MOTION_GROUP
        UDTDefinition motionGroup = new UDTDefinition("MOTION_GROUP");
        motionGroup.addMember("GroupStatus", "DINT");
        motionGroup.addMember("GroupFault", "BOOL");
        motionGroup.addMember("Alternate1UpdateMultiplier", "DINT");
        motionGroup.addMember("Alternate2UpdateMultiplier", "DINT");
        motionGroup.addMember("CoarseUpdatePeriod", "DINT");
        types.put("MOTION_GROUP", motionGroup);

        // CAM
        UDTDefinition cam = new UDTDefinition("CAM");
        cam.addMember("Type", "DINT");
        cam.addMember("Size", "DINT");
        cam.addMember("Status", "DINT");
        cam.addMember("StartSlope", "REAL");
        cam.addMember("EndSlope", "REAL");
        types.put("CAM", cam);

        // CAM_PROFILE
        UDTDefinition camProfile = new UDTDefinition("CAM_PROFILE");
        camProfile.addMember("Type", "DINT");
        camProfile.addMember("Interpolation", "DINT");
        camProfile.addMember("Status", "DINT");
        types.put("CAM_PROFILE", camProfile);
    }

    private static void addSpecialtyTypes(Map<String, UDTDefinition> types) {
        // COORDINATE_SYSTEM
        UDTDefinition coordSystem = new UDTDefinition("COORDINATE_SYSTEM");
        coordSystem.addMember("Type", "DINT");
        coordSystem.addMember("Status", "DINT");
        coordSystem.addMember("ActualPosition", "REAL");
        coordSystem.addMember("ActualPositionY", "REAL");
        coordSystem.addMember("ActualPositionZ", "REAL");
        types.put("COORDINATE_SYSTEM", coordSystem);

        // PHASE
        UDTDefinition phase = new UDTDefinition("PHASE");
        phase.addMember("Status", "DINT");
        phase.addMember("Command", "DINT");
        phase.addMember("Owner", "DINT");
        phase.addMember("Failures", "DINT");
        types.put("PHASE", phase);

        // EQUIPMENT_SEQUENCE
        UDTDefinition equipSeq = new UDTDefinition("EQUIPMENT_SEQUENCE");
        equipSeq.addMember("Status", "DINT");
        equipSeq.addMember("Command", "DINT");
        equipSeq.addMember("Step", "DINT");
        types.put("EQUIPMENT_SEQUENCE", equipSeq);

        // FBD_TIMER
        UDTDefinition fbdTimer = new UDTDefinition("FBD_TIMER");
        fbdTimer.addMember("PRE", "DINT");
        fbdTimer.addMember("ACC", "DINT");
        fbdTimer.addMember("EN", "BOOL");
        fbdTimer.addMember("DN", "BOOL");
        fbdTimer.addMember("TT", "BOOL");
        types.put("FBD_TIMER", fbdTimer);

        // FBD_COUNTER
        UDTDefinition fbdCounter = new UDTDefinition("FBD_COUNTER");
        fbdCounter.addMember("PRE", "DINT");
        fbdCounter.addMember("ACC", "DINT");
        fbdCounter.addMember("CU", "BOOL");
        fbdCounter.addMember("CD", "BOOL");
        fbdCounter.addMember("DN", "BOOL");
        fbdCounter.addMember("OV", "BOOL");
        fbdCounter.addMember("UN", "BOOL");
        types.put("FBD_COUNTER", fbdCounter);
    }
}
