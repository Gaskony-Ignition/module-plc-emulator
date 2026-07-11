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
 * <p>Member sets are sourced from {@code docs/plans/ADDRESSING.md} §3.11 (the normative
 * addressing spec, DOC-CONFIRMED per type unless noted INFERRED/INCOMPLETE below) — not
 * hand-approximated. Where §3.11 recommends deriving a table from a real Studio 5000 export
 * rather than a transcribed list (PIDE, ALARM_ANALOG), the members below were extracted
 * directly from the vendored/local corpus rather than authored from memory.
 *
 * Covers 22 predefined types across:
 * - Basic types: TIMER, COUNTER, CONTROL, MESSAGE
 * - Process control: PID, PIDE (aliased from the real L5X type name PID_ENHANCED),
 *   ALARM_ANALOG, ALARM_DIGITAL
 * - Motion control: AXIS_CIP_DRIVE, AXIS_VIRTUAL, AXIS_SERVO_DRIVE, MOTION_GROUP, CAM, CAM_PROFILE
 * - Specialty: COORDINATE_SYSTEM, PHASE, EQUIPMENT_SEQUENCE, FBD_TIMER, FBD_COUNTER
 *
 * <p><b>INCOMPLETE types (ADDRESSING.md §3.11.1 policy):</b> AXIS_CIP_DRIVE, AXIS_VIRTUAL,
 * AXIS_SERVO_DRIVE, MOTION_GROUP and COORDINATE_SYSTEM are enormous (hundreds of members) and
 * have no public corpus export. Per policy this class emits only the ~10-15 most-referenced
 * members for each rather than hand-authoring a full list from memory; see the per-type
 * comments below and {@code docs/KNOWN_ISSUES.md}. CAM is small and fully DOC-CONFIRMED
 * (3 members) so it is NOT incomplete.
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
        // STRING (base) — ADDRESSING.md §3.10, DOC-CONFIRMED (Kevin Herron, IA staff, verbatim):
        // "with the v21 driver the LEN and DATA members are always browsable underneath the
        // String tag itself." Corpus <DataType Name="STRING"> shows LEN (DINT) + DATA (SINT[82]).
        // A plain "STRING" tag DataType previously had no entry here at all, so L5XParser.parseTag
        // never routed it through expandUdtInstance and it fell through to a bare scalar with no
        // .LEN/.DATA members (defect FIX-5). Custom STRING_n types already worked because the L5X
        // declares them as an ordinary <DataType> with LEN/DATA members - this makes base STRING
        // follow the identical path.
        UDTDefinition string = new UDTDefinition("STRING");
        string.addMember("LEN", "DINT");
        string.addMember("DATA", "SINT", "82");
        types.put("STRING", string);

        // TIMER structure — ADDRESSING.md §3.11 TIMER: 5 members, DOC-CONFIRMED (verbatim from
        // corpus L83E export + pycomm3). There is NO .ER member — the phantom .ER previously
        // here did not exist on the real type and has been removed.
        UDTDefinition timer = new UDTDefinition("TIMER");
        timer.addMember("PRE", "DINT");
        timer.addMember("ACC", "DINT");
        timer.addMember("EN", "BOOL");
        timer.addMember("TT", "BOOL");
        timer.addMember("DN", "BOOL");
        types.put("TIMER", timer);

        // COUNTER structure — ADDRESSING.md §3.11 COUNTER: 7 members, DOC-CONFIRMED
        // (emulator already correct). No .EN, no .ER.
        UDTDefinition counter = new UDTDefinition("COUNTER");
        counter.addMember("PRE", "DINT");
        counter.addMember("ACC", "DINT");
        counter.addMember("CU", "BOOL");
        counter.addMember("CD", "BOOL");
        counter.addMember("DN", "BOOL");
        counter.addMember("OV", "BOOL");
        counter.addMember("UN", "BOOL");
        types.put("COUNTER", counter);

        // CONTROL structure — ADDRESSING.md §3.11 CONTROL: 10 members, DOC-CONFIRMED.
        // CONTROL DOES have .ER (unlike TIMER). Previously missing .UL, .IN, .FD — added.
        UDTDefinition control = new UDTDefinition("CONTROL");
        control.addMember("LEN", "DINT");
        control.addMember("POS", "DINT");
        control.addMember("EN", "BOOL");
        control.addMember("EU", "BOOL");
        control.addMember("DN", "BOOL");
        control.addMember("EM", "BOOL");
        control.addMember("ER", "BOOL");
        control.addMember("UL", "BOOL");
        control.addMember("IN", "BOOL");
        control.addMember("FD", "BOOL");
        types.put("CONTROL", control);

        // MESSAGE structure — ADDRESSING.md §3.11 MESSAGE: status+core DOC-CONFIRMED, config
        // layout INFERRED (1756-PM012 / field practice). FLAGS itself is not modelled as a
        // separate raw word member (only the named status bits are, per the driver's browse
        // behaviour). Previously invented .ConnectionPath (STRING, not a real member) has been
        // removed; .EN_CC and .ST were missing and have been added.
        UDTDefinition message = new UDTDefinition("MESSAGE");
        // Status bits
        message.addMember("EW", "BOOL");
        message.addMember("ST", "BOOL");
        message.addMember("DN", "BOOL");
        message.addMember("ER", "BOOL");
        message.addMember("TO", "BOOL");
        message.addMember("EN", "BOOL");
        message.addMember("EN_CC", "BOOL");
        // Data/error (INT, not DINT)
        message.addMember("ERR", "INT");
        message.addMember("EXERR", "INT");
        message.addMember("DN_LEN", "INT");
        message.addMember("REQ_LEN", "INT");
        // Config members (INFERRED layout; names from 1756-PM012 / field practice)
        message.addMember("Class", "INT");
        message.addMember("Instance", "DINT");
        message.addMember("Attribute", "INT");
        message.addMember("LocalIndex", "DINT");
        message.addMember("RemoteIndex", "DINT");
        message.addMember("Channel", "SINT");
        message.addMember("Rack", "SINT");
        message.addMember("Group", "SINT");
        message.addMember("Slot", "SINT");
        message.addMember("DestinationLink", "INT");
        message.addMember("DestinationNode", "INT");
        message.addMember("SourceLink", "INT");
        message.addMember("UnconnectedTimeout", "DINT");
        message.addMember("ConnectionRate", "DINT");
        message.addMember("TimeoutMultiplier", "SINT");
        message.addMember("ServiceCode", "INT");
        types.put("MESSAGE", message);
    }

    private static void addProcessControlTypes(Map<String, UDTDefinition> types) {
        // PID (classic) — ADDRESSING.md §3.11 PID: ~46-49 members, DOC-CONFIRMED verbatim from
        // the Logix5000 reference manual. Not in the vendored corpus (synthesised fixture:
        // test-files/synthetic-pid.l5x). Replaces the previous 14-member set with several
        // non-existent names (CVH/CVL/TIE/MINTIE/MAXTIE mixed with invented ones) wholesale.
        // Note PID.ERR is a REAL (scaled error) — distinct from MESSAGE.ERR (INT).
        UDTDefinition pid = new UDTDefinition("PID");
        // 21 status bits
        pid.addMember("EN", "BOOL");
        pid.addMember("CT", "BOOL");
        pid.addMember("CL", "BOOL");
        pid.addMember("PVT", "BOOL");
        pid.addMember("DOE", "BOOL");
        pid.addMember("SWM", "BOOL");
        pid.addMember("CA", "BOOL");
        pid.addMember("MO", "BOOL");
        pid.addMember("PE", "BOOL");
        pid.addMember("NDF", "BOOL");
        pid.addMember("NOBC", "BOOL");
        pid.addMember("NOZC", "BOOL");
        pid.addMember("INI", "BOOL");
        pid.addMember("SPOR", "BOOL");
        pid.addMember("OLL", "BOOL");
        pid.addMember("OLH", "BOOL");
        pid.addMember("EWD", "BOOL");
        pid.addMember("DVNA", "BOOL");
        pid.addMember("DVPA", "BOOL");
        pid.addMember("PVLA", "BOOL");
        pid.addMember("PVHA", "BOOL");
        // 28 REAL parameters
        pid.addMember("SP", "REAL");
        pid.addMember("KP", "REAL");
        pid.addMember("KI", "REAL");
        pid.addMember("KD", "REAL");
        pid.addMember("BIAS", "REAL");
        pid.addMember("MAXS", "REAL");
        pid.addMember("MINS", "REAL");
        pid.addMember("DB", "REAL");
        pid.addMember("SO", "REAL");
        pid.addMember("MAXO", "REAL");
        pid.addMember("MINO", "REAL");
        pid.addMember("UPD", "REAL");
        pid.addMember("PV", "REAL");
        pid.addMember("ERR", "REAL");
        pid.addMember("OUT", "REAL");
        pid.addMember("PVH", "REAL");
        pid.addMember("PVL", "REAL");
        pid.addMember("DVP", "REAL");
        pid.addMember("DVN", "REAL");
        pid.addMember("PVDB", "REAL");
        pid.addMember("DVDB", "REAL");
        pid.addMember("MAXI", "REAL");
        pid.addMember("MINI", "REAL");
        pid.addMember("TIE", "REAL");
        pid.addMember("MAXCV", "REAL");
        pid.addMember("MINCV", "REAL");
        pid.addMember("MINTIE", "REAL");
        pid.addMember("MAXTIE", "REAL");
        types.put("PID", pid);

        // PIDE (PID_ENHANCED) — ADDRESSING.md §3.11 PIDE: ~130+ members, DOC-CONFIRMED verbatim
        // from the corpus 1768 export (local-only, unlicensed — never vendored/committed; see
        // gateway/src/test/resources/corpus/ATTRIBUTION.md). The genuine corpus tag RMPS_PIDE
        // (DataType="PID_ENHANCED") exposes 165 members; derived from that real export rather
        // than hand-authored, per the spec's recommendation. Replaces the previous ~30-member
        // approximation wholesale.
        //
        // FLAG: the L5X DataType string for this type is PID_ENHANCED, not PIDE — both keys are
        // registered below (mirroring the existing ALMA/ALARM_ANALOG alias pattern) so the
        // parser resolves either name to the same definition.
        UDTDefinition pide = new UDTDefinition("PIDE");
        pide.addMember("EnableIn", "BOOL");
        pide.addMember("PV", "REAL");
        pide.addMember("PVFault", "BOOL");
        pide.addMember("PVEUMax", "REAL");
        pide.addMember("PVEUMin", "REAL");
        pide.addMember("SPProg", "REAL");
        pide.addMember("SPOper", "REAL");
        pide.addMember("SPCascade", "REAL");
        pide.addMember("SPHLimit", "REAL");
        pide.addMember("SPLLimit", "REAL");
        pide.addMember("UseRatio", "BOOL");
        pide.addMember("RatioProg", "REAL");
        pide.addMember("RatioOper", "REAL");
        pide.addMember("RatioHLimit", "REAL");
        pide.addMember("RatioLLimit", "REAL");
        pide.addMember("CVFault", "BOOL");
        pide.addMember("CVInitReq", "BOOL");
        pide.addMember("CVInitValue", "REAL");
        pide.addMember("CVProg", "REAL");
        pide.addMember("CVOper", "REAL");
        pide.addMember("CVOverride", "REAL");
        pide.addMember("CVPrevious", "REAL");
        pide.addMember("CVSetPrevious", "BOOL");
        pide.addMember("CVManLimiting", "BOOL");
        pide.addMember("CVEUMax", "REAL");
        pide.addMember("CVEUMin", "REAL");
        pide.addMember("CVHLimit", "REAL");
        pide.addMember("CVLLimit", "REAL");
        pide.addMember("CVROCLimit", "REAL");
        pide.addMember("FF", "REAL");
        pide.addMember("FFPrevious", "REAL");
        pide.addMember("FFSetPrevious", "BOOL");
        pide.addMember("HandFB", "REAL");
        pide.addMember("HandFBFault", "BOOL");
        pide.addMember("WindupHIn", "BOOL");
        pide.addMember("WindupLIn", "BOOL");
        pide.addMember("ControlAction", "BOOL");
        pide.addMember("DependIndepend", "BOOL");
        pide.addMember("PGain", "REAL");
        pide.addMember("IGain", "REAL");
        pide.addMember("DGain", "REAL");
        pide.addMember("PVEProportional", "BOOL");
        pide.addMember("PVEDerivative", "BOOL");
        pide.addMember("DSmoothing", "BOOL");
        pide.addMember("PVTracking", "BOOL");
        pide.addMember("ZCDeadband", "REAL");
        pide.addMember("ZCOff", "BOOL");
        pide.addMember("PVHHLimit", "REAL");
        pide.addMember("PVHLimit", "REAL");
        pide.addMember("PVLLimit", "REAL");
        pide.addMember("PVLLLimit", "REAL");
        pide.addMember("PVDeadband", "REAL");
        pide.addMember("PVROCPosLimit", "REAL");
        pide.addMember("PVROCNegLimit", "REAL");
        pide.addMember("PVROCPeriod", "REAL");
        pide.addMember("DevHHLimit", "REAL");
        pide.addMember("DevHLimit", "REAL");
        pide.addMember("DevLLimit", "REAL");
        pide.addMember("DevLLLimit", "REAL");
        pide.addMember("DevDeadband", "REAL");
        pide.addMember("AllowCasRat", "BOOL");
        pide.addMember("ManualAfterInit", "BOOL");
        pide.addMember("ProgProgReq", "BOOL");
        pide.addMember("ProgOperReq", "BOOL");
        pide.addMember("ProgCasRatReq", "BOOL");
        pide.addMember("ProgAutoReq", "BOOL");
        pide.addMember("ProgManualReq", "BOOL");
        pide.addMember("ProgOverrideReq", "BOOL");
        pide.addMember("ProgHandReq", "BOOL");
        pide.addMember("OperProgReq", "BOOL");
        pide.addMember("OperOperReq", "BOOL");
        pide.addMember("OperCasRatReq", "BOOL");
        pide.addMember("OperAutoReq", "BOOL");
        pide.addMember("OperManualReq", "BOOL");
        pide.addMember("ProgValueReset", "BOOL");
        pide.addMember("TimingMode", "DINT");
        pide.addMember("OversampleDT", "REAL");
        pide.addMember("RTSTime", "DINT");
        pide.addMember("RTSTimeStamp", "DINT");
        pide.addMember("AtuneAcquire", "BOOL");
        pide.addMember("AtuneStart", "BOOL");
        pide.addMember("AtuneUseGains", "BOOL");
        pide.addMember("AtuneAbort", "BOOL");
        pide.addMember("AtuneUnacquire", "BOOL");
        pide.addMember("EnableOut", "BOOL");
        pide.addMember("CVEU", "REAL");
        pide.addMember("CV", "REAL");
        pide.addMember("CVInitializing", "BOOL");
        pide.addMember("CVHAlarm", "BOOL");
        pide.addMember("CVLAlarm", "BOOL");
        pide.addMember("CVROCAlarm", "BOOL");
        pide.addMember("SP", "REAL");
        pide.addMember("SPPercent", "REAL");
        pide.addMember("SPHAlarm", "BOOL");
        pide.addMember("SPLAlarm", "BOOL");
        pide.addMember("PVPercent", "REAL");
        pide.addMember("E", "REAL");
        pide.addMember("EPercent", "REAL");
        pide.addMember("InitPrimary", "BOOL");
        pide.addMember("WindupHOut", "BOOL");
        pide.addMember("WindupLOut", "BOOL");
        pide.addMember("Ratio", "REAL");
        pide.addMember("RatioHAlarm", "BOOL");
        pide.addMember("RatioLAlarm", "BOOL");
        pide.addMember("ZCDeadbandOn", "BOOL");
        pide.addMember("PVHHAlarm", "BOOL");
        pide.addMember("PVHAlarm", "BOOL");
        pide.addMember("PVLAlarm", "BOOL");
        pide.addMember("PVLLAlarm", "BOOL");
        pide.addMember("PVROCPosAlarm", "BOOL");
        pide.addMember("PVROCNegAlarm", "BOOL");
        pide.addMember("DevHHAlarm", "BOOL");
        pide.addMember("DevHAlarm", "BOOL");
        pide.addMember("DevLAlarm", "BOOL");
        pide.addMember("DevLLAlarm", "BOOL");
        pide.addMember("ProgOper", "BOOL");
        pide.addMember("CasRat", "BOOL");
        pide.addMember("Auto", "BOOL");
        pide.addMember("Manual", "BOOL");
        pide.addMember("Override", "BOOL");
        pide.addMember("Hand", "BOOL");
        pide.addMember("DeltaT", "REAL");
        pide.addMember("AtuneReady", "BOOL");
        pide.addMember("AtuneOn", "BOOL");
        pide.addMember("AtuneDone", "BOOL");
        pide.addMember("AtuneAborted", "BOOL");
        pide.addMember("AtuneBusy", "BOOL");
        pide.addMember("Status1", "DINT");
        pide.addMember("Status2", "DINT");
        pide.addMember("InstructFault", "BOOL");
        pide.addMember("PVFaulted", "BOOL");
        pide.addMember("CVFaulted", "BOOL");
        pide.addMember("HandFBFaulted", "BOOL");
        pide.addMember("PVSpanInv", "BOOL");
        pide.addMember("SPProgInv", "BOOL");
        pide.addMember("SPOperInv", "BOOL");
        pide.addMember("SPCascadeInv", "BOOL");
        pide.addMember("SPLimitsInv", "BOOL");
        pide.addMember("RatioProgInv", "BOOL");
        pide.addMember("RatioOperInv", "BOOL");
        pide.addMember("RatioLimitsInv", "BOOL");
        pide.addMember("CVProgInv", "BOOL");
        pide.addMember("CVOperInv", "BOOL");
        pide.addMember("CVOverrideInv", "BOOL");
        pide.addMember("CVPreviousInv", "BOOL");
        pide.addMember("CVEUSpanInv", "BOOL");
        pide.addMember("CVLimitsInv", "BOOL");
        pide.addMember("CVROCLimitInv", "BOOL");
        pide.addMember("FFInv", "BOOL");
        pide.addMember("FFPreviousInv", "BOOL");
        pide.addMember("HandFBInv", "BOOL");
        pide.addMember("PGainInv", "BOOL");
        pide.addMember("IGainInv", "BOOL");
        pide.addMember("DGainInv", "BOOL");
        pide.addMember("ZCDeadbandInv", "BOOL");
        pide.addMember("PVDeadbandInv", "BOOL");
        pide.addMember("PVROCLimitsInv", "BOOL");
        pide.addMember("DevHLLimitsInv", "BOOL");
        pide.addMember("DevDeadbandInv", "BOOL");
        pide.addMember("AtuneDataInv", "BOOL");
        pide.addMember("TimingModeInv", "BOOL");
        pide.addMember("RTSMissed", "BOOL");
        pide.addMember("RTSTimeInv", "BOOL");
        pide.addMember("RTSTimeStampInv", "BOOL");
        pide.addMember("DeltaTInv", "BOOL");
        types.put("PIDE", pide);
        types.put("PID_ENHANCED", pide);

        // ALARM_ANALOG — ADDRESSING.md §3.11 ALARM_ANALOG: ~90-100+ members, DOC-CONFIRMED
        // (most). The 65 config/input members below are verbatim from the real corpus export
        // (ControlLogix-1756L83E-fw36-L5Sharp.L5X, tag TestAnalogAlarm, <AlarmAnalogParameters>
        // attributes — vendored in gateway/src/test/resources/corpus/), not hand-authored. The
        // 12 output/status members are the spec's core-confirmed set (static exports do not
        // serialise computed outputs, so these cannot be corpus-derived). This 77-member set is
        // the confirmed subset of an estimated 90-100+; further per-level Acked/InAlarm output
        // members are NOT named here to avoid re-introducing the "approximate from memory"
        // failure this fix corrects. Replaces the previous 25-member approximation wholesale.
        UDTDefinition alarmAnalog = new UDTDefinition("ALARM_ANALOG");
        // Config/input (corpus-verified, in export order)
        alarmAnalog.addMember("EnableIn", "BOOL");
        alarmAnalog.addMember("InFault", "BOOL");
        alarmAnalog.addMember("HHEnabled", "BOOL");
        alarmAnalog.addMember("HEnabled", "BOOL");
        alarmAnalog.addMember("LEnabled", "BOOL");
        alarmAnalog.addMember("LLEnabled", "BOOL");
        alarmAnalog.addMember("AckRequired", "BOOL");
        alarmAnalog.addMember("ProgAckAll", "BOOL");
        alarmAnalog.addMember("OperAckAll", "BOOL");
        alarmAnalog.addMember("HHProgAck", "BOOL");
        alarmAnalog.addMember("HHOperAck", "BOOL");
        alarmAnalog.addMember("HProgAck", "BOOL");
        alarmAnalog.addMember("HOperAck", "BOOL");
        alarmAnalog.addMember("LProgAck", "BOOL");
        alarmAnalog.addMember("LOperAck", "BOOL");
        alarmAnalog.addMember("LLProgAck", "BOOL");
        alarmAnalog.addMember("LLOperAck", "BOOL");
        alarmAnalog.addMember("ROCPosProgAck", "BOOL");
        alarmAnalog.addMember("ROCPosOperAck", "BOOL");
        alarmAnalog.addMember("ROCNegProgAck", "BOOL");
        alarmAnalog.addMember("ROCNegOperAck", "BOOL");
        alarmAnalog.addMember("ProgSuppress", "BOOL");
        alarmAnalog.addMember("OperSuppress", "BOOL");
        alarmAnalog.addMember("ProgUnsuppress", "BOOL");
        alarmAnalog.addMember("OperUnsuppress", "BOOL");
        alarmAnalog.addMember("HHOperShelve", "BOOL");
        alarmAnalog.addMember("HOperShelve", "BOOL");
        alarmAnalog.addMember("LOperShelve", "BOOL");
        alarmAnalog.addMember("LLOperShelve", "BOOL");
        alarmAnalog.addMember("ROCPosOperShelve", "BOOL");
        alarmAnalog.addMember("ROCNegOperShelve", "BOOL");
        alarmAnalog.addMember("ProgUnshelveAll", "BOOL");
        alarmAnalog.addMember("HHOperUnshelve", "BOOL");
        alarmAnalog.addMember("HOperUnshelve", "BOOL");
        alarmAnalog.addMember("LOperUnshelve", "BOOL");
        alarmAnalog.addMember("LLOperUnshelve", "BOOL");
        alarmAnalog.addMember("ROCPosOperUnshelve", "BOOL");
        alarmAnalog.addMember("ROCNegOperUnshelve", "BOOL");
        alarmAnalog.addMember("ProgDisable", "BOOL");
        alarmAnalog.addMember("OperDisable", "BOOL");
        alarmAnalog.addMember("ProgEnable", "BOOL");
        alarmAnalog.addMember("OperEnable", "BOOL");
        alarmAnalog.addMember("AlarmCountReset", "BOOL");
        alarmAnalog.addMember("HHMinDurationEnable", "BOOL");
        alarmAnalog.addMember("HMinDurationEnable", "BOOL");
        alarmAnalog.addMember("LMinDurationEnable", "BOOL");
        alarmAnalog.addMember("LLMinDurationEnable", "BOOL");
        alarmAnalog.addMember("In", "REAL");
        alarmAnalog.addMember("HHLimit", "REAL");
        alarmAnalog.addMember("HHSeverity", "DINT");
        alarmAnalog.addMember("HLimit", "REAL");
        alarmAnalog.addMember("HSeverity", "DINT");
        alarmAnalog.addMember("LLimit", "REAL");
        alarmAnalog.addMember("LSeverity", "DINT");
        alarmAnalog.addMember("LLLimit", "REAL");
        alarmAnalog.addMember("LLSeverity", "DINT");
        alarmAnalog.addMember("MinDurationPRE", "DINT");
        alarmAnalog.addMember("ShelveDuration", "DINT");
        alarmAnalog.addMember("MaxShelveDuration", "DINT");
        alarmAnalog.addMember("Deadband", "REAL");
        alarmAnalog.addMember("ROCPosLimit", "REAL");
        alarmAnalog.addMember("ROCPosSeverity", "DINT");
        alarmAnalog.addMember("ROCNegLimit", "REAL");
        alarmAnalog.addMember("ROCNegSeverity", "DINT");
        alarmAnalog.addMember("ROCPeriod", "REAL");
        // Output/status (spec core-confirmed set)
        alarmAnalog.addMember("EnableOut", "BOOL");
        alarmAnalog.addMember("InAlarm", "BOOL");
        alarmAnalog.addMember("AnyInAlarmUnack", "BOOL");
        alarmAnalog.addMember("HHInAlarm", "BOOL");
        alarmAnalog.addMember("HInAlarm", "BOOL");
        alarmAnalog.addMember("LInAlarm", "BOOL");
        alarmAnalog.addMember("LLInAlarm", "BOOL");
        alarmAnalog.addMember("ROC", "REAL");
        alarmAnalog.addMember("HHAcked", "BOOL");
        alarmAnalog.addMember("Severity", "DINT");
        alarmAnalog.addMember("Status", "DINT");
        alarmAnalog.addMember("InstructFault", "BOOL");
        types.put("ALARM_ANALOG", alarmAnalog);
        types.put("ALMA", alarmAnalog);

        // ALARM_DIGITAL — ADDRESSING.md §3.11 ALARM_DIGITAL: ~35-40 members, DOC-CONFIRMED
        // (this is the spec's complete, non-elliptical list — 23 inputs + 17 outputs = 40).
        // Replaces the previous 22-member approximation wholesale.
        UDTDefinition alarmDigital = new UDTDefinition("ALARM_DIGITAL");
        // Inputs
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
        alarmDigital.addMember("ProgDisable", "BOOL");
        alarmDigital.addMember("OperDisable", "BOOL");
        alarmDigital.addMember("ProgEnable", "BOOL");
        alarmDigital.addMember("OperEnable", "BOOL");
        alarmDigital.addMember("AlarmCountReset", "BOOL");
        alarmDigital.addMember("UseProgTime", "BOOL");
        alarmDigital.addMember("ProgTime", "LINT");
        alarmDigital.addMember("Severity", "DINT");
        alarmDigital.addMember("MinDurationPRE", "DINT");
        // Outputs
        alarmDigital.addMember("EnableOut", "BOOL");
        alarmDigital.addMember("InAlarm", "BOOL");
        alarmDigital.addMember("Acked", "BOOL");
        alarmDigital.addMember("InAlarmUnack", "BOOL");
        alarmDigital.addMember("Suppressed", "BOOL");
        alarmDigital.addMember("Disabled", "BOOL");
        alarmDigital.addMember("MinDurationACC", "DINT");
        alarmDigital.addMember("AlarmCount", "DINT");
        alarmDigital.addMember("InAlarmTime", "LINT");
        alarmDigital.addMember("AckTime", "LINT");
        alarmDigital.addMember("RetToNormalTime", "LINT");
        alarmDigital.addMember("AlarmCountResetTime", "LINT");
        alarmDigital.addMember("DeliveryER", "BOOL");
        alarmDigital.addMember("DeliveryDN", "BOOL");
        alarmDigital.addMember("InFaulted", "BOOL");
        alarmDigital.addMember("InstructFault", "BOOL");
        alarmDigital.addMember("Status", "DINT");
        types.put("ALARM_DIGITAL", alarmDigital);
        types.put("ALMD", alarmDigital);
    }

    private static void addMotionControlTypes(Map<String, UDTDefinition> types) {
        // AXIS_CIP_DRIVE — ADDRESSING.md §3.11.1 policy: enormous (~468 members, DOC-CONFIRMED
        // count), not in the corpus, no public motion export exists. INCOMPLETE by policy: emits
        // only the ~10-15 most-referenced members (INFERRED) common to the axis family plus the
        // two AXIS_CIP_DRIVE-specific additions the spec names. See docs/KNOWN_ISSUES.md.
        UDTDefinition axisCipDrive = new UDTDefinition("AXIS_CIP_DRIVE");
        addCommonAxisMembers(axisCipDrive);
        axisCipDrive.addMember("ActualTorque", "REAL");
        axisCipDrive.addMember("MotorVelocityFeedback", "REAL");
        types.put("AXIS_CIP_DRIVE", axisCipDrive);

        // AXIS_VIRTUAL — ADDRESSING.md §3.11.1 policy: ~110-150 members (DOC-CONFIRMED count),
        // INCOMPLETE by policy; common axis-family minimum set only. See docs/KNOWN_ISSUES.md.
        UDTDefinition axisVirtual = new UDTDefinition("AXIS_VIRTUAL");
        addCommonAxisMembers(axisVirtual);
        types.put("AXIS_VIRTUAL", axisVirtual);

        // AXIS_SERVO_DRIVE — ADDRESSING.md §3.11.1 policy: ~200-260 members (DOC-CONFIRMED
        // count), INCOMPLETE by policy; common axis-family minimum set only.
        // See docs/KNOWN_ISSUES.md.
        UDTDefinition axisServoDrive = new UDTDefinition("AXIS_SERVO_DRIVE");
        addCommonAxisMembers(axisServoDrive);
        types.put("AXIS_SERVO_DRIVE", axisServoDrive);

        // MOTION_GROUP — ADDRESSING.md §3.11.1: ~12 members (DOC-CONFIRMED count), not named
        // individually in the spec. INCOMPLETE by policy: retained as a best-effort placeholder
        // (unchanged from the prior approximation) pending a real export or IA bench read —
        // see docs/KNOWN_ISSUES.md. Do not treat this set as authoritative.
        UDTDefinition motionGroup = new UDTDefinition("MOTION_GROUP");
        motionGroup.addMember("GroupStatus", "DINT");
        motionGroup.addMember("GroupFault", "BOOL");
        motionGroup.addMember("Alternate1UpdateMultiplier", "DINT");
        motionGroup.addMember("Alternate2UpdateMultiplier", "DINT");
        motionGroup.addMember("CoarseUpdatePeriod", "DINT");
        types.put("MOTION_GROUP", motionGroup);

        // CAM — ADDRESSING.md §3.11.1: a small cam-POINT type, DOC-CONFIRMED and complete —
        // exactly 3 members, used as an array element (MyCam[n].X). The previous
        // Type/Size/Status/StartSlope/EndSlope set was entirely fabricated; replaced wholesale.
        UDTDefinition cam = new UDTDefinition("CAM");
        cam.addMember("X", "REAL");
        cam.addMember("Y", "REAL");
        cam.addMember("SegmentType", "SINT");
        types.put("CAM", cam);

        // CAM_PROFILE — not covered by ADDRESSING.md §3.11 (no confirmed or inferred source);
        // left unchanged, out of scope for this fix.
        UDTDefinition camProfile = new UDTDefinition("CAM_PROFILE");
        camProfile.addMember("Type", "DINT");
        camProfile.addMember("Interpolation", "DINT");
        camProfile.addMember("Status", "DINT");
        types.put("CAM_PROFILE", camProfile);
    }

    /**
     * Members common to the AXIS_* family (ADDRESSING.md §3.11.1, INFERRED minimum set).
     * @param axis the axis UDT definition to populate
     */
    private static void addCommonAxisMembers(UDTDefinition axis) {
        axis.addMember("ActualPosition", "REAL");
        axis.addMember("CommandPosition", "REAL");
        axis.addMember("ActualVelocity", "REAL");
        axis.addMember("CommandVelocity", "REAL");
        axis.addMember("ActualAcceleration", "REAL");
        axis.addMember("CommandAcceleration", "REAL");
        axis.addMember("PositionError", "REAL");
        axis.addMember("AverageVelocity", "REAL");
        axis.addMember("MasterOffset", "REAL");
        axis.addMember("AxisFault", "BOOL");
        axis.addMember("AxisState", "DINT");
        axis.addMember("MotionStatus", "DINT");
        axis.addMember("ServoActionStatus", "DINT");
        axis.addMember("DriveEnableStatus", "BOOL");
    }

    private static void addSpecialtyTypes(Map<String, UDTDefinition> types) {
        // COORDINATE_SYSTEM — ADDRESSING.md §3.11.1: ~80-120 members (DOC-CONFIRMED count), not
        // named individually in the spec. INCOMPLETE by policy: retained as a best-effort
        // placeholder (unchanged) pending a real export or IA bench read.
        // See docs/KNOWN_ISSUES.md.
        UDTDefinition coordSystem = new UDTDefinition("COORDINATE_SYSTEM");
        coordSystem.addMember("Type", "DINT");
        coordSystem.addMember("Status", "DINT");
        coordSystem.addMember("ActualPosition", "REAL");
        coordSystem.addMember("ActualPositionY", "REAL");
        coordSystem.addMember("ActualPositionZ", "REAL");
        types.put("COORDINATE_SYSTEM", coordSystem);

        // PHASE, EQUIPMENT_SEQUENCE, FBD_TIMER, FBD_COUNTER — not covered by ADDRESSING.md
        // §3.11 (no confirmed or inferred source recorded); left unchanged, out of scope.
        UDTDefinition phase = new UDTDefinition("PHASE");
        phase.addMember("Status", "DINT");
        phase.addMember("Command", "DINT");
        phase.addMember("Owner", "DINT");
        phase.addMember("Failures", "DINT");
        types.put("PHASE", phase);

        UDTDefinition equipSeq = new UDTDefinition("EQUIPMENT_SEQUENCE");
        equipSeq.addMember("Status", "DINT");
        equipSeq.addMember("Command", "DINT");
        equipSeq.addMember("Step", "DINT");
        types.put("EQUIPMENT_SEQUENCE", equipSeq);

        UDTDefinition fbdTimer = new UDTDefinition("FBD_TIMER");
        fbdTimer.addMember("PRE", "DINT");
        fbdTimer.addMember("ACC", "DINT");
        fbdTimer.addMember("EN", "BOOL");
        fbdTimer.addMember("DN", "BOOL");
        fbdTimer.addMember("TT", "BOOL");
        types.put("FBD_TIMER", fbdTimer);

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
