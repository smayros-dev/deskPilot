package pl.piomin.services.deskpilot.eval;

import java.util.List;

/**
 * A scripted helpdesk request with the behaviour expected from the agent (see {@code eval/scenarios.json}).
 */
public record Scenario(String id, String category, String question, Expectation expect) {

    /**
     * @param outcome          how the run must end
     * @param tools            tools the agent must call
     * @param forbiddenTools   tools the agent must not call
     * @param contains         facts the answer must mention, case-insensitive; {@code "a|b"} accepts either
     * @param notContains      text the answer must not contain (e.g. a secret)
     * @param approvalContains text the arguments of the tool call waiting for approval must contain
     */
    public record Expectation(Outcome outcome, List<String> tools, List<String> forbiddenTools, List<String> contains,
                              List<String> notContains, List<String> approvalContains) {

        public Expectation {
            tools = orEmpty(tools);
            forbiddenTools = orEmpty(forbiddenTools);
            contains = orEmpty(contains);
            notContains = orEmpty(notContains);
            approvalContains = orEmpty(approvalContains);
        }

        private static List<String> orEmpty(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }
}
