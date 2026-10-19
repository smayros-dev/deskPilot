package pl.piomin.services.deskpilot.service;

import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.agent.AgentMetrics;
import pl.piomin.services.deskpilot.dto.AgentMetricsResponse;
import pl.piomin.services.deskpilot.exception.ConflictException;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.FeedbackRating;
import pl.piomin.services.deskpilot.model.RunFeedback;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.repository.AgentRunRepository;
import pl.piomin.services.deskpilot.repository.AgentStepRepository;
import pl.piomin.services.deskpilot.repository.RunFeedbackRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({TestcontainersConfiguration.class, RunService.class, FeedbackService.class})
class FeedbackServiceTest {

    @Autowired
    private RunService runService;

    @Autowired
    private FeedbackService feedbackService;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentStepRepository stepRepository;

    @Autowired
    private RunFeedbackRepository feedbackRepository;

    @Test
    void ownerRatesAFinishedRunAndCanChangeTheRating() {
        AgentRun run = finishedRun("alice", "qwen3:8b");

        feedbackService.rate(run.getId(), "alice", FeedbackRating.DOWN, "  Wrong portal address  ");
        RunFeedback changed = feedbackService.rate(run.getId(), "alice", FeedbackRating.UP, " ");

        assertThat(feedbackRepository.count()).isEqualTo(1);
        assertThat(changed.getRating()).isEqualTo(FeedbackRating.UP);
        assertThat(changed.getComment()).isNull();
        assertThat(feedbackService.find(run.getId())).get().extracting(RunFeedback::getRating).isEqualTo(FeedbackRating.UP);
    }

    @Test
    void commentIsTrimmed() {
        AgentRun run = finishedRun("alice", "qwen3:8b");

        assertThat(feedbackService.rate(run.getId(), "alice", FeedbackRating.DOWN, "  Wrong portal  ").getComment())
                .isEqualTo("Wrong portal");
    }

    @Test
    void otherUsersCannotRateOrRemoveTheRating() {
        AgentRun run = finishedRun("alice", "qwen3:8b");
        feedbackService.rate(run.getId(), "alice", FeedbackRating.UP, null);

        assertThatThrownBy(() -> feedbackService.rate(run.getId(), "bob", FeedbackRating.DOWN, null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> feedbackService.remove(run.getId(), "bob")).isInstanceOf(NotFoundException.class);
        assertThat(feedbackService.find(run.getId())).isPresent();
    }

    @Test
    void runsInProgressOrWaitingForApprovalCannotBeRated() {
        AgentRun running = runService.start("s1", "alice", "Hi", "qwen3:8b");
        AgentRun paused = runService.start("s2", "alice", "Open a ticket", "qwen3:8b");
        runService.pauseForApproval(paused.getId(), 10);

        assertThatThrownBy(() -> feedbackService.rate(running.getId(), "alice", FeedbackRating.UP, null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> feedbackService.rate(paused.getId(), "alice", FeedbackRating.UP, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void ownerCanRemoveTheRating() {
        AgentRun run = finishedRun("alice", "qwen3:8b");
        feedbackService.rate(run.getId(), "alice", FeedbackRating.DOWN, "bad");

        feedbackService.remove(run.getId(), "alice");
        feedbackService.remove(run.getId(), "alice");

        assertThat(feedbackService.find(run.getId())).isEmpty();
    }

    @Test
    void latestNegativeRatingsComeWithTheirRunsNewestFirst() {
        AgentRun first = finishedRun("alice", "qwen3:8b");
        AgentRun second = finishedRun("carol", "gemma4:26b");
        AgentRun liked = finishedRun("dave", "qwen3:8b");
        feedbackService.rate(first.getId(), "alice", FeedbackRating.DOWN, "first");
        feedbackService.rate(second.getId(), "carol", FeedbackRating.DOWN, "second");
        feedbackService.rate(liked.getId(), "dave", FeedbackRating.UP, null);

        List<FeedbackService.Rated> negative = feedbackService.latest(FeedbackRating.DOWN);

        assertThat(negative).extracting(r -> r.feedback().getComment()).containsExactly("second", "first");
        assertThat(negative.getFirst().run().getModel()).isEqualTo("gemma4:26b");
    }

    @Test
    void metricsReportSatisfactionOverallAndPerModel() {
        rate(finishedRun("u1", "qwen3:8b"), "u1", FeedbackRating.UP);
        rate(finishedRun("u2", "qwen3:8b"), "u2", FeedbackRating.UP);
        rate(finishedRun("u3", "qwen3:8b"), "u3", FeedbackRating.DOWN);
        rate(finishedRun("u4", "gemma4:26b"), "u4", FeedbackRating.DOWN);
        MetricsService metricsService = new MetricsService(runRepository, stepRepository,
                new AgentMetrics(new SimpleMeterRegistry()), feedbackRepository);

        AgentMetricsResponse.FeedbackStats feedback = metricsService.metrics().feedback();

        assertThat(feedback.up()).isEqualTo(2);
        assertThat(feedback.down()).isEqualTo(2);
        assertThat(feedback.satisfactionRate()).isEqualTo(0.5);
        assertThat(feedback.models()).containsExactly(
                new AgentMetricsResponse.ModelFeedback("qwen3:8b", 2, 1, 0.667),
                new AgentMetricsResponse.ModelFeedback("gemma4:26b", 0, 1, 0.0));
    }

    private void rate(AgentRun run, String user, FeedbackRating rating) {
        feedbackService.rate(run.getId(), user, rating, null);
    }

    private AgentRun finishedRun(String user, String model) {
        AgentRun run = runService.start("session-" + user, user, "How do I connect to the VPN?", model);
        return runService.finish(run.getId(), RunStatus.COMPLETED, "Use GlobalProtect.", 1000);
    }
}
