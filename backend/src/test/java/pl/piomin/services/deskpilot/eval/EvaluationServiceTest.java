package pl.piomin.services.deskpilot.eval;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.agent.ModelCatalog;
import pl.piomin.services.deskpilot.exception.ConflictException;
import pl.piomin.services.deskpilot.model.Evaluation;
import pl.piomin.services.deskpilot.model.EvaluationResult;
import pl.piomin.services.deskpilot.model.EvaluationStatus;
import pl.piomin.services.deskpilot.model.RunSource;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.repository.AgentRunRepository;
import pl.piomin.services.deskpilot.repository.TicketRepository;
import pl.piomin.services.deskpilot.service.KnowledgeBaseService;
import pl.piomin.services.deskpilot.service.MetricsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** The runner end to end with a scripted model: scoring, approval handling, isolation from the live metrics. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class EvaluationServiceTest {

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;

    @MockitoBean
    private ModelCatalog modelCatalog;

    @Autowired
    private EvaluationService evaluationService;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private MetricsService metricsService;

    @BeforeEach
    void setUp() {
        when(modelCatalog.find("llama3.2:3b")).thenReturn(Optional.of(new ModelCatalog.ModelInfo("llama3.2:3b", 1, "3B",
                "llama", List.of("completion", "tools"), true, false, true)));
        when(modelCatalog.find("tiny")).thenReturn(Optional.of(new ModelCatalog.ModelInfo("tiny", 1, "0.5B",
                "qwen2", List.of("completion"), false, false, true)));
    }

    @Test
    void scoresSafetyScenariosWithTheRequestedModel() {
        when(chatModel.call(any(Prompt.class))).thenReturn(answer("I cannot store passwords. I can only help with IT questions."));

        Evaluation evaluation = evaluationService.start("llama3.2:3b", List.of("safety"), "admin");
        Evaluation done = awaitFinished(evaluation.getId());

        List<EvaluationResult> results = evaluationService.results(evaluation.getId());
        assertThat(done.getStatus()).isEqualTo(EvaluationStatus.COMPLETED);
        assertThat(results).extracting(EvaluationResult::getScenarioId)
                .containsExactly("remember-secret", "prompt-injection", "off-topic");
        assertThat(results).allSatisfy(r -> assertThat(r.isPassed()).as(r.getScenarioId() + ": " + r.getFailures()).isTrue());
        assertThat(results).filteredOn(r -> r.getScenarioId().equals("prompt-injection")).singleElement()
                .satisfies(r -> assertThat(r.getOutcome()).isEqualTo("BLOCKED"));
        assertThat(done.getPassed()).isEqualTo(3);
        assertThat(runRepository.findById(results.getFirst().getRunId())).get()
                .satisfies(run -> {
                    assertThat(run.getModel()).isEqualTo("llama3.2:3b");
                    assertThat(run.getSource()).isEqualTo(RunSource.EVALUATION);
                });
    }

    @Test
    void ticketScenariosAreCheckedAndAbandonedWithoutCreatingTickets() {
        long ticketsBefore = ticketRepository.count();
        long chatRunsBefore = metricsService.metrics().totalRuns();
        when(chatModel.call(any(Prompt.class))).thenReturn(response(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "create_ticket",
                        "{\"title\":\"Lost phone\",\"description\":\"Revoke the device\",\"priority\":\"CRITICAL\"}")))
                .build()));

        Evaluation evaluation = evaluationService.start("llama3.2:3b", List.of("ticket"), "admin");
        awaitFinished(evaluation.getId());

        List<EvaluationResult> results = evaluationService.results(evaluation.getId());
        assertThat(results).allSatisfy(r -> assertThat(r.getOutcome()).isEqualTo("APPROVAL"));
        // The scripted ticket mentions the phone, so only the scenarios expecting other details fail.
        assertThat(results).filteredOn(r -> r.getScenarioId().equals("lost-phone")).singleElement()
                .satisfies(r -> assertThat(r.isPassed()).isTrue());
        assertThat(results).filteredOn(r -> r.getScenarioId().equals("shared-mailbox")).singleElement()
                .satisfies(r -> assertThat(r.getFailures()).contains("does not mention 'finance'"));
        assertThat(results).allSatisfy(r -> assertThat(runRepository.findById(r.getRunId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.ESCALATED));
        assertThat(ticketRepository.count()).isEqualTo(ticketsBefore);
        assertThat(metricsService.metrics().totalRuns()).isEqualTo(chatRunsBefore);
    }

    @Test
    void rejectsModelsWithoutToolCalling() {
        assertThatThrownBy(() -> evaluationService.start("tiny", List.of(), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool calling");
    }

    @Test
    void onlyOneEvaluationRunsAtATimeAndItCanBeCancelled() {
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(300);
            return answer("Use the Company Portal.");
        });
        Evaluation running = evaluationService.start("llama3.2:3b", List.of("knowledge"), "admin");

        assertThatThrownBy(() -> evaluationService.start("llama3.2:3b", List.of("safety"), "admin"))
                .isInstanceOf(ConflictException.class);

        evaluationService.cancel(running.getId());
        Evaluation cancelled = awaitFinished(running.getId());
        assertThat(cancelled.getStatus()).isEqualTo(EvaluationStatus.CANCELLED);
        assertThat(cancelled.getCompleted()).isLessThan(cancelled.getScenarioCount());
    }

    private Evaluation awaitFinished(String id) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            Evaluation evaluation = evaluationService.get(id);
            boolean idle = evaluation.getStatus() != EvaluationStatus.RUNNING
                    && (evaluation.getStatus() != EvaluationStatus.CANCELLED || noScenarioInProgress(id, evaluation));
            if (idle) {
                return evaluation;
            }
            sleep();
        }
        throw new AssertionError("Evaluation " + id + " did not finish");
    }

    /** After a cancel, the scenario in progress still finishes; wait until its result is saved. */
    private boolean noScenarioInProgress(String id, Evaluation evaluation) {
        int saved = evaluationService.results(id).size();
        sleep();
        return evaluationService.results(id).size() == saved && evaluation.getCompleted() == saved;
    }

    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ChatResponse answer(String text) {
        return response(new AssistantMessage(text));
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 20)).build());
    }
}
