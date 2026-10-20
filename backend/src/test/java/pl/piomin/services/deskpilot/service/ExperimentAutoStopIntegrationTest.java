package pl.piomin.services.deskpilot.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.agent.SessionService;
import pl.piomin.services.deskpilot.cloud.CloudProviderRepository;
import pl.piomin.services.deskpilot.cloud.CloudProviderService;
import pl.piomin.services.deskpilot.events.ExperimentAutoStopped;
import pl.piomin.services.deskpilot.exception.ModelProviderException;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.model.Experiment;
import pl.piomin.services.deskpilot.model.ExperimentStatus;
import pl.piomin.services.deskpilot.model.RunSource;
import pl.piomin.services.deskpilot.repository.ExperimentRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An experiment whose variant cannot run any more stops itself (12m): its cloud provider is disabled or loses its key,
 * or the variant fails at the provider several times in a row.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@RecordApplicationEvents
class ExperimentAutoStopIntegrationTest {

    @MockitoBean
    private ChatModel chatModel;

    @Autowired
    private ExperimentService experimentService;

    @Autowired
    private ExperimentRepository experimentRepository;

    @Autowired
    private CloudProviderService providers;

    @Autowired
    private CloudProviderRepository providerRepository;

    @Autowired
    private RunService runService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private ApplicationEvents events;

    @AfterEach
    void tearDown() {
        experimentRepository.deleteAll();
        providerRepository.deleteAll();
    }

    @Test
    void disablingTheProviderOfAVariantStopsTheExperiment() {
        providers.update("mistral", true, "sk-test-1234", false, "admin");
        Experiment experiment = runningExperiment();

        providers.update("mistral", false, null, false, "admin");

        Experiment stopped = experimentRepository.findById(experiment.getId()).orElseThrow();
        assertThat(stopped.getStatus()).isEqualTo(ExperimentStatus.STOPPED);
        assertThat(stopped.getStoppedBy()).isEqualTo(AuditService.SYSTEM);
        assertThat(stopped.getStopReason()).isEqualTo("Mistral AI was disabled or lost its API key");
        assertThat(experimentService.results(experiment.getId()).stopReason()).isEqualTo(stopped.getStopReason());
        assertThat(events.stream(ExperimentAutoStopped.class)).singleElement()
                .extracting(ExperimentAutoStopped::experimentId).isEqualTo(experiment.getId());
        assertThat(auditService.search(new AuditService.Filter(AuditAction.EXPERIMENT_STOPPED, AuditService.SYSTEM, null, null),
                0, 10).getContent())
                .anySatisfy(entry -> assertThat(entry.getDetails()).contains("Mistral AI was disabled"));
    }

    @Test
    void anotherProviderChangeLeavesTheExperimentRunning() {
        Experiment experiment = runningExperiment();

        providers.update("gemini", false, null, false, "admin");

        assertThat(experimentRepository.findById(experiment.getId()).orElseThrow().isRunning()).isTrue();
        assertThat(events.stream(ExperimentAutoStopped.class)).isEmpty();
    }

    @Test
    void repeatedProviderErrorsOfOneVariantStopTheExperiment() {
        Experiment experiment = runningExperiment();
        String sessionId = sessionService.create("alice").getId();
        ModelProviderException error = new ModelProviderException("mistral", 401, "Mistral rejected the API key");

        experimentService.recordOutcome(run(sessionId, experiment, "B"), error);
        experimentService.recordOutcome(run(sessionId, experiment, "B"), error);
        // A success in between starts the count again.
        experimentService.recordOutcome(run(sessionId, experiment, "B"), null);
        experimentService.recordOutcome(run(sessionId, experiment, "B"), new RuntimeException(error));
        experimentService.recordOutcome(run(sessionId, experiment, "B"), error);
        // Variant A's errors are counted on their own.
        experimentService.recordOutcome(run(sessionId, experiment, "A"), new IllegalStateException("not a provider error"));
        assertThat(experimentRepository.findById(experiment.getId()).orElseThrow().isRunning()).isTrue();

        experimentService.recordOutcome(run(sessionId, experiment, "B"), error);

        Experiment stopped = experimentRepository.findById(experiment.getId()).orElseThrow();
        assertThat(stopped.getStatus()).isEqualTo(ExperimentStatus.STOPPED);
        assertThat(stopped.getStopReason())
                .startsWith("Variant B failed 3 times in a row with a model-provider error")
                .contains("Mistral rejected the API key");
    }

    /** Saved directly: the start endpoint would check that the cloud model exists at the provider. */
    private Experiment runningExperiment() {
        return experimentRepository.saveAndFlush(new Experiment("local vs mistral",
                new Experiment.Variant("ollama", "qwen3:8b", null),
                new Experiment.Variant("mistral", "mistral-small-latest", null), 50, "admin"));
    }

    private AgentRun run(String sessionId, Experiment experiment, String variant) {
        return runService.start(sessionId, "alice", "My laptop is slow", "qwen3:8b", RunSource.CHAT,
                experiment.getId(), variant);
    }
}
