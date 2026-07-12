package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.logixemulator.gateway.parser.PLCParser;
import com.inductiveautomation.logixemulator.gateway.parser.ParserFactory;
import org.eclipse.milo.opcua.sdk.server.NodeManager;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNodeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.OpcUaDataType;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fidelity suite (Stage C) — asserts that the NodeId identifier the emulator assigns to a construct
 * matches the identifier Ignition's real Allen-Bradley Logix driver assigns to the same construct,
 * per the normative grammar in {@code docs/plans/ADDRESSING.md}. That NodeId match is the entire
 * "develop on emulator, swap in the real PLC later" contract.
 *
 * <p><b>Scope of this class.</b> It covers the C1 (canonical NodeIds), C2 (full array expansion),
 * C3 (DWORD-packed BOOL arrays), C4 (predefined structured-type member tables, §5.9),
 * C5 (ExternalAccess/OpcUaAccess, AOI element-name parsing, module I/O tags §5.10) and C6/C8
 * (atomic type mapping, initial values) fixes and their §5 checklist rows. All Stage C
 * assertions are now enabled; nothing in this suite is pending other work.
 *
 * <p><b>Enable mechanism.</b> {@code @Tag("fidelity")} tests are excluded from the default
 * {@code ./gradlew test}; run them via {@code ./gradlew :gateway:fidelityTest} or
 * {@code ./gradlew :gateway:test -PincludeFidelity}.
 *
 * <p>Each assertion traces to an ADDRESSING.md rule (DOC-CONFIRMED unless noted). The stubbed
 * {@link AddressSpaceBuilder.NodeContext} echoes each identifier verbatim as the NodeId's string
 * identifier, so a created node's {@code getNodeId().getIdentifier()} is exactly the canonical
 * identifier under test (no live OPC-UA server or Ignition gateway required).
 */
@Tag("fidelity")
class AddressSpaceBuilderFidelityTest {

    private static final String CORPUS_NODEBLUE = "corpus/CompactLogix5370-1769L33ER-fw33-NodeblueAI.L5X";
    private static final String CORPUS_STELLENTUS = "corpus/CompactLogix5370-1769L33ER-fw30-stellentus.L5X";
    private static final String CORPUS_L5SHARP = "corpus/ControlLogix-1756L83E-fw36-L5Sharp.L5X";
    private static final String CORPUS_IOTRUSTLAB_CONTROLLER =
        "corpus/ControlLogix-1756L72-fw37-iotrustlab-controller.L5X";
    private static final String CORPUS_DMROEDER = "corpus/CompactLogix5380-5069L320ERM-fw34-dmroeder.L5X";
    private static final String SYNTHETIC_BOOLPACK = "test-files/synthetic-boolpack.l5x";
    private static final String SYNTHETIC_CONTROL = "test-files/synthetic-control.l5x";
    private static final String SYNTHETIC_PID = "test-files/synthetic-pid.l5x";
    private static final String SYNTHETIC_PIDE_ALIAS = "test-files/synthetic-pide-alias.l5x";

    private AddressSpaceBuilder builder;
    private AddressSpaceBuilder.NodeContext context;
    private UaFolderNode rootNode;
    private List<UaNode> addedNodes;

    @BeforeEach
    void setUp() {
        UaNodeContext uaNodeContext = org.mockito.Mockito.mock(UaNodeContext.class);
        @SuppressWarnings("unchecked")
        NodeManager<UaNode> nodeManager = org.mockito.Mockito.mock(NodeManager.class);
        OpcUaServer server = org.mockito.Mockito.mock(OpcUaServer.class);
        org.mockito.Mockito.when(uaNodeContext.getNodeManager()).thenReturn(nodeManager);
        org.mockito.Mockito.when(uaNodeContext.getServer()).thenReturn(server);
        org.mockito.Mockito.when(server.getNamespaceTable()).thenReturn(new NamespaceTable());

        DeviceContext deviceContext = org.mockito.Mockito.mock(DeviceContext.class);
        org.mockito.Mockito.when(deviceContext.nodeId(org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> {
                Object identifier = inv.getArgument(0);
                return new NodeId(1, String.valueOf(identifier));
            });
        org.mockito.Mockito.when(deviceContext.qualifiedName(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> {
                String name = inv.getArgument(0);
                return new QualifiedName(1, name);
            });

        context = new AddressSpaceBuilder.NodeContext(uaNodeContext, deviceContext);
        rootNode = context.createFolder("TestDevice", "TestDevice");

        addedNodes = new ArrayList<>();
        builder = new AddressSpaceBuilder(
            addedNodes::add,
            "TestDevice",
            LoggerFactory.getLogger(AddressSpaceBuilderFidelityTest.class)
        );
    }

    // =====================================================================================
    // §5.1 Controller scope — CompactLogix5370-1769L33ER-fw33-NodeblueAI.L5X
    // =====================================================================================

    @Test
    @DisplayName("§5.1 controller tags get bare canonical NodeIds — no Controller:Global. prefix (C1)")
    void controllerScopeBareIdentifiers() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_NODEBLUE);

        // Atomic controller tags are bare (ADDRESSING.md §3.1).
        assertType(nodes, "SystemClock", OpcUaDataType.Int32);
        assertType(nodes, "EmergencyStop", OpcUaDataType.Boolean);

        // UDT instance + members are dotted off the bare tag id (ADDRESSING.md §3.3).
        assertThat(nodes).containsKey("Motor_1");
        assertType(nodes, "Motor_1.Running", OpcUaDataType.Boolean);
        assertType(nodes, "Motor_1.Speed", OpcUaDataType.Float);
        assertType(nodes, "Motor_1.RunTime", OpcUaDataType.Int32);

        // The legacy long form must NOT exist (ADDRESSING.md §5.1 absence assertion).
        assertThat(nodes).doesNotContainKey("Controller:Global.SystemClock");
        assertThat(nodes).doesNotContainKey("Controller:Global.Motor_1.Speed");
    }

    // =====================================================================================
    // §5.2 Program scope — same file, MainProgram / MotorProgram
    // =====================================================================================

    @Test
    @DisplayName("§5.2 program tags use the Program:<Prog>. selector, not the old Programs. form (C1)")
    void programScopeSelectorForm() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_NODEBLUE);

        // TIMER members inherit the program prefix (ADDRESSING.md §3.2).
        assertThat(nodes).containsKey("Program:MainProgram.MainTimer");
        assertType(nodes, "Program:MainProgram.MainTimer.PRE", OpcUaDataType.Int32);
        assertThat(nodes)
            .containsKeys("Program:MainProgram.MainTimer.ACC", "Program:MainProgram.MainTimer.EN",
                "Program:MainProgram.MainTimer.TT", "Program:MainProgram.MainTimer.DN");
        // TIMER has NO .ER member (ADDRESSING.md §3.11, C4) — the phantom .ER is gone.
        assertThat(nodes).doesNotContainKey("Program:MainProgram.MainTimer.ER");

        // COUNTER members (ADDRESSING.md §3.11 COUNTER set is already correct in the emulator).
        assertType(nodes, "Program:MainProgram.CycleCounter.ACC", OpcUaDataType.Int32);
        assertThat(nodes)
            .containsKeys("Program:MainProgram.CycleCounter.CU", "Program:MainProgram.CycleCounter.CD",
                "Program:MainProgram.CycleCounter.DN", "Program:MainProgram.CycleCounter.OV",
                "Program:MainProgram.CycleCounter.UN");

        // Program-scoped atomic in a second program.
        assertType(nodes, "Program:MotorProgram.VFD_SpeedRef", OpcUaDataType.Float);

        // The old Programs.<Prog>.<tag> form must be entirely gone (ADDRESSING.md §5.2 absence).
        assertThat(nodes.keySet()).noneMatch(id -> id.startsWith("Programs."));
    }

    // =====================================================================================
    // §5.3 UDT/AOI member depth — same file (AOI Motor_Control instance Motor1_AOI)
    // =====================================================================================

    @Test
    @DisplayName("§5.3 AOI backing-tag members carry the canonical program prefix (C1, unblocked by C5b)")
    void aoiMemberCanonicalPaths() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_NODEBLUE);

        assertThat(nodes).containsKey("Program:MainProgram.Motor1_AOI");
        // Visible Input/Output parameters are exposed off the canonical instance id (§3.3).
        assertType(nodes, "Program:MainProgram.Motor1_AOI.Start", OpcUaDataType.Boolean);
        assertType(nodes, "Program:MainProgram.Motor1_AOI.Running", OpcUaDataType.Boolean);
        // The spec row explicitly lists the standard EnableIn/EnableOut parameters as exposed
        // (ADDRESSING.md §5.3; the corpus declares them ExternalAccess="Read Only", not "None",
        // so §3.12 keeps them). FIX-13: these were previously unasserted - and unexposed, because
        // L5XParser.parseAOI hard-skipped both names (the AOI expansion gap this closes).
        assertType(nodes, "Program:MainProgram.Motor1_AOI.EnableIn", OpcUaDataType.Boolean);
        assertType(nodes, "Program:MainProgram.Motor1_AOI.EnableOut", OpcUaDataType.Boolean);
    }

    @Test
    @DisplayName("§5.3 UDT member depth: nested struct members carry the full canonical path (C1) — "
        + "controller UDT instance stands in for the AOI blocked by the parser gap above")
    void udtMemberDepthCanonicalPaths() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_NODEBLUE);

        // A controller-scoped UDT instance: members dot off the bare tag id, arbitrary depth (§3.3).
        assertThat(nodes).containsKey("Motor_1");
        assertType(nodes, "Motor_1.Running", OpcUaDataType.Boolean);
        assertType(nodes, "Motor_1.RunCommand", OpcUaDataType.Boolean);
        // A program-scoped predefined (TIMER) instance: depth-2 canonical member path (§3.2/§3.11).
        assertType(nodes, "Program:MainProgram.MainTimer.ACC", OpcUaDataType.Int32);
    }

    @Test
    @DisplayName("§5.3 AOI LocalTags with ExternalAccess=None are omitted (C5a)")
    void aoiExternalAccessNoneOmitted() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_NODEBLUE);
        assertThat(nodes).doesNotContainKey("Program:MainProgram.Motor1_AOI.RunLatch");
        assertThat(nodes.keySet()).noneMatch(id -> id.startsWith("Program:MainProgram.Motor1_AOI.FaultTimer"));
    }

    @Test
    @DisplayName("§5.3/§3.12 AOI Output parameters with ExternalAccess=\"Read Only\" are created "
        + "read-only, with no write filter (C5a)")
    void aoiReadOnlyOutputParameterEnforced() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_NODEBLUE);

        // "Running" is an AOI Output parameter with ExternalAccess="Read Only" in the corpus.
        assertThat(nodes).containsKey("Program:MainProgram.Motor1_AOI.Running");
        UaVariableNode running = (UaVariableNode) nodes.get("Program:MainProgram.Motor1_AOI.Running");
        assertThat(org.eclipse.milo.opcua.sdk.core.AccessLevel.fromValue(running.getAccessLevel()))
            .as("Read Only parameter must be created with AccessLevel.READ_ONLY, no write filter")
            .isEqualTo(org.eclipse.milo.opcua.sdk.core.AccessLevel.READ_ONLY);
        assertThat(org.eclipse.milo.opcua.sdk.core.AccessLevel.fromValue(running.getUserAccessLevel()))
            .isEqualTo(org.eclipse.milo.opcua.sdk.core.AccessLevel.READ_ONLY);

        // "Start" is a Read/Write Input parameter and must remain fully read-write.
        assertThat(nodes).containsKey("Program:MainProgram.Motor1_AOI.Start");
        UaVariableNode start = (UaVariableNode) nodes.get("Program:MainProgram.Motor1_AOI.Start");
        assertThat(org.eclipse.milo.opcua.sdk.core.AccessLevel.fromValue(start.getAccessLevel()))
            .isEqualTo(org.eclipse.milo.opcua.sdk.core.AccessLevel.READ_WRITE);
    }

    @Test
    @DisplayName("§3.12 Constant=\"true\" controller tags are created read-only (C5a)")
    void constantTagsAreReadOnly() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_IOTRUSTLAB_CONTROLLER);

        // "True"/"False" are Constant="true" ExternalAccess="Read/Write" BOOL tags in the corpus -
        // ADDRESSING.md §3.12 requires Constant tags to be read-only regardless of ExternalAccess.
        assertThat(nodes).containsKey("True");
        UaVariableNode trueTag = (UaVariableNode) nodes.get("True");
        assertThat(org.eclipse.milo.opcua.sdk.core.AccessLevel.fromValue(trueTag.getAccessLevel()))
            .isEqualTo(org.eclipse.milo.opcua.sdk.core.AccessLevel.READ_ONLY);
    }

    // =====================================================================================
    // §5.4 1-D and multi-dim arrays — CompactLogix5370-1769L33ER-fw30-stellentus.L5X
    // =====================================================================================

    @Test
    @DisplayName("§5.4 1-D and 2-D arrays expand to indexed elements; multi-dim uses [i,j] (C2)")
    void arrayExpansionOneAndTwoDim() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_STELLENTUS);

        // INFO_ABOUT : INT[2] controller-scoped -> bare INFO_ABOUT[0], INFO_ABOUT[1] (§3.4).
        assertType(nodes, "INFO_ABOUT[0]", OpcUaDataType.Int16);
        assertType(nodes, "INFO_ABOUT[1]", OpcUaDataType.Int16);

        // multiArray : INT Dimensions="2 4" -> comma-indexed elements (ADDRESSING.md §3.5).
        // SPEC NOTE: ADDRESSING.md §5.4 lists these as bare "multiArray[0,0]", but in the real
        // stellentus file multiArray is PROGRAM-scoped (program "dancer"), so the canonical id
        // carries the Program:dancer selector. The comma-index expansion (the actual C2 assertion)
        // is unchanged; only the scope prefix differs from the spec's example.
        assertType(nodes, "Program:dancer.multiArray[0,0]", OpcUaDataType.Int16);
        assertType(nodes, "Program:dancer.multiArray[0,3]", OpcUaDataType.Int16);
        assertType(nodes, "Program:dancer.multiArray[1,0]", OpcUaDataType.Int16);
        assertType(nodes, "Program:dancer.multiArray[1,3]", OpcUaDataType.Int16);

        // Must be comma-indexed, not first-dimension-only (ADDRESSING.md §5.4 absence).
        assertThat(nodes).doesNotContainKey("Program:dancer.multiArray[0]");
        assertThat(nodes).doesNotContainKey("Program:dancer.multiArray[1]");
        // And never the bare (scope-stripped) form.
        assertThat(nodes).doesNotContainKey("multiArray[0,0]");
    }

    // =====================================================================================
    // §5.5 Multi-dim (2-D and 3-D) large — ControlLogix-1756L83E-fw36-L5Sharp.L5X
    // Doubles as the OpcUaAccess-distinction test: these tags carry OpcUaAccess="None" but
    // ExternalAccess="Read/Write", so they MUST be emitted (ADDRESSING.md §3.12, §5.5).
    // =====================================================================================

    @Test
    @DisplayName("§5.5 2-D array yields exactly 15 elements; 3-D array uses [i,j,k]; OpcUaAccess=None "
        + "tags are still emitted (C2)")
    void multiDimArraysAndOpcUaAccessIgnored() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_L5SHARP);

        // MultiDimensionalArray : DINT Dimensions="3 5" -> 15 elements [0,0]..[2,4].
        assertType(nodes, "MultiDimensionalArray[0,0]", OpcUaDataType.Int32);
        assertType(nodes, "MultiDimensionalArray[2,4]", OpcUaDataType.Int32);
        long count = nodes.keySet().stream().filter(id -> id.startsWith("MultiDimensionalArray[")).count();
        assertThat(count).as("2-D DINT[3,5] must expand to 15 elements").isEqualTo(15);

        // TestArray : DINT Dimensions="1 1 2" -> [0,0,0], [0,0,1] (ADDRESSING.md §3.5, 3-D).
        assertType(nodes, "TestArray[0,0,0]", OpcUaDataType.Int32);
        assertType(nodes, "TestArray[0,0,1]", OpcUaDataType.Int32);
    }

    // =====================================================================================
    // §5.7 BOOL array DWORD packing — synthetic fixture synthetic-boolpack.l5x
    // =====================================================================================

    @Test
    @DisplayName("§5.7 BOOL arrays are DWORD-packed to Tag[word].bit; bare Tag[N] must not exist (C3)")
    void boolArrayDwordPacking() throws IOException {
        Map<String, UaNode> nodes = build(SYNTHETIC_BOOLPACK);

        // PackBits : BOOL[32] -> single DWORD, bits .0 .. .31 (ADDRESSING.md §3.8).
        assertType(nodes, "PackBits[0].0", OpcUaDataType.Boolean);
        assertType(nodes, "PackBits[0].31", OpcUaDataType.Boolean);

        // PackBits2 : BOOL[64] -> two DWORDs; element 32 -> [1].0, element 63 -> [1].31.
        assertType(nodes, "PackBits2[0].0", OpcUaDataType.Boolean);
        assertType(nodes, "PackBits2[1].0", OpcUaDataType.Boolean);
        assertType(nodes, "PackBits2[1].31", OpcUaDataType.Boolean);

        // The bare BOOL-element nodes the real driver rejects must NOT exist (ADDRESSING.md §5.7).
        assertThat(nodes).doesNotContainKey("PackBits[0]");
        assertThat(nodes).doesNotContainKey("PackBits[2]");
        assertThat(nodes).doesNotContainKey("PackBits2[32]");
        assertThat(nodes).doesNotContainKey("PackBits2[2]");

        // A scalar (non-array) BOOL is still a plain boolean node, not packed.
        assertType(nodes, "PlainBool", OpcUaDataType.Boolean);
        assertThat(nodes).doesNotContainKey("PlainBool[0].0");

        // Exactly 32 + 64 packed bit nodes exist for the two BOOL arrays.
        long packBitsCount = nodes.keySet().stream().filter(id -> id.startsWith("PackBits[")).count();
        long packBits2Count = nodes.keySet().stream().filter(id -> id.startsWith("PackBits2[")).count();
        assertThat(packBitsCount).isEqualTo(32);
        assertThat(packBits2Count).isEqualTo(64);
    }

    @Test
    @DisplayName("§5.7/FIX-15 a BOOL array's decorated per-element Value=\"1\" reaches the packed "
        + "bit node's initial value (via AddressPolicy.boolArrayBit) - siblings default to false")
    void boolArrayElementValuePropagatesToPackedBit() throws IOException {
        Map<String, UaNode> nodes = build(SYNTHETIC_BOOLPACK);

        // PackBits[5] (element 5, Value="1") -> word 0, bit 5.
        UaVariableNode bit5 = (UaVariableNode) nodes.get("PackBits[0].5");
        assertThat(bit5.getValue().getValue().getValue())
            .as("PackBits element 5's exported Value=1 must reach PackBits[0].5, not default false")
            .isEqualTo(true);

        // An element with no <Element> entry in the export still defaults to false.
        UaVariableNode bit6 = (UaVariableNode) nodes.get("PackBits[0].6");
        assertThat(bit6.getValue().getValue().getValue()).isEqualTo(false);

        // PackBits2 element 40 -> word 1, bit 8.
        UaVariableNode packBits2Bit8 = (UaVariableNode) nodes.get("PackBits2[1].8");
        assertThat(packBits2Bit8.getValue().getValue().getValue())
            .as("PackBits2 element 40's exported Value=1 must reach PackBits2[1].8")
            .isEqualTo(true);
    }

    // =====================================================================================
    // FIX-15 — array-element initial values from a real Studio 5000 export (release blocker,
    // plc-dod3/item4-hotreload.txt) — CompactLogix5370-1769L33ER-fw30-stellentus.L5X
    // =====================================================================================

    @Test
    @DisplayName("FIX-15: a real export's 1-D array element Values reach the canonical element "
        + "nodes, not the type default (INFO_ABOUT, controller-scoped INT[2])")
    void arrayElementValuesFromRealExport() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_STELLENTUS);

        UaVariableNode elem0 = (UaVariableNode) nodes.get("INFO_ABOUT[0]");
        UaVariableNode elem1 = (UaVariableNode) nodes.get("INFO_ABOUT[1]");
        assertThat(elem0.getValue().getValue().getValue())
            .as("INFO_ABOUT[0]'s real export value (-2925) must appear, not the INT default (0)")
            .isEqualTo((short) -2925);
        assertThat(elem1.getValue().getValue().getValue()).isEqualTo((short) 1952);
    }

    @Test
    @DisplayName("FIX-15: a real export's 2-D array element Values reach the canonical "
        + "comma-indexed element nodes (multiArray, program-scoped INT Dimensions=\"2 4\")")
    void multiDimArrayElementValuesFromRealExport() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_STELLENTUS);

        UaVariableNode first = (UaVariableNode) nodes.get("Program:dancer.multiArray[0,0]");
        UaVariableNode last = (UaVariableNode) nodes.get("Program:dancer.multiArray[1,3]");
        assertThat(first.getValue().getValue().getValue())
            .as("multiArray[0,0]'s real export value (5) must appear, not the INT default (0)")
            .isEqualTo((short) 5);
        assertThat(last.getValue().getValue().getValue())
            .as("multiArray[1,3]'s real export value (194993, narrowed to Int16 by "
                + "(short) truncation exactly like any other INT-typed value/AddressSpaceBuilder "
                + "narrowing - the value in the export itself overflows a real Logix INT) must "
                + "appear as its truncated form (-1615), not the type default (0)")
            .isEqualTo((short) -1615);
    }

    // =====================================================================================
    // §5.9 Predefined member correctness — synthetic fixtures + corpus (C4)
    // =====================================================================================

    @Test
    @DisplayName("§5.9 CONTROL: all 10 members present incl. .UL, .IN, .FD (C4)")
    void controlFullMemberSet() throws IOException {
        Map<String, UaNode> nodes = build(SYNTHETIC_CONTROL);

        assertThat(nodes).containsKey("TestControl");
        assertType(nodes, "TestControl.LEN", OpcUaDataType.Int32);
        assertType(nodes, "TestControl.POS", OpcUaDataType.Int32);
        assertThat(nodes).containsKeys(
            "TestControl.EN", "TestControl.EU", "TestControl.DN", "TestControl.EM",
            "TestControl.ER", "TestControl.UL", "TestControl.IN", "TestControl.FD");

        long controlMemberCount = nodes.keySet().stream()
            .filter(id -> id.startsWith("TestControl.")).count();
        assertThat(controlMemberCount).as("CONTROL has exactly 10 members").isEqualTo(10);
    }

    @Test
    @DisplayName("§5.9 PID (classic): status/parameter members present, .ERR is Float not Int (C4)")
    void pidFullMemberSet() throws IOException {
        Map<String, UaNode> nodes = build(SYNTHETIC_PID);

        assertThat(nodes).containsKey("TestPid");
        assertThat(nodes).containsKeys(
            "TestPid.SP", "TestPid.KP", "TestPid.KI", "TestPid.KD", "TestPid.OUT", "TestPid.SO",
            "TestPid.MAXO");
        assertType(nodes, "TestPid.SP", OpcUaDataType.Float);
        // PID.ERR is a scaled REAL, distinct from MESSAGE.ERR (INT) (ADDRESSING.md §3.11).
        assertType(nodes, "TestPid.ERR", OpcUaDataType.Float);
    }

    @Test
    @DisplayName("§5.9 PIDE (PID_ENHANCED): type-name alias resolves and core members are present (C4)")
    void pideAliasAndCoreMembers() throws IOException {
        Map<String, UaNode> nodes = build(SYNTHETIC_PIDE_ALIAS);

        // The tag's DataType is the genuine L5X string PID_ENHANCED, not PIDE (ADDRESSING.md
        // §3.11) — if the alias did not resolve, none of these member nodes would exist.
        assertThat(nodes).containsKey("TestPide");
        assertType(nodes, "TestPide.PV", OpcUaDataType.Float);
        assertType(nodes, "TestPide.SP", OpcUaDataType.Float);
        assertType(nodes, "TestPide.CVEU", OpcUaDataType.Float);
        assertType(nodes, "TestPide.PGain", OpcUaDataType.Float);
        assertType(nodes, "TestPide.InstructFault", OpcUaDataType.Boolean);
    }

    // =====================================================================================
    // §5.10 I/O modules — ControlLogix-1756L72-fw37-iotrustlab-controller.L5X
    // =====================================================================================

    @Test
    @DisplayName("§5.10 module I/O tags: a parsed digital I/O module contributes an :I.Data node "
        + "(ADDRESSING.md §3.13, INFERRED, C5c)")
    void moduleIoDataTagsSynthesised() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_IOTRUSTLAB_CONTROLLER);

        // Dig_In_1 is a 1756-IB32/B digital input module at <Port Address="2"> - its InputTag
        // Structure has a top-level "Data" (DINT) member (ADDRESSING.md §3.13/§5.10).
        assertType(nodes, "Dig_In_1:I.Data", OpcUaDataType.Int32);

        // Dig_Out_1 is a 1756-OB32 digital output module - it has BOTH an input status echo
        // (:I.Data) and the actual output command (:O.Data).
        assertType(nodes, "Dig_Out_1:I.Data", OpcUaDataType.Int32);
        assertType(nodes, "Dig_Out_1:O.Data", OpcUaDataType.Int32);

        // An_IN_1 is an analog module (1756-IF16/B) whose InputTag exposes per-channel
        // Ch0Data..Ch7Data members, not a single top-level "Data" member - ADDRESSING.md §3.13's
        // scope guidance is to not guess at that layout, so it must contribute no :I tag at all.
        assertThat(nodes.keySet()).noneMatch(id -> id.startsWith("An_IN_1:I"));

        // The "Local" pseudo-module (the controller/chassis itself) has no Connections and must
        // not contribute a tag either.
        assertThat(nodes.keySet()).noneMatch(id -> id.startsWith("Local:I") || id.startsWith("Local:O"));
    }

    // =====================================================================================
    // §3.14 Atomic type mapping — v32+ unsigned atomics (C6) — ControlLogix-1756L83E-fw36-L5Sharp.L5X
    // These tags also carry OpcUaAccess="None" (ExternalAccess="Read/Write"), reinforcing §5.5's
    // OpcUaAccess-ignored assertion, and SimpleUSint's Value="255" doubles as a C8 initial-value
    // check (255 does not fit a signed SByte, so this also proves the unsigned Byte mapping is
    // exercised end-to-end, not just type-labelled).
    // =====================================================================================

    @Test
    @DisplayName("§3.14 v32+ unsigned atomics map to their unsigned OPC-UA types and keep their "
        + "real initial value (C6, C8)")
    void unsignedAtomicTypeMapping() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_L5SHARP);

        assertType(nodes, "SimpleUSint", OpcUaDataType.Byte);
        assertType(nodes, "SimpleUInt", OpcUaDataType.UInt16);
        assertType(nodes, "SimpleUDint", OpcUaDataType.UInt32);
        assertType(nodes, "SimpleULint", OpcUaDataType.UInt64);

        UaVariableNode simpleUSint = (UaVariableNode) nodes.get("SimpleUSint");
        Object value = simpleUSint.getValue().getValue().getValue();
        assertThat(value)
            .as("SimpleUSint Value=\"255\" from the real export must reach the built node (C8)")
            .isEqualTo(org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte(255));
    }

    // =====================================================================================
    // C8 — initial values read from the decorated Value attribute — dmroeder corpus file
    // =====================================================================================

    @Test
    @DisplayName("C8: a real export's DataValue Value attribute becomes the node's initial value, "
        + "not the type default")
    void initialValueReadFromDataValueAttribute() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_DMROEDER);

        // Program:GetMEDName.CharCount is a program-scoped DINT with Value="18" in the real
        // export - before C8, extractValue() never read the Value attribute, so every tag
        // silently started at its type default (0) regardless of the file's contents.
        assertType(nodes, "Program:GetMEDName.CharCount", OpcUaDataType.Int32);
        UaVariableNode charCount = (UaVariableNode) nodes.get("Program:GetMEDName.CharCount");
        assertThat(charCount.getValue().getValue().getValue())
            .as("CharCount's real export value (18) must appear on the built node, not the DINT default (0)")
            .isEqualTo(18);
    }

    // =====================================================================================
    // §5.6 STRING — ControlLogix-1756L83E-fw36-L5Sharp.L5X (FIX-5)
    // =====================================================================================

    @Test
    @DisplayName("§5.6 base STRING keeps a parent String value AND exposes .LEN/.DATA[i] members "
        + "(ADDRESSING.md §3.10, FIX-5)")
    void baseStringExposesLenAndDataMembers() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_L5SHARP);

        // SimpleString (STRING) -> parent "SimpleString" (String value) + .LEN (Int32) +
        // .DATA[0] (SByte) - the spec's exact form, not a paraphrase (§5.6).
        assertType(nodes, "SimpleString", OpcUaDataType.String);
        assertType(nodes, "SimpleString.LEN", OpcUaDataType.Int32);
        assertType(nodes, "SimpleString.DATA[0]", OpcUaDataType.SByte);

        // .DATA expands to the STRING type's DATA dimension - 82 for base STRING (§5.6).
        long dataMemberCount = nodes.keySet().stream()
            .filter(id -> id.startsWith("SimpleString.DATA[")).count();
        assertThat(dataMemberCount).as("base STRING .DATA must expand to 82 elements").isEqualTo(82);
        assertType(nodes, "SimpleString.DATA[81]", OpcUaDataType.SByte);
        assertThat(nodes).doesNotContainKey("SimpleString.DATA[82]");

        // Regression guard: a custom STRING-family type (FakeString, DataType="FakeString" with
        // an ordinary <DataType> declaring LEN/DATA[23]) must keep working exactly as before -
        // Object-node members only, no dual scalar value (FIX-5 is scoped to base STRING only).
        assertThat(nodes).containsKey("FakeStringTag");
        assertType(nodes, "FakeStringTag.LEN", OpcUaDataType.Int32);
        assertType(nodes, "FakeStringTag.DATA[0]", OpcUaDataType.SByte);
        long fakeStringDataCount = nodes.keySet().stream()
            .filter(id -> id.startsWith("FakeStringTag.DATA[")).count();
        assertThat(fakeStringDataCount).as("FakeString .DATA must keep its own dimension (23)").isEqualTo(23);
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    /** Parses a classpath L5X resource and builds its address space, returning nodes by identifier. */
    private Map<String, UaNode> build(String resourcePath) throws IOException {
        String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        String content;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertThat(in).as("test resource %s must exist on the classpath", resourcePath).isNotNull();
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        PLCParser parser = ParserFactory.getParser(fileName);
        assertThat(parser).as("a parser must be registered for %s", fileName).isNotNull();
        JsonObject parsed = parser.parseContent(content, fileName);
        assertThat(parsed).as("%s must parse", resourcePath).isNotNull();

        builder.buildAddressSpace(parsed, rootNode, context);

        Map<String, UaNode> byId = new LinkedHashMap<>();
        for (UaNode node : addedNodes) {
            byId.put(node.getNodeId().getIdentifier().toString(), node);
        }
        return byId;
    }

    /** Asserts a variable node exists at {@code identifier} with the given OPC-UA data type. */
    private static void assertType(Map<String, UaNode> nodes, String identifier, OpcUaDataType expected) {
        assertThat(nodes).as("node %s must exist", identifier).containsKey(identifier);
        UaNode node = nodes.get(identifier);
        assertThat(node).as("%s must be a variable node", identifier).isInstanceOf(UaVariableNode.class);
        assertThat(((UaVariableNode) node).getDataType())
            .as("%s data type", identifier)
            .isEqualTo(expected.getNodeId());
    }
}
