package pl.piomin.services.deskpilot.agent;

import java.util.Set;

/** Ready-made settings snapshots for unit tests. */
public final class TestAgentConfigs {

    private TestAgentConfigs() {
    }

    public static AgentConfig defaults() {
        return new AgentConfig("qwen3:8b", 0.2, 0.9, 8192, 2048, false, 8, 3, 0.55, true,
                Set.of(), Set.of("create_ticket"), null);
    }
}
