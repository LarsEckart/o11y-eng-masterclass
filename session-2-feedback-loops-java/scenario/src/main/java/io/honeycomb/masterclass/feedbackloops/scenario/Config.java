package io.honeycomb.masterclass.feedbackloops.scenario;

import java.time.Duration;
import java.time.Instant;

/**
 * Describes one generated dataset.
 *
 * <p>Go's {@code Config} is a struct the caller mutates after
 * {@code DefaultConfig()}. A Java record is immutable, so overrides go through
 * the {@code with...} methods, which return a modified copy.</p>
 *
 * @param traceCount       total number of root requests across the window
 * @param window           how far back from {@code now} the dataset extends
 * @param deployAgo        how long before {@code now} the canary deploy happened;
 *                         requests older than this run {@code baselineVersion} only
 * @param now              anchors the dataset. Passed in rather than read from the
 *                         clock so generation is deterministic and testable.
 * @param baselineVersion  the build everyone ran before the deploy
 * @param canaryVersion    the build the canary slice runs after the deploy
 * @param canaryShare      fraction of post-deploy traffic on {@code canaryVersion}
 * @param regressionFactor multiplies duration for affected requests;
 *                         1.4 = 40% slower, per the book
 */
public record Config(
        int traceCount,
        Duration window,
        Duration deployAgo,
        Instant now,
        String baselineVersion,
        String canaryVersion,
        double canaryShare,
        double regressionFactor) {

    /**
     * The tuned demo configuration described in the {@link Scenario} comment.
     * Go's {@code DefaultConfig()} leaves {@code Now} unset and trusts the
     * caller to fill it in. Here the caller has to pass it, so it cannot be
     * forgotten.
     */
    public static Config defaults(Instant now) {
        return new Config(
                10_000,
                Duration.ofHours(4),
                Duration.ofMinutes(90),
                now,
                "1.4.2",
                "1.5.0",
                0.10,
                1.4);
    }

    /**
     * The instant the canary deploy went out. The Honeycomb deploy marker must
     * be created at exactly this time or the before/after boundary in the demo
     * will not line up with the data.
     */
    public Instant deployTime() {
        return now.minus(deployAgo);
    }

    public Config withTraceCount(int traceCount) {
        return new Config(traceCount, window, deployAgo, now, baselineVersion, canaryVersion, canaryShare, regressionFactor);
    }

    public Config withWindow(Duration window) {
        return new Config(traceCount, window, deployAgo, now, baselineVersion, canaryVersion, canaryShare, regressionFactor);
    }

    public Config withDeployAgo(Duration deployAgo) {
        return new Config(traceCount, window, deployAgo, now, baselineVersion, canaryVersion, canaryShare, regressionFactor);
    }
}
