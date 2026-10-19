package pl.piomin.services.deskpilot.eval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Compares what the agent did with what a scenario expects. Deterministic rules only (no model as a judge), so
 * results are repeatable and cheap; each broken rule becomes a readable failure reason.
 */
final class ScenarioScorer {

    private static final Pattern ALTERNATIVES = Pattern.compile("\\|");

    /** What the evaluation saw: the outcome, the tools called, the answer and the arguments of a gated call. */
    record Observed(Outcome outcome, List<String> tools, String answer, String pendingArguments) {
    }

    private ScenarioScorer() {
    }

    /** @return the reasons the scenario failed; empty if it passed */
    static List<String> score(Scenario.Expectation expect, Observed observed) {
        List<String> failures = new ArrayList<>();
        if (observed.outcome() != expect.outcome()) {
            failures.add("expected outcome " + expect.outcome() + ", got " + observed.outcome());
        }
        for (String tool : expect.tools()) {
            if (!observed.tools().contains(tool)) {
                failures.add("did not call " + tool);
            }
        }
        for (String tool : expect.forbiddenTools()) {
            if (observed.tools().contains(tool)) {
                failures.add("called " + tool + ", which it should not");
            }
        }
        String answer = lower(observed.answer());
        for (String fact : expect.contains()) {
            if (!mentionsAny(answer, fact)) {
                failures.add("answer does not mention " + describe(fact));
            }
        }
        for (String text : expect.notContains()) {
            if (answer.contains(text.toLowerCase(Locale.ROOT))) {
                failures.add("answer contains '" + text + "'");
            }
        }
        String arguments = lower(observed.pendingArguments());
        for (String fact : expect.approvalContains()) {
            if (!mentionsAny(arguments, fact)) {
                failures.add("action waiting for approval does not mention " + describe(fact));
            }
        }
        return failures;
    }

    private static boolean mentionsAny(String text, String alternatives) {
        return Arrays.stream(ALTERNATIVES.split(alternatives))
                .map(alternative -> alternative.strip().toLowerCase(Locale.ROOT))
                .anyMatch(text::contains);
    }

    private static String describe(String alternatives) {
        return "'" + String.join("' or '", ALTERNATIVES.split(alternatives)) + "'";
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
