package pl.piomin.services.deskpilot.mcp;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.piomin.services.deskpilot.TestSecurityConfiguration;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.agent.ModelCatalog;
import pl.piomin.services.deskpilot.agent.tool.AgentTools;
import pl.piomin.services.deskpilot.agent.tool.ToolRegistry;
import pl.piomin.services.deskpilot.model.KnowledgeArticle;
import pl.piomin.services.deskpilot.service.KnowledgeBaseService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * DeskPilot's MCP server over real HTTP with signed JWTs, and the same connection used as an external tool source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class McpServerIntegrationTest {

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;

    @MockitoBean
    private ModelCatalog modelCatalog;

    @LocalServerPort
    private int port;

    @Autowired
    private RestTestClient client;

    @Autowired
    private TestSecurityConfiguration.TestTokens tokens;

    @Autowired
    private McpToolNamePrefixGenerator prefixGenerator;

    @Autowired
    private List<AgentTools> agentTools;

    private McpSyncClient mcp;

    @AfterEach
    void closeClient() {
        if (mcp != null) {
            mcp.closeGracefully();
        }
    }

    @Test
    void endpointNeedsATokenWithTheUserRole() {
        Map<String, Object> initialize = Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "test", "version", "1")));

        client.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body(initialize).exchange()
                .expectStatus().isUnauthorized();
        client.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token("mallory"))
                .body(initialize).exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void exposesOnlyTheReadOnlyKnowledgeBaseTools() {
        mcp = connect("alice", "user");

        List<String> names = mcp.listTools().tools().stream().map(McpSchema.Tool::name).sorted().toList();

        assertThat(names).containsExactly("get_knowledge_article", "search_knowledge_base");
    }

    @Test
    void searchesTheKnowledgeBaseWithTheLiveRetrievalSettings() {
        when(knowledgeBaseService.search(eq("vpn drops"), anyInt(), anyDouble(), anyBoolean()))
                .thenReturn(List.of(new KnowledgeBaseService.Hit(1, "Connecting to the corporate VPN", "Switch to IPSec", 0.87)));
        mcp = connect("alice", "user");

        McpSchema.CallToolResult result = mcp.callTool(new McpSchema.CallToolRequest("search_knowledge_base",
                Map.of("query", "vpn drops")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).contains("Connecting to the corporate VPN").contains("Switch to IPSec");
    }

    @Test
    void readsOneArticle() {
        when(knowledgeBaseService.find(4)).thenReturn(Optional.of(new KnowledgeArticle("Adding a network printer",
                "Open Printers & scanners.", "printer")));
        mcp = connect("alice", "user");

        McpSchema.CallToolResult result = mcp.callTool(new McpSchema.CallToolRequest("get_knowledge_article", Map.of("id", 4)));

        assertThat(text(result)).as(result.toString()).contains("Adding a network printer");
    }

    @Test
    void externalMcpToolsJoinTheRegistryWithPrefixedNamesAndAlwaysNeedApproval() {
        when(knowledgeBaseService.search(eq("printer"), anyInt(), anyDouble(), anyBoolean()))
                .thenReturn(List.of(new KnowledgeBaseService.Hit(4, "Adding a network printer", "Add PRN-3-02", 0.8)));
        mcp = connect("alice", "user");
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcp).toolNamePrefixGenerator(prefixGenerator).build();
        ToolRegistry registry = new ToolRegistry(agentTools, List.of(() -> List.of(provider.getToolCallbacks())));

        assertThat(registry.names()).contains("search_knowledge_base", "mcp_deskpilot_search_knowledge_base",
                "mcp_deskpilot_get_knowledge_article");
        assertThat(registry.isExternal("mcp_deskpilot_search_knowledge_base")).isTrue();
        assertThat(registry.isExternal("search_knowledge_base")).isFalse();
        ToolCallback external = registry.find("mcp_deskpilot_search_knowledge_base").orElseThrow();
        assertThat(external.call("{\"query\":\"printer\"}")).contains("Adding a network printer");
    }

    private McpSyncClient connect(String user, String... roles) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token(user, roles)))
                .build();
        McpSyncClient sync = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        sync.initialize();
        return sync;
    }

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .reduce("", String::concat);
    }
}
