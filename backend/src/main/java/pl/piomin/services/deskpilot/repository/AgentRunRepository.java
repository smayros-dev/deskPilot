package pl.piomin.services.deskpilot.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.RunSource;
import pl.piomin.services.deskpilot.model.RunStatus;

public interface AgentRunRepository extends JpaRepository<AgentRun, String> {

    List<AgentRun> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    interface SessionRunCount {
        String getSessionId();

        long getRuns();
    }

    @Query("select r.sessionId as sessionId, count(r) as runs from AgentRun r where r.sessionId in :sessionIds group by r.sessionId")
    List<SessionRunCount> countBySession(Collection<String> sessionIds);

    @Modifying
    @Query("delete from AgentRun r where r.sessionId = :sessionId")
    int deleteBySessionId(String sessionId);

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

    interface VariantTotals {
        String getVariant();

        long getRuns();

        long getFinished();

        long getCompleted();

        long getEscalated();

        long getActiveMillis();

        long getTokens();
    }

    /** Runs of an experiment per variant; the sums other than runs are over finished runs only. */
    @Query("""
            select r.variant as variant, count(r) as runs,
                   sum(case when r.completedAt is not null then 1 else 0 end) as finished,
                   sum(case when r.status = pl.piomin.services.deskpilot.model.RunStatus.COMPLETED then 1 else 0 end) as completed,
                   sum(case when r.status = pl.piomin.services.deskpilot.model.RunStatus.ESCALATED then 1 else 0 end) as escalated,
                   sum(case when r.completedAt is not null then r.activeMillis else 0 end) as activeMillis,
                   sum(case when r.completedAt is not null then r.promptTokens + r.completionTokens else 0 end) as tokens
            from AgentRun r where r.experimentId = :experimentId
            group by r.variant""")
    List<VariantTotals> totalsByVariant(String experimentId);

    @Query("select r.activeMillis from AgentRun r where r.experimentId = :experimentId and r.variant = :variant and r.completedAt is not null order by r.activeMillis")
    List<Long> finishedLatencies(String experimentId, String variant);

    @Query("select r.activeMillis from AgentRun r where r.completedAt is not null and r.source = pl.piomin.services.deskpilot.model.RunSource.CHAT order by r.activeMillis")
    List<Long> finishedRunLatencies();
}
