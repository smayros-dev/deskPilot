package pl.piomin.services.agentai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import pl.piomin.services.agentai.agent.AgentOrchestrator;
import pl.piomin.services.agentai.agent.SessionStore;
import pl.piomin.services.agentai.model.AgentRun;
import pl.piomin.services.agentai.model.AgentStep;
import pl.piomin.services.agentai.model.RunStatus;
import pl.piomin.services.agentai.model.StepType;
import pl.piomin.services.agentai.service.RunService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end agent runs against the real local model. Runs only when OLLAMA_IT=true.
 * Assertions are deliberately loose: a small local model words its answers differently each time.
 */
@SpringBootTest(properties = "agent.rag.index-on-startup=true")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "OLLAMA_IT", matches = "true")
class AgentSmokeTest {

    @Autowired
    private AgentOrchestrator orchestrator;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private RunService runService;

    @Test
    void answersFromKnowledgeBase() {
        String sessionId = sessionStore.create("smoke-user").id();

        AgentRun run = orchestrator.chat(sessionId, "My VPN connection drops every few minutes. What should I do?");

        print(run);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(runService.steps(run.getId())).extracting(AgentStep::getName).contains("search_knowledge_base");
        assertThat(run.getFinalAnswer()).containsIgnoringCase("IPSec");
    }

    @Test
    void ticketRequestPausesForApproval() {
        String sessionId = sessionStore.create("smoke-user").id();

        AgentRun run = orchestrator.chat(sessionId, "Please open a HIGH priority ticket: my laptop screen is cracked "
                + "and I need a replacement. I already tried an external monitor, it works.");

        print(run);
        assertThat(run.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(orchestrator.pendingApprovals(run.getId())).anySatisfy(call -> assertThat(call.name()).isEqualTo("create_ticket"));
    }

    private void print(AgentRun run) {
        System.out.printf("%n=== run %s: %s in %d ms, %d model calls, %d tool calls%n",
                run.getId(), run.getStatus(), run.getActiveMillis(), run.getIterations(), run.getToolCalls());
        runService.steps(run.getId()).forEach(step -> System.out.printf("  [%s] %s %s%n", step.getType(),
                step.getName() == null ? "" : step.getName(),
                step.getContent() == null ? "" : step.getContent().replace('\n', ' ')
                        .substring(0, Math.min(160, step.getContent().length()))));
        if (run.getStatus() != RunStatus.AWAITING_APPROVAL) {
            System.out.println("  answer: " + run.getFinalAnswer());
        }
        assertThat(runService.steps(run.getId())).extracting(AgentStep::getType).contains(StepType.USER_INPUT);
    }
}
