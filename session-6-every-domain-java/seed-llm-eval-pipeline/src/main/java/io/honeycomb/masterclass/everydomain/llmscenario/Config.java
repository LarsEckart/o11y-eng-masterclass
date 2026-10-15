package io.honeycomb.masterclass.everydomain.llmscenario;

import java.time.Duration;
import java.time.Instant;

/**
 * Describes one generated dataset.
 *
 * <p>Go's {@code Config} is a mutable struct the seeder edits field by field.
 * This is an immutable record, so an override such as {@code SEED_COUNT}
 * produces a new one through a {@code with...} method.</p>
 *
 * @param qualityRegressionShare the fraction of conversations whose score falls
 *                               in {@code qualityRegressedRange} instead of
 *                               {@code qualityGoodRange}
 */
public record Config(
        int conversationCount,
        Duration window,
        Instant now,
        double qualityRegressionShare,
        Range qualityGoodRange,
        Range qualityRegressedRange) {

    /**
     * The tuned demo configuration described in {@link Scenario}, anchored at
     * {@code now}.
     */
    public static Config defaults(Instant now) {
        return new Config(
                5000,
                Duration.ofHours(24),
                now,
                0.12,
                new Range(0.75, 0.98),
                new Range(0.30, 0.65));
    }

    public Config withConversationCount(int conversationCount) {
        return new Config(conversationCount, window, now, qualityRegressionShare, qualityGoodRange, qualityRegressedRange);
    }
}
