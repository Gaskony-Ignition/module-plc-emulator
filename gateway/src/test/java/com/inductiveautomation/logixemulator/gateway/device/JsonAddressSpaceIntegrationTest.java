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
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Regression test for defect B2 ({@code docs/plans/V10_FIDELITY_PLAN.md}): a JSON tag file using
 * the flat {@code tags} shape (with {@code type}/{@code scope} fields - the shape real-world
 * hand-authored JSON tag files actually use, per {@code plc-dod/tags.json}) used to parse
 * successfully but yield zero OPC-UA tags, because {@code AddressSpaceBuilder.buildAddressSpace()}
 * only ever reads {@code global_tags}/{@code programs} and the parser passed the flat shape
 * through unchanged.
 *
 * <p>Drives the real end-to-end path a REST upload takes: {@link ParserFactory} picks the parser
 * by filename, then the parsed JSON is run through {@link AddressSpaceBuilder#buildAddressSpace}
 * against a stubbed {@link AddressSpaceBuilder.NodeContext} (no live OPC-UA server or Ignition
 * gateway required) - mirroring {@code AddressSpaceBuilderIntegrationTest}'s approach for the L5X
 * corpus.
 */
class JsonAddressSpaceIntegrationTest {

    private static final String TAGS_JSON = "test-files/tags.json";

    private AddressSpaceBuilder builder;
    private AddressSpaceBuilder.NodeContext context;
    private UaFolderNode rootNode;
    private List<UaNode> addedNodes;

    @BeforeEach
    void setUp() {
        UaNodeContext uaNodeContext = mock(UaNodeContext.class);
        @SuppressWarnings("unchecked")
        NodeManager<UaNode> nodeManager = mock(NodeManager.class);
        OpcUaServer server = mock(OpcUaServer.class);
        when(uaNodeContext.getNodeManager()).thenReturn(nodeManager);
        when(uaNodeContext.getServer()).thenReturn(server);
        when(server.getNamespaceTable()).thenReturn(new NamespaceTable());

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
            LoggerFactory.getLogger(JsonAddressSpaceIntegrationTest.class)
        );
    }

    @Test
    @DisplayName("JSON tag file (flat 'tags' shape) builds a non-empty address space end-to-end "
        + "(regression test for defect B2)")
    void testJsonTagsFileBuildsAddressSpace() throws IOException {
        String fileName = "tags.json";

        String content;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(TAGS_JSON)) {
            assertThat(in).as("test resource %s must exist on the classpath", TAGS_JSON).isNotNull();
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        PLCParser parser = ParserFactory.getParser(fileName);
        assertThat(parser).as("a parser must be registered for %s", fileName).isNotNull();

        JsonObject parsed = parser.parseContent(content, fileName);
        assertThat(parsed).as("%s must parse", fileName).isNotNull();

        assertThatCode(() -> builder.buildAddressSpace(parsed, rootNode, context))
            .doesNotThrowAnyException();

        // tags.json has 5 Controller:Global tags + 1 Program:MainProgram tag = 6 total.
        int totalTags = AddressSpaceBuilder.countTotalTags(parsed);
        assertThat(totalTags)
            .as("defect B2: JSON tag file must yield created OPC tags, not zero")
            .isEqualTo(6);

        assertThat(addedNodes)
            .extracting(n -> n.getBrowseName().getName())
            .contains("RampInt", "SineReal", "ToggleBool", "StaticWriteInt", "StaticWriteReal", "ProgCounter");
    }
}
