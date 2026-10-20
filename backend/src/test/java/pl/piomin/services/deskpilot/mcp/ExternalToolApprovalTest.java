package pl.piomin.services.deskpilot.mcp;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.agent.AgentOrchestrator;
import pl.piomin.services.deskpilot.agent.AgentSettingsService;
import pl.piomin.services.deskpilot.agent.ModelCatalog;
import pl.piomin.services.deskpilot.agent.SessionService;
import pl.piomin.services.deskpilot.agent.tool.ExternalTools;
import pl.piomin.services.deskpilot.controller.SettingsController;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.service.KnowledgeBaseService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** An external (MCP) tool is offered to the model, but never runs without a human approval. */
@SpringBootTest
@Import({TestcontainersConfiguration.class, ExternalToolApprovalTest.FakeMcpServer.class})
@ActiveProfiles("test")
class ExternalToolApprovalTest {

    static final String TOOL = "mcp_github_create_issue";
    static final AtomicInteger CALLS = new AtomicInteger();

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeMcpServer {

        @Bean
        ExternalTools fakeGithubTools() {
            ToolCallback createIssue = new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder().name(TOOL).description("Create a GitHub issue")
                            .inputSchema("{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"}}}").build();
                }

                @Override
                public String call(String toolInput) {
                    CALLS.incrementAndGet();
                    return "{\"issue\":42}";
                }
            };
            return () -> List.of(createIssue);
        }
    }

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;

    @MockitoBean
    private ModelCatalog modelCatalog;

    @Autowired
    private AgentOrchestrator orchestrator;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private AgentSettingsService settingsService;

    @Autowired
    private SettingsController settingsController;

    @BeforeEach
    void setUp() {
        settingsService.reset();
        CALLS.set(0);
    }

    @Test
    void externalToolIsListedAndAlwaysWaitsForApproval() {
        assertThat(settingsController.tools()).filteredOn(t -> t.name().equals(TOOL)).singleElement()
                .satisfies(t -> {
                    assertThat(t.external()).isTrue();
                    assertThat(t.approvalByDefault()).isTrue();
                });
        assertThat(settingsService.current().approvalTools()).doesNotContain(TOOL);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", TOOL, "{\"title\":\"VPN down\"}")))
                        .build()),
                response(new AssistantMessage("Issue #42 created.")));
        String sessionId = sessionService.create("alice").getId();

        AgentRun paused = orchestrator.chat(sessionId, "Open a GitHub issue: VPN down");

        assertThat(paused.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(CALLS).hasValue(0);

        AgentRun done = orchestrator.decide(paused.getId(), new AgentOrchestrator.ApprovalDecision(true, null, "bob"));

        assertThat(done.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(CALLS).hasValue(1);
    }

    @Test
    void rejectedExternalToolDoesNotRun() {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", TOOL, "{\"title\":\"x\"}")))
                        .build()),
                response(new AssistantMessage("Not created.")));
        String sessionId = sessionService.create("alice").getId();
        AgentRun paused = orchestrator.chat(sessionId, "Open a GitHub issue");

        orchestrator.decide(paused.getId(), new AgentOrchestrator.ApprovalDecision(false, "not now", "bob"));

        assertThat(CALLS).hasValue(0);
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 20)).build());
    }
}
