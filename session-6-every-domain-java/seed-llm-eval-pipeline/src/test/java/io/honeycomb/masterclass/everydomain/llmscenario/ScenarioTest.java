package io.honeycomb.masterclass.everydomain.llmscenario;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioTest {

    private static Config testConfig() {
        return Config.defaults(Instant.parse("2026-09-16T12:00:00Z"));
    }

    private static List<Conversation> generate(Config cfg) {
        return Scenario.generate(cfg, new Random(7));
    }

    /** Guards the population split the whole package exists to demonstrate. */
    @Test
    void qualityRegressionRateMatchesConfig() {
        Config cfg = testConfig();
        List<Conversation> convos = generate(cfg);

        long regressed = convos.stream().filter(Conversation::regressed).count();
        double got = (double) regressed / convos.size();
        assertThat(got)
                .as("observed regression rate, want close to configured %.3f", cfg.qualityRegressionShare())
                .isBetween(cfg.qualityRegressionShare() - 0.03, cfg.qualityRegressionShare() + 0.03);
    }

    /**
     * Checks there's no overlap between the good and regressed populations.
     * MC4's SLI threshold (0.7) needs to fall cleanly between them for the
     * formula to mean anything against this dataset.
     */
    @Test
    void qualityScoresFallInTheirDeclaredRanges() {
        Config cfg = testConfig();
        List<Conversation> convos = generate(cfg);

        assertThat(cfg.qualityRegressedRange().high())
                .as("QualityRegressedRange tops out at, which must be below MC4's 0.7 SLI threshold")
                .isLessThan(0.7);
        assertThat(cfg.qualityGoodRange().low())
                .as("QualityGoodRange starts at, which must be above MC4's 0.7 SLI threshold")
                .isGreaterThan(0.7);

        for (Conversation c : convos) {
            if (c.regressed()) {
                assertThat(c.qualityScore())
                        .as("regressed conversation score, want inside %s", cfg.qualityRegressedRange())
                        .isBetween(cfg.qualityRegressedRange().low(), cfg.qualityRegressedRange().high());
            } else {
                assertThat(c.qualityScore())
                        .as("good conversation score, want inside %s", cfg.qualityGoodRange())
                        .isBetween(cfg.qualityGoodRange().low(), cfg.qualityGoodRange().high());
            }
        }
    }

    /**
     * Computes MC4's exact formula, {@code count(quality_score > 0.7) /
     * count(*)}, against this package's output, so a drift in the proportions
     * would show up here before it shows up live.
     */
    @Test
    void sliFormulaHasSomethingToFind() {
        List<Conversation> convos = generate(testConfig());

        long good = convos.stream().filter(c -> c.qualityScore() > 0.7).count();
        double ratio = (double) good / convos.size();
        assertThat(ratio)
                .as("SLI ratio, want comfortably below 1.0 (a real regression) but not so low it reads as a total outage")
                .isStrictlyBetween(0.80, 0.99);
    }

    /**
     * Guards the delivery test's precondition, same rationale as every other
     * seeder in this repo.
     */
    @Test
    void conversationCountExceedsQueueMargin() {
        Config cfg = Config.defaults(Instant.now());
        final int defaultQueueSize = 2048;
        assertThat(cfg.conversationCount())
                .as("default config emits %d conversations (one span each), not comfortably past the %d-span default queue; raise conversationCount",
                        cfg.conversationCount(), defaultQueueSize)
                .isGreaterThan(defaultQueueSize * 2);
    }
}
