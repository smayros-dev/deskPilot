package pl.piomin.services.deskpilot.agent;

import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.AgentStep;

/**
 * Follows a run while the agent works on it, e.g. to stream its steps to the browser. Called on the request's
 * thread; implementations must not throw (a closed connection must not fail the run).
 */
public interface RunListener {

    RunListener NONE = new RunListener() {
    };

    /** The run was created, or resumed after an approval decision. */
    default void runStarted(AgentRun run) {
    }

    /** The agent is about to call the model for the given iteration (1-based); the slow part on a CPU. */
    default void thinking(int iteration) {
    }

    /** A step was added to the run's trace. */
    default void step(AgentStep step) {
    }
}
