package io.honeycomb.masterclass.everydomain.cicdscenario;

import java.time.Duration;
import java.time.Instant;

/**
 * Describes one generated dataset.
 *
 * <p>Go's {@code Config} is a mutable struct the seeder edits field by field.
 * This is an immutable record, so an override such as {@code SEED_COUNT}
 * produces a new one through a {@code with...} method.</p>
 *
 * @param runCount        the number of pipeline runs (traces) to generate
 * @param window          how far back from {@code now} the runs are spread
 * @param regressionAgo   how long before {@code now} the build regression
 *                        started. It is still ongoing at {@code now}, same
 *                        reasoning as session 4's incident: a trailing-window
 *                        trigger needs something current to find.
 * @param now             anchors the dataset
 * @param mainBranchShare the fraction of runs that ran on main rather than on
 *                        a PR branch
 */
public record Config(
        int runCount,
        Duration window,
        Duration regressionAgo,
        Instant now,
        int testCount,
        double flakyTestFailureRate,
        double mainBranchShare,
        Duration baselineBuildDuration,
        Duration regressedBuildDuration) {

    /**
     * The tuned demo configuration described in {@link Scenario}, anchored at
     * {@code now}.
     */
    public static Config defaults(Instant now) {
        return new Config(
                500,
                Duration.ofDays(14),
                Duration.ofHours(20),
                now,
                20,
                0.15,
                0.25,
                Duration.ofMinutes(12),
                Duration.ofMinutes(22));
    }

    public Config withRunCount(int runCount) {
        return new Config(runCount, window, regressionAgo, now, testCount, flakyTestFailureRate,
                mainBranchShare, baselineBuildDuration, regressedBuildDuration);
    }

    /** The instant the build regression began. */
    public Instant regressionStart() {
        return now.minus(regressionAgo);
    }
}
