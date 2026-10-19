package pl.piomin.services.deskpilot.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.RunSource;
import pl.piomin.services.deskpilot.model.RunStatus;

public interface AgentRunRepository extends JpaRepository<AgentRun, String> {

    List<AgentRun> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<AgentRun> findByStatusIn(List<RunStatus> statuses);

    List<AgentRun> findByStatusAndSourceOrderByCreatedAtAsc(RunStatus status, RunSource source);

    interface StatusTotals {
        RunStatus getStatus();

        long getRuns();

        long getIterations();

        long getToolCalls();

        long getToolErrors();

        long getPromptTokens();

        long getCompletionTokens();

        long getActiveMillis();
    }

    @Query("""
            select r.status as status, count(r) as runs, sum(r.iterations) as iterations,
                   sum(r.toolCalls) as toolCalls, sum(r.toolErrors) as toolErrors,
                   sum(r.promptTokens) as promptTokens, sum(r.completionTokens) as completionTokens,
                   sum(r.activeMillis) as activeMillis
            from AgentRun r where r.source = pl.piomin.services.deskpilot.model.RunSource.CHAT group by r.status""")
    List<StatusTotals> totalsByStatus();

    interface ModelTotals {
        String getModel();

        long getRuns();

        long getCompleted();

        long getIterations();

        long getTokens();

        long getActiveMillis();
    }

    @Query("""
            select coalesce(r.model, 'unknown') as model, count(r) as runs,
                   sum(case when r.status = pl.piomin.services.deskpilot.model.RunStatus.COMPLETED then 1 else 0 end) as completed,
                   sum(r.iterations) as iterations, sum(r.promptTokens + r.completionTokens) as tokens,
                   sum(r.activeMillis) as activeMillis
            from AgentRun r where r.completedAt is not null and r.source = pl.piomin.services.deskpilot.model.RunSource.CHAT
            group by coalesce(r.model, 'unknown') order by count(r) desc""")
    List<ModelTotals> finishedTotalsByModel();

    @Query("select r.activeMillis from AgentRun r where r.completedAt is not null and r.source = pl.piomin.services.deskpilot.model.RunSource.CHAT order by r.activeMillis")
    List<Long> finishedRunLatencies();
}
