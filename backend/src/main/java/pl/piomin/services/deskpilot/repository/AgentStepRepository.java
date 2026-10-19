package pl.piomin.services.deskpilot.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import pl.piomin.services.deskpilot.model.AgentStep;
import pl.piomin.services.deskpilot.model.StepType;

public interface AgentStepRepository extends JpaRepository<AgentStep, Long> {

    List<AgentStep> findByRunIdOrderByIdAsc(String runId);

    // Dashboard figures count chat runs only, never evaluation runs.
    @Query("select count(s) from AgentStep s where s.type = :type and s.runId in (select r.id from AgentRun r where r.source = pl.piomin.services.deskpilot.model.RunSource.CHAT)")
    long countByType(StepType type);

    @Query("select count(s) from AgentStep s where s.type = :type and s.name = :name and s.runId in (select r.id from AgentRun r where r.source = pl.piomin.services.deskpilot.model.RunSource.CHAT)")
    long countByTypeAndName(StepType type, String name);

    interface ToolTotals {
        String getTool();

        long getCalls();

        long getErrors();

        double getAvgMillis();
    }

    @Query("""
            select s.name as tool, count(s) as calls,
                   sum(case when s.error = true then 1 else 0 end) as errors,
                   avg(s.durationMillis) as avgMillis
            from AgentStep s where s.type = pl.piomin.services.deskpilot.model.StepType.TOOL_RESULT
              and s.runId in (select r.id from AgentRun r where r.source = pl.piomin.services.deskpilot.model.RunSource.CHAT)
            group by s.name order by count(s) desc""")
    List<ToolTotals> toolTotals();
}
