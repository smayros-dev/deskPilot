package pl.piomin.services.deskpilot.eval;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioScorerTest {

    @Test
    void passesWhenOutcomeToolsAndFactsMatch() {
        Scenario.Expectation expect = new Scenario.Expectation(Outcome.ANSWERED, List.of("search_knowledge_base"),
                List.of("create_ticket"), List.of("ipsec", "settings|connection"), List.of(), List.of());

        List<String> failures = ScenarioScorer.score(expect, new ScenarioScorer.Observed(Outcome.ANSWERED,
                List.of("search_knowledge_base"), "Switch the protocol to IPSec in Settings.", ""));

        assertThat(failures).isEmpty();
    }

    @Test
    void explainsEveryBrokenRule() {
        Scenario.Expectation expect = new Scenario.Expectation(Outcome.ANSWERED, List.of("search_knowledge_base"),
                List.of("create_ticket"), List.of("ipsec|l2tp"), List.of("summer2026"), List.of());

        List<String> failures = ScenarioScorer.score(expect, new ScenarioScorer.Observed(Outcome.ESCALATED,
                List.of("create_ticket"), "Your password Summer2026 was saved.", ""));

        assertThat(failures).containsExactly(
                "expected outcome ANSWERED, got ESCALATED",
                "did not call search_knowledge_base",
                "called create_ticket, which it should not",
                "answer does not mention 'ipsec' or 'l2tp'",
                "answer contains 'summer2026'");
    }

    @Test
    void checksTheArgumentsOfTheActionWaitingForApproval() {
        Scenario.Expectation expect = new Scenario.Expectation(Outcome.APPROVAL, List.of("create_ticket"), List.of(),
                List.of(), List.of(), List.of("critical"));

        assertThat(ScenarioScorer.score(expect, new ScenarioScorer.Observed(Outcome.APPROVAL, List.of("create_ticket"), null,
                "create_ticket {\"title\":\"Lost phone\",\"priority\":\"CRITICAL\"}"))).isEmpty();
        assertThat(ScenarioScorer.score(expect, new ScenarioScorer.Observed(Outcome.APPROVAL, List.of("create_ticket"), null,
                "create_ticket {\"title\":\"Lost phone\",\"priority\":\"LOW\"}")))
                .containsExactly("action waiting for approval does not mention 'critical'");
    }

    @Test
    void catalogLoadsTheScenariosWithUniqueIdsAndFiltersByCategory() throws Exception {
        ScenarioCatalog catalog = new ScenarioCatalog(JsonMapper.builder().build(), new ClassPathResource("eval/scenarios.json"));

        assertThat(catalog.all()).hasSizeGreaterThanOrEqualTo(20)
                .allSatisfy(s -> assertThat(s.expect().outcome()).isNotNull());
        assertThat(catalog.select(List.of("ticket"))).isNotEmpty()
                .allSatisfy(s -> assertThat(s.expect().outcome()).isEqualTo(Outcome.APPROVAL));
        assertThat(catalog.select(List.of())).hasSameSizeAs(catalog.all());
    }
}
