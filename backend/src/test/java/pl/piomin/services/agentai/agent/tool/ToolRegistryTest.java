package pl.piomin.services.agentai.agent.tool;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import pl.piomin.services.agentai.agent.TestAgentConfigs;
import pl.piomin.services.agentai.config.AgentProperties;
import pl.piomin.services.agentai.exception.NotFoundException;
import pl.piomin.services.agentai.guardrail.GuardrailService;
import pl.piomin.services.agentai.model.MemoryFact;
import pl.piomin.services.agentai.model.Ticket;
import pl.piomin.services.agentai.model.TicketPriority;
import pl.piomin.services.agentai.service.KnowledgeBaseService;
import pl.piomin.services.agentai.service.MemoryService;
import pl.piomin.services.agentai.service.TicketService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises tools through Spring AI's {@link ToolCallback} (JSON in, JSON out), the same path the agent uses.
 */
class ToolRegistryTest {

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final MemoryService memoryService = mock(MemoryService.class);
    private final GuardrailService guardrails = new GuardrailService(new AgentProperties(8,
            new AgentProperties.Rag(0.5, 3, false), new AgentProperties.Guardrails(4000, 6000, List.of())));
    private final ToolRegistry registry = new ToolRegistry(List.of(
            new KnowledgeBaseTools(knowledgeBaseService),
            new TicketTools(ticketService),
            new MemoryTools(memoryService, guardrails),
            new EscalationTools()));
    private final RunContext context = new RunContext("alice", "session-1", "run-1", TestAgentConfigs.defaults());

    @Test
    void registersAllTools() {
        assertThat(registry.names()).containsExactlyInAnyOrder(
                "search_knowledge_base", "get_ticket", "create_ticket", "save_memory", "recall_memories", "escalate_to_human");
    }

    @Test
    void onlyCreateTicketRequiresApprovalByDefault() {
        assertThat(registry.defaultApprovalTools()).containsExactly("create_ticket");
    }

    @Test
    void searchUsesTheRunsRagSettings() {
        call("search_knowledge_base", "{\"query\":\"printer\"}");

        verify(knowledgeBaseService).search("printer", 3, 0.55);
    }

    @Test
    void unknownToolIsNotFound() {
        assertThat(registry.find("delete_all_tickets")).isEmpty();
    }

    @Test
    void toolContextIsHiddenFromTheModel() {
        String schema = tool("create_ticket").getToolDefinition().inputSchema();

        assertThat(schema).contains("title", "description", "priority").doesNotContain("toolContext");
    }

    @Test
    void searchReturnsArticles() {
        when(knowledgeBaseService.search("vpn drops", 3, 0.55))
                .thenReturn(List.of(new KnowledgeBaseService.Hit(1, "VPN", "Use IPSec", 0.8123)));

        String result = call("search_knowledge_base", "{\"query\":\"vpn drops\"}");

        assertThat(result).contains("\"title\":\"VPN\"", "\"relevance\":0.81").doesNotContain("No relevant article");
    }

    @Test
    void searchWithoutHitsTellsTheAgentToReformulate() {
        when(knowledgeBaseService.search("cake recipe", 3, 0.55)).thenReturn(List.of());

        assertThat(call("search_knowledge_base", "{\"query\":\"cake recipe\"}")).contains("No relevant article");
    }

    @Test
    void searchRejectsBlankQuery() {
        assertThatThrownBy(() -> call("search_knowledge_base", "{\"query\":\" \"}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createTicketUsesTheRealUserNotModelInput() {
        when(ticketService.create("VPN broken", "Tried IPSec", TicketPriority.HIGH, "alice"))
                .thenReturn(new Ticket("VPN broken", "Tried IPSec", TicketPriority.HIGH, "alice"));

        String result = call("create_ticket",
                "{\"title\":\"VPN broken\",\"description\":\"Tried IPSec\",\"priority\":\"high\"}");

        assertThat(result).contains("\"requestedBy\":\"alice\"", "\"status\":\"OPEN\"");
        verify(ticketService).create("VPN broken", "Tried IPSec", TicketPriority.HIGH, "alice");
    }

    @Test
    void createTicketRejectsInvalidPriority() {
        assertThatThrownBy(() -> call("create_ticket",
                "{\"title\":\"VPN broken\",\"description\":\"d\",\"priority\":\"URGENT\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasRootCauseMessage("priority must be one of LOW, MEDIUM, HIGH, CRITICAL");
    }

    @Test
    void getTicketPropagatesNotFound() {
        when(ticketService.get(anyLong())).thenThrow(new NotFoundException("Ticket 7 not found"));

        assertThatThrownBy(() -> call("get_ticket", "{\"ticketId\":7}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasRootCauseMessage("Ticket 7 not found");
    }

    @Test
    void memoriesAreScopedToTheCurrentUser() {
        when(memoryService.recall("alice", "laptop")).thenReturn(List.of(new MemoryFact("alice", "Uses a MacBook")));

        call("save_memory", "{\"fact\":\"Prefers short answers\"}");
        String recalled = call("recall_memories", "{\"query\":\"laptop\"}");

        verify(memoryService).save("alice", "Prefers short answers");
        assertThat(recalled).contains("Uses a MacBook");
    }

    @Test
    void refusesToStoreSecretsInMemory() {
        assertThatThrownBy(() -> call("save_memory", "{\"fact\":\"My password: Hunter2!\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasRootCauseMessage("Refused: the fact looks like a password, key or card number. Never store secrets.");
    }

    @Test
    void escalationIsRecordedInRunContext() {
        call("escalate_to_human", "{\"reason\":\"User asked for a human\"}");

        assertThat(context.escalationReason()).contains("User asked for a human");
    }

    @Test
    void toolNeedingContextFailsWithoutIt() {
        assertThatThrownBy(() -> tool("save_memory").call("{\"fact\":\"x\"}"))
                .isInstanceOf(RuntimeException.class);
    }

    private String call(String toolName, String argumentsJson) {
        return tool(toolName).call(argumentsJson, context.toToolContext());
    }

    private ToolCallback tool(String name) {
        return registry.find(name).orElseThrow();
    }
}
