package pl.piomin.services.deskpilot.service;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditServiceTest {

    record Sample(String model, double temperature, Set<String> tools, String prompt, String updatedAt) {
    }

    @Test
    void diffListsChangedFieldsOnly() {
        Sample before = new Sample("qwen3:8b", 0.2, Set.of("a", "b"), null, "t1");
        Sample after = new Sample("qwen3:8b", 0.9, Set.of("b", "a"), "  ", "t2");

        assertThat(AuditService.diff(before, after, "updatedAt")).isEqualTo("temperature: 0.2 → 0.9");
    }

    @Test
    void diffShortensLongValuesAndShowsEmptyAsDash() {
        String longPrompt = "x".repeat(300);
        String diff = AuditService.diff(new Sample("m", 0.2, Set.of(), null, null), new Sample("m", 0.2, Set.of(), longPrompt, null));

        assertThat(diff).startsWith("prompt: — → xxx").endsWith("…").hasSizeLessThan(140);
    }

    @Test
    void csvFieldsAreQuotedAndFormulasNeutralized() {
        assertThat(List.of("plain", "a,b", "say \"hi\"", "=SUM(A1)", "-1")).map(AuditService::csvField)
                .containsExactly("plain", "\"a,b\"", "\"say \"\"hi\"\"\"", "'=SUM(A1)", "'-1");
    }
}
