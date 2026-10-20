package pl.piomin.services.deskpilot.service;

/**
 * Two-sided tests for the difference between two proportions (12m): the pooled two-proportion z-test, or Fisher's
 * exact test when an expected cell count is below 5 and the normal approximation is unreliable.
 */
public final class ProportionTest {

    /** @param test "z" or "fisher" */
    public record Result(String test, double pValue) {
    }

    private ProportionTest() {
    }

    /**
     * @param successesA successes out of {@code totalA} in the first group
     * @param successesB successes out of {@code totalB} in the second group
     */
    public static Result compare(long successesA, long totalA, long successesB, long totalB) {
        if (totalA <= 0 || totalB <= 0) {
            throw new IllegalArgumentException("Both groups need at least one observation");
        }
        long successes = successesA + successesB;
        long failures = totalA + totalB - successes;
        double total = totalA + totalB;
        long smallerGroup = Math.min(totalA, totalB);
        double smallestExpected = Math.min(smallerGroup * successes / total, smallerGroup * failures / total);
        return smallestExpected < 5
                ? new Result("fisher", fisherExact(successesA, totalA - successesA, successesB, totalB - successesB))
                : new Result("z", zTest(successesA, totalA, successesB, totalB));
    }

    /** Pooled two-proportion z-test, two-sided p-value. */
    static double zTest(long successesA, long totalA, long successesB, long totalB) {
        double pooled = (double) (successesA + successesB) / (totalA + totalB);
        double standardError = Math.sqrt(pooled * (1 - pooled) * (1.0 / totalA + 1.0 / totalB));
        if (standardError == 0) {
            return 1; // all successes or all failures: nothing differs
        }
        double z = ((double) successesA / totalA - (double) successesB / totalB) / standardError;
        return Math.min(1, 2 * (1 - normalCdf(Math.abs(z))));
    }

    /**
     * Fisher's exact test on the 2×2 table [[a, b], [c, d]], two-sided: the total probability of the tables with the
     * same margins that are at most as likely as the observed one.
     */
    static double fisherExact(long a, long b, long c, long d) {
        long row1 = a + b;
        long col1 = a + c;
        long n = a + b + c + d;
        double observed = hypergeometric(a, row1, col1, n);
        double p = 0;
        for (long x = Math.max(0, row1 + col1 - n); x <= Math.min(row1, col1); x++) {
            double probability = hypergeometric(x, row1, col1, n);
            if (probability <= observed * (1 + 1e-7)) {
                p += probability;
            }
        }
        return Math.min(1, p);
    }

    private static double hypergeometric(long x, long row1, long col1, long n) {
        return Math.exp(logChoose(row1, x) + logChoose(n - row1, col1 - x) - logChoose(n, col1));
    }

    private static double logChoose(long n, long k) {
        return logFactorial(n) - logFactorial(k) - logFactorial(n - k);
    }

    private static double logFactorial(long n) {
        double sum = 0;
        for (long i = 2; i <= n; i++) {
            sum += Math.log(i);
        }
        return sum;
    }

    /** Standard normal cumulative distribution (Abramowitz and Stegun 7.1.26, error below 1.5e-7). */
    static double normalCdf(double x) {
        double u = Math.abs(x) / Math.sqrt(2);
        double t = 1 / (1 + 0.3275911 * u);
        double erf = 1 - t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
                * Math.exp(-u * u);
        return x >= 0 ? (1 + erf) / 2 : (1 - erf) / 2;
    }
}
