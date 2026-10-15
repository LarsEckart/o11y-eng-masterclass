package io.honeycomb.masterclass.everydomain.llmscenario;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates the Masterclass 6 LLM-eval supplement. (Go:
 * {@code internal/llmscenario}.) Plain Java with no OpenTelemetry in it.
 *
 * <h2>The scenario</h2>
 *
 * <p>This is deliberately small. The live LLM demo runs on real Claude Code OTel
 * telemetry (see the session README) with real cost, real tokens, and real
 * multi-step spans ({@code claude_code.interaction}, {@code .llm_request},
 * {@code .tool}), all pointed at session 1's shared Collector. That data has no
 * quality signal anywhere in it: Claude Code's telemetry has success/failure on
 * tool execution, but nothing resembling a continuous eval score. So this class
 * generates only the one thing real data can't provide: a batch of scored
 * conversations, standing in for the output of an eval pipeline (Chapter 21's
 * "the score comes from an eval pipeline" line), each carrying
 * {@code llm.response.quality_score}, the exact attribute MC4's SLI formula
 * ({@code count(llm.response.quality_score > 0.7) / count(*)}) names, so that
 * formula runs unmodified against this dataset.</p>
 *
 * <h2>Why a regressed population, not just noise around one mean</h2>
 *
 * <p>{@link Config#qualityRegressionShare()} of conversations score low
 * ({@link Config#qualityRegressedRange()}) instead of high
 * ({@link Config#qualityGoodRange()}), and nothing else about them differs: no
 * error status, no latency change. Chapter 21's central claim is that
 * success/failure can't capture quality; a dataset where quality was just
 * Gaussian noise around a single mean wouldn't demonstrate a real regression
 * for the SLI to catch, just measurement jitter.</p>
 *
 * <h2>Delivery test sizing</h2>
 *
 * <p>One span per conversation, so {@code conversationCount} alone must clear the
 * SDK's default 2048-span queue with margin; see the seeder's
 * {@code MAX_QUEUE_SIZE} and {@code ScenarioTest.conversationCountExceedsQueueMargin}.</p>
 */
public final class Scenario {

    private Scenario() {
    }

    /**
     * Produces the dataset, in emission order.
     *
     * <p>The order of the {@code rng} calls follows the Go code, so the shape
     * of the data matches. The exact numbers do not: Go's {@code math/rand} and
     * Java's {@link Random} are different generators, so the same seed gives
     * different conversations.</p>
     */
    public static List<Conversation> generate(Config cfg, Random rng) {
        var out = new ArrayList<Conversation>(cfg.conversationCount());

        for (int i = 0; i < cfg.conversationCount(); i++) {
            // Go: Int63n(window). Random.nextLong(bound) is the same idea:
            // uniform in [0, bound).
            Instant start = cfg.now().minusNanos(rng.nextLong(cfg.window().toNanos()));

            boolean regressed = rng.nextDouble() < cfg.qualityRegressionShare();
            // Both draws happen, as in Go: the good-range draw is overwritten
            // when the conversation regressed. Keeping it makes the random
            // sequence the same shape as Go's.
            double quality = uniform(rng, cfg.qualityGoodRange());
            if (regressed) {
                quality = uniform(rng, cfg.qualityRegressedRange());
            }

            out.add(new Conversation(start, quality, regressed));
        }

        return out;
    }

    private static double uniform(Random rng, Range r) {
        return r.low() + rng.nextDouble() * (r.high() - r.low());
    }
}
