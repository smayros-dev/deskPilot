package pl.piomin.services.deskpilot.service;

import org.junit.jupiter.api.Test;
import pl.piomin.services.deskpilot.config.ExperimentProperties;
import pl.piomin.services.deskpilot.dto.ExperimentResponse.Significance;
import pl.piomin.services.deskpilot.dto.ExperimentResponse.Verdict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Significance of A/B results (12m), checked against values computed with R / SciPy. */
class ProportionTestTest {

    @Test
    void normalCdfMatchesKnownQuantiles() {
        assertThat(ProportionTest.normalCdf(0)).isCloseTo(0.5, within(1e-7));
        assertThat(ProportionTest.normalCdf(1.959964)).isCloseTo(0.975, within(1e-6));
        assertThat(ProportionTest.normalCdf(-1.644854)).isCloseTo(0.05, within(1e-6));
    }

    @Test
    void zTestMatchesTwoProportionTest() {
        // prop.test(c(45, 60), c(100, 100), correct = FALSE)$p.value = 0.03365
        assertThat(ProportionTest.zTest(45, 100, 60, 100)).isCloseTo(0.03365, within(1e-4));
        // Equal rates: p = 1; all successes in both groups: nothing to test.
        assertThat(ProportionTest.zTest(30, 60, 50, 100)).isCloseTo(1.0, within(1e-6));
        assertThat(ProportionTest.zTest(20, 20, 30, 30)).isEqualTo(1.0);
    }

    @Test
    void fisherExactMatchesKnownTables() {
        // Lady tasting tea: fisher.test(matrix(c(3, 1, 1, 3), 2))$p.value = 0.4857
        assertThat(ProportionTest.fisherExact(3, 1, 1, 3)).isCloseTo(0.4857, within(1e-4));
        // fisher.test(matrix(c(1, 11, 9, 3), 2))$p.value = 0.002759
        assertThat(ProportionTest.fisherExact(1, 9, 11, 3)).isCloseTo(0.002759, within(1e-5));
    }

    @Test
    void smallCountsUseFisherLargeCountsTheZTest() {
        assertThat(ProportionTest.compare(10, 10, 6, 10).test()).isEqualTo("fisher");
        assertThat(ProportionTest.compare(45, 100, 60, 100).test()).isEqualTo("z");
    }

    @Test
    void verdictNeedsTheMinimumSampleAndTheFivePercentLevel() {
        ExperimentService service = new ExperimentService(null, null, null, null, null, null, null,
                new ExperimentProperties(3, 10));

        Significance tooFew = service.significance(9, 9, 1, 9);
        assertThat(tooFew.verdict()).isEqualTo(Verdict.NOT_ENOUGH_DATA);
        assertThat(tooFew.pValue()).isNull();
        assertThat(tooFew.rateA()).isEqualTo(1.0);

        Significance worse = service.significance(60, 100, 45, 100);
        assertThat(worse.verdict()).isEqualTo(Verdict.B_WORSE);
        assertThat(worse.test()).isEqualTo("z");
        assertThat(worse.pValue()).isCloseTo(0.0337, within(1e-4));

        assertThat(service.significance(45, 100, 60, 100).verdict()).isEqualTo(Verdict.B_BETTER);
        assertThat(service.significance(50, 100, 55, 100).verdict()).isEqualTo(Verdict.NO_SIGNIFICANT_DIFFERENCE);
    }
}
