package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.logixemulator.gateway.parser.PLCParser;
import com.inductiveautomation.logixemulator.gateway.parser.ParserFactory;
import org.eclipse.milo.opcua.sdk.server.NodeManager;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNodeContext;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration-level tests for {@link AddressSpaceBuilder#buildAddressSpace}.
 *
 * <p>These tests parse real files (the module's own {@code simple.l5x} fixture, plus the
 * licence-clean real-world corpus vendored in {@code src/test/resources/corpus/}, see
 * {@code ATTRIBUTION.md}) through {@link ParserFactory} and then run the resulting parsed-tag
 * JSON through {@code buildAddressSpace()} against a stubbed {@link AddressSpaceBuilder.NodeContext}
 * — no live OPC-UA server or Ignition gateway is required.
 *
 * <p><b>Regression evidence (defect B1, {@code docs/plans/V10_FIDELITY_PLAN.md}):</b> before the
 * B1 fix, every corpus file containing an atomic-typed array tag (i.e. most of the corpus — a
 * Decorated-format {@code <Array>} element has no direct {@code <DataValue>} child, so
 * {@code L5XParser.extractValue()} fell back to the {@code "{structure}"} sentinel, which was then
 * copied onto every array element and handed to {@code AddressSpaceBuilder.getInitialValue()},
 * which called {@code JsonPrimitive.getAsInt()} on it) this test class failed with:
 *
 * <pre>
 * java.lang.NumberFormatException: For input string: "{structure}"
 *     at com.google.gson.JsonPrimitive.getAsInt(JsonPrimitive.java:228)
 *     at com.inductiveautomation.logixemulator.gateway.device.AddressSpaceBuilder.getInitialValue(AddressSpaceBuilder.java:465)
 *     at com.inductiveautomation.logixemulator.gateway.device.AddressSpaceBuilder.addAtomicTag(AddressSpaceBuilder.java:355)
 *     at com.inductiveautomation.logixemulator.gateway.device.AddressSpaceBuilder.addTag(AddressSpaceBuilder.java:266)
 *     at com.inductiveautomation.logixemulator.gateway.device.AddressSpaceBuilder.buildAddressSpace(AddressSpaceBuilder.java:99)
 * </pre>
 *
 * matching the gateway crash captured in {@code plc-dod/item2-addressspace-error.txt} (the
 * production DoD run that motivated this fix). After the B1 fix (lenient typed parsing in
 * {@code getInitialValue()}, no more {@code "{structure}"} sentinel from
 * {@code L5XParser.extractValue()}, and per-tag resilience in {@code buildAddressSpace()}) all
 * cases below build without exception.
 */
class AddressSpaceBuilderIntegrationTest {

    /** The module's own baseline fixture — must always build cleanly, before and after the fix. */
    private static final String SIMPLE_L5X = "test-files/simple.l5x";

    // Licence-clean real-world corpus (see corpus/ATTRIBUTION.md), listed inline in the
    // @ValueSource below (JLS requires annotation array values to be compile-time constants).
    // The unlicensed CompactLogix 1768 file is deliberately excluded from this repo and these
    // tests. L5K files are vendored for future use but are out of scope for Stage A per the
    // maintainer's 10/07/2026 decision (L5X is the primary Rockwell format).

    private AddressSpaceBuilder builder;
    private AddressSpaceBuilder.NodeContext context;
    private UaFolderNode rootNode;
    private List<UaNode> addedNodes;

    @BeforeEach
    void setUp() {
        // Stub UaNodeContext: only getNodeManager()/getServer() are exercised (by addReference(),
        // invoked when building folder/object/variable nodes and wiring Organizes/HasComponent
        // references); no live OPC-UA server is needed.
        UaNodeContext uaNodeContext = mock(UaNodeContext.class);
        @SuppressWarnings("unchecked")
        NodeManager<UaNode> nodeManager = mock(NodeManager.class);
        OpcUaServer server = mock(OpcUaServer.class);
        when(uaNodeContext.getNodeManager()).thenReturn(nodeManager);
        when(uaNodeContext.getServer()).thenReturn(server);
        when(server.getNamespaceTable()).thenReturn(new NamespaceTable());

        // Stub DeviceContext: only nodeId()/qualifiedName() (default methods) are used by
        // NodeContext's factory methods.
        DeviceContext deviceContext = mock(DeviceContext.class);
        when(deviceContext.nodeId(any())).thenAnswer(inv -> {
            Object identifier = inv.getArgument(0);
            return new NodeId(1, String.valueOf(identifier));
        });
        when(deviceContext.qualifiedName(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return new QualifiedName(1, name);
        });

        context = new AddressSpaceBuilder.NodeContext(uaNodeContext, deviceContext);
        rootNode = context.createFolder("TestDevice", "TestDevice");

        addedNodes = new ArrayList<>();
        builder = new AddressSpaceBuilder(
            addedNodes::add,
            "TestDevice",
            LoggerFactory.getLogger(AddressSpaceBuilderIntegrationTest.class)
        );
    }

    @Test
    @DisplayName("one malformed tag is skipped with a warning, not aborting the whole build (defect B1 per-tag resilience)")
    void testMalformedTagDoesNotAbortBuild() {
        JsonObject plcData = new JsonObject();
        JsonArray globalTags = new JsonArray();

        // Malformed: "data_type" key present but its value is JsonNull, so the early
        // "tag.get(\"data_type\") == null" guard does not catch it and
        // tag.get("data_type").getAsString() throws UnsupportedOperationException.
        JsonObject badTag = new JsonObject();
        badTag.addProperty("name", "BadTag");
        badTag.add("data_type", com.google.gson.JsonNull.INSTANCE);
        globalTags.add(badTag);

        JsonObject goodTag = new JsonObject();
        goodTag.addProperty("name", "GoodTag");
        goodTag.addProperty("data_type", "DINT");
        globalTags.add(goodTag);

        plcData.add("global_tags", globalTags);

        assertThatCode(() -> builder.buildAddressSpace(plcData, rootNode, context))
            .doesNotThrowAnyException();

        assertThat(addedNodes)
            .extracting(n -> n.getBrowseName().getName())
            .contains("GoodTag")
            .doesNotContain("BadTag");
    }

    @Test
    @DisplayName("simple.l5x builds without exception and creates tags")
    void testSimpleL5xBuilds() throws IOException {
        JsonObject parsed = parseResource(SIMPLE_L5X);
        assertThat(parsed).as("simple.l5x must parse").isNotNull();

        assertThatCode(() -> builder.buildAddressSpace(parsed, rootNode, context))
            .doesNotThrowAnyException();

        int totalTags = AddressSpaceBuilder.countTotalTags(parsed);
        assertThat(totalTags).isGreaterThan(0);
    }

    /**
     * {@code ControlLogix-1756L85E-fw38-RockwellAutomation.L5X} is a Rockwell-published
     * "dependencies" export (see {@code corpus/ATTRIBUTION.md}) — it carries real DataType/AOI
     * definitions but both the controller and {@code MainProgram} scopes export an empty
     * self-closing {@code <Tags/>}. Zero tags is the correct, non-buggy result for this file, so
     * it is exempted from the "at least one tag" assertion below (it still must not throw).
     */
    private static final String NO_TAG_INSTANCES_FIXTURE =
        "corpus/ControlLogix-1756L85E-fw38-RockwellAutomation.L5X";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "corpus/ControlLogix-1756L83E-fw36-L5Sharp.L5X",
        "corpus/ControlLogix-1756L72-fw37-iotrustlab-controller.L5X",
        "corpus/ControlLogix-1756L72-fw37-iotrustlab-simulator.L5X",
        "corpus/ControlLogix-1756L85E-fw38-RockwellAutomation.L5X",
        "corpus/CompactLogix5380-5069L320ERM-fw34-dmroeder.L5X",
        "corpus/CompactLogix5370-1769L33ER-fw33-NodeblueAI.L5X",
        "corpus/CompactLogix5370-1769L33ER-fw30-stellentus.L5X",
    })
    @DisplayName("real-world corpus L5X builds without exception and creates tags")
    void testCorpusFileBuilds(String resourcePath) throws IOException {
        JsonObject parsed = parseResource(resourcePath);
        assertThat(parsed).as("%s must parse", resourcePath).isNotNull();

        assertThatCode(() -> builder.buildAddressSpace(parsed, rootNode, context))
            .as("buildAddressSpace() must not throw for %s (regression guard for defect B1)", resourcePath)
            .doesNotThrowAnyException();

        int totalTags = AddressSpaceBuilder.countTotalTags(parsed);
        if (NO_TAG_INSTANCES_FIXTURE.equals(resourcePath)) {
            assertThat(totalTags).as("%s", resourcePath).isEqualTo(0);
        } else {
            assertThat(totalTags)
                .as("%s must yield at least one tag", resourcePath)
                .isGreaterThan(0);
        }
    }

    // =====================================================================================
    // FIX-12 — packed BOOL-array bit nodes must respect the tag's read_only flag
    // (ADDRESSING.md §3.12), exactly like createLeafVariable does for every other node kind.
    // =====================================================================================

    @Test
    @DisplayName("FIX-12: a read-only BOOL array's packed bit nodes are AccessLevel.READ_ONLY "
        + "with no write filter")
    void testReadOnlyBoolArrayBitNodes() {
        JsonObject plcData = boolArrayData("RoBits", 33, true);

        builder.buildAddressSpace(plcData, rootNode, context);

        List<org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode> bits = boolBitNodes("RoBits");
        assertThat(bits).hasSize(33);
        for (var bit : bits) {
            assertThat(org.eclipse.milo.opcua.sdk.core.AccessLevel.fromValue(bit.getAccessLevel()))
                .as("bit node %s must be read-only", bit.getNodeId().getIdentifier())
                .isEqualTo(org.eclipse.milo.opcua.sdk.core.AccessLevel.READ_ONLY);
            assertThat(org.eclipse.milo.opcua.sdk.core.AccessLevel.fromValue(bit.getUserAccessLevel()))
                .isEqualTo(org.eclipse.milo.opcua.sdk.core.AccessLevel.READ_ONLY);
            assertThat(bit.getFilterChain().getFilters())
                .as("a read-only bit node must carry no write filter")
                .isEmpty();
        }
    }

    @Test
    @DisplayName("FIX-12: a writable BOOL array's packed bit nodes stay READ_WRITE with a write filter")
    void testWritableBoolArrayBitNodes() {
        JsonObject plcData = boolArrayData("RwBits", 32, false);

        builder.buildAddressSpace(plcData, rootNode, context);

        List<org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode> bits = boolBitNodes("RwBits");
        assertThat(bits).hasSize(32);
        for (var bit : bits) {
            assertThat(org.eclipse.milo.opcua.sdk.core.AccessLevel.fromValue(bit.getAccessLevel()))
                .isEqualTo(org.eclipse.milo.opcua.sdk.core.AccessLevel.READ_WRITE);
            assertThat(bit.getFilterChain().getFilters())
                .as("a writable bit node must carry the pass-through write filter")
                .hasSize(1);
        }
    }

    @Test
    @DisplayName("FIX-12: a read-only BOOL member array inside a UDT gets read-only bit nodes too")
    void testReadOnlyBoolMemberArrayBitNodes() {
        JsonObject member = new JsonObject();
        member.addProperty("name", "Flags");
        member.addProperty("data_type", "BOOL");
        member.addProperty("dimensions", "32");
        member.addProperty("read_only", true);
        JsonArray members = new JsonArray();
        members.add(member);

        JsonObject tag = new JsonObject();
        tag.addProperty("name", "MyUdt");
        tag.addProperty("data_type", "SomeUdt");
        tag.add("udt_members", members);
        JsonArray globalTags = new JsonArray();
        globalTags.add(tag);
        JsonObject plcData = new JsonObject();
        plcData.add("global_tags", globalTags);

        builder.buildAddressSpace(plcData, rootNode, context);

        List<org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode> bits = boolBitNodes("MyUdt.Flags");
        assertThat(bits).hasSize(32);
        for (var bit : bits) {
            assertThat(org.eclipse.milo.opcua.sdk.core.AccessLevel.fromValue(bit.getAccessLevel()))
                .isEqualTo(org.eclipse.milo.opcua.sdk.core.AccessLevel.READ_ONLY);
            assertThat(bit.getFilterChain().getFilters()).isEmpty();
        }
    }

    /** Builds parsed data with a single 1-D BOOL array controller tag. */
    private static JsonObject boolArrayData(String name, int elements, boolean readOnly) {
        JsonObject tag = new JsonObject();
        tag.addProperty("name", name);
        tag.addProperty("data_type", "BOOL");
        tag.addProperty("isArray", true);
        tag.addProperty("dimensions", String.valueOf(elements));
        if (readOnly) {
            tag.addProperty("read_only", true);
        }
        JsonArray globalTags = new JsonArray();
        globalTags.add(tag);
        JsonObject plcData = new JsonObject();
        plcData.add("global_tags", globalTags);
        return plcData;
    }

    /** All created variable nodes whose identifier starts with {@code base}'s packed-bit prefix. */
    private List<org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode> boolBitNodes(String base) {
        return addedNodes.stream()
            .filter(n -> n.getNodeId().getIdentifier().toString().startsWith(base + "["))
            .map(n -> (org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode) n)
            .toList();
    }

    private JsonObject parseResource(String resourcePath) throws IOException {
        String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);

        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertThat(in).as("test resource %s must exist on the classpath", resourcePath).isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            PLCParser parser = ParserFactory.getParser(fileName);
            assertThat(parser).as("a parser must be registered for %s", fileName).isNotNull();

            return parser.parseContent(content, fileName);
        }
    }
}
