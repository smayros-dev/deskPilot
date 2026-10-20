package pl.piomin.services.deskpilot.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeGapDraftTest {

    @Test
    void readsTitleTagsAndBodyIncludingMarkdownBoldHeaders() {
        KnowledgeGapService.Draft draft = KnowledgeGapService.Draft.parse("""
                **TITLE:** Reset a Bluetooth mouse
                **TAGS:** Mouse, Bluetooth

                1. Hold the button.
                2. Pair again.""", "fallback");

        assertThat(draft.title()).isEqualTo("Reset a Bluetooth mouse");
        assertThat(draft.tags()).isEqualTo("mouse, bluetooth");
        assertThat(draft.content()).isEqualTo("1. Hold the button.\n2. Pair again.");
    }

    @Test
    void withoutHeadersTheGapTitleIsUsedAndTheWholeReplyIsTheBody() {
        KnowledgeGapService.Draft draft = KnowledgeGapService.Draft.parse("<think>hmm</think>\nRestart the dock.", "Dock flickers");

        assertThat(draft.title()).isEqualTo("Dock flickers");
        assertThat(draft.tags()).isEmpty();
        assertThat(draft.content()).isEqualTo("Restart the dock.");
    }

    @Test
    void similarVectorsHaveAHighCosine() {
        float[] a = KnowledgeGapService.normalize(new float[] {1, 1, 0});
        float[] b = KnowledgeGapService.normalize(new float[] {1, 1, 0.1f});
        float[] c = KnowledgeGapService.normalize(new float[] {0, 0, 1});

        assertThat(KnowledgeGapService.cosine(a, b)).isGreaterThan(0.99);
        assertThat(KnowledgeGapService.cosine(a, c)).isZero();
    }
}
