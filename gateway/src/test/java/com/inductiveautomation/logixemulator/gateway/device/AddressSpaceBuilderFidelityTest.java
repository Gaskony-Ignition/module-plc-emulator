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
import org.junit.jupiter.api.Disabled;
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
 * <p><b>Scope of this class.</b> It covers the C1 (canonical NodeIds), C2 (full array expansion)
 * and C3 (DWORD-packed BOOL arrays) fixes and their §5 checklist rows. Assertions that depend on
 * out-of-scope fixes (C4 predefined member tables, C5 External-Access / Modules) are marked
 * {@link Disabled} with the owning task, so this suite stays green for the C1-C3 work.
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
    private static final String SYNTHETIC_BOOLPACK = "test-files/synthetic-boolpack.l5x";

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

        // TIMER members inherit the program prefix (ADDRESSING.md §3.2). NOTE: .ER absence is a
        // C4 (predefined member-table) assertion, deliberately not checked here.
        assertThat(nodes).containsKey("Program:MainProgram.MainTimer");
        assertType(nodes, "Program:MainProgram.MainTimer.PRE", OpcUaDataType.Int32);
        assertThat(nodes)
            .containsKeys("Program:MainProgram.MainTimer.ACC", "Program:MainProgram.MainTimer.EN",
                "Program:MainProgram.MainTimer.TT", "Program:MainProgram.MainTimer.DN");

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
    @Disabled("Blocked by a pre-existing AOI-parser gap unrelated to C1-C3: L5XParser looks for "
        + "<AddOnInstructionDefinition> but this corpus file wraps AOIs as <AddOnInstruction>, so "
        + "Motor1_AOI is not expanded into members (it becomes a scalar). The canonical program-scope "
        + "member-path contract this row exercises is already proven by §5.2 (Program:MainProgram."
        + "MainTimer.PRE etc.). Enable once the AOI element-name parsing is fixed (C5-adjacent).")
    @DisplayName("§5.3 AOI backing-tag members carry the canonical program prefix (C1)")
    void aoiMemberCanonicalPaths() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_NODEBLUE);

        assertThat(nodes).containsKey("Program:MainProgram.Motor1_AOI");
        // Visible Input/Output parameters are exposed off the canonical instance id (§3.3).
        assertType(nodes, "Program:MainProgram.Motor1_AOI.Start", OpcUaDataType.Boolean);
        assertType(nodes, "Program:MainProgram.Motor1_AOI.Running", OpcUaDataType.Boolean);
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
    @Disabled("External-Access filtering is C5 (out of scope for C1-C3). ADDRESSING.md §5.3 requires "
        + "AOI LocalTags with ExternalAccess=None (RunLatch, FaultTimer) to be omitted; the parser "
        + "does not yet read ExternalAccess, so they are still emitted. Enable when C5 lands.")
    @DisplayName("§5.3 AOI LocalTags with ExternalAccess=None are omitted (C5)")
    void aoiExternalAccessNoneOmitted() throws IOException {
        Map<String, UaNode> nodes = build(CORPUS_NODEBLUE);
        assertThat(nodes).doesNotContainKey("Program:MainProgram.Motor1_AOI.RunLatch");
        assertThat(nodes.keySet()).noneMatch(id -> id.startsWith("Program:MainProgram.Motor1_AOI.FaultTimer"));
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
