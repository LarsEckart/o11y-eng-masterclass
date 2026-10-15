package io.honeycomb.masterclass.costsampling.scenario;

import io.honeycomb.masterclass.costsampling.GoDuration;
import io.honeycomb.masterclass.costsampling.scenario.Scenario.Config;
import io.honeycomb.masterclass.costsampling.scenario.Scenario.Request;
import io.honeycomb.masterclass.costsampling.scenario.Scenario.Route;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScenarioTest {

    // Seed 7, like the Go test. Java's Random is a different generator from
    // Go's, so the exact requests differ, but every check below is a
    // tolerance on a distribution, not on specific values.
    private final Config cfg = Scenario.defaultConfig();
    private final List<Request> reqs = Scenario.generate(cfg, new Random(7));

    /**
     * Guards the input to routeFor's cumulative-sum selection and to the
     * class comment's retained-share arithmetic: if the shares don't sum to
     * 1, "85/10/4/1" stops meaning what the docs say it means.
     */
    @Test
    void defaultConfigRouteSharesSumToOne() {
        double sum = cfg.routes().stream().mapToDouble(Route::share).sum();
        assertThat(sum).isCloseTo(1.0, within(1e-9));
    }

    /**
     * Checks each route's observed share against its configured share. The
     * Collector's per-key adaptive rate only visibly differs from a flat
     * rate if /api/admin/report's 1% is both present and clearly rarer than
     * /api/search's 85%. This test is what guards that the skew survives
     * into the generated data.
     */
    @Test
    void generateRouteDistributionMatchesShares() {
        Map<String, Integer> counts = countByRoute();

        for (Route rt : cfg.routes()) {
            double got = (double) counts.getOrDefault(rt.path(), 0) / reqs.size();
            assertThat(got)
                .as("route %s observed share, want close to configured %.3f", rt.path(), rt.share())
                .isCloseTo(rt.share(), within(0.03));
        }
    }

    /**
     * The numeric version of "the skew is the point" from the class comment:
     * at the default requestCount, the rarest route still needs enough
     * requests to be a real, rankable population rather than a handful of
     * outliers noise would swallow.
     */
    @Test
    void generateLowVolumeRouteStaysVisible() {
        Route rarest = cfg.routes().stream()
            .min((a, b) -> Double.compare(a.share(), b.share()))
            .orElseThrow();

        int n = countByRoute().getOrDefault(rarest.path(), 0);
        assertThat(n)
            .as("rarest route %s is too rare for the per-key demo to show it as a real population", rarest.path())
            .isGreaterThanOrEqualTo(25);
    }

    /**
     * Guards one half of the class comment's retained-share arithmetic
     * (errorRate*1.0 term): if the actual generated error rate drifts from
     * cfg.errorRate(), the live demo's ~81% reduction claim stops matching
     * what the Collector actually receives.
     */
    @Test
    void generateErrorRateMatchesConfig() {
        long errored = reqs.stream().filter(Request::errored).count();
        double got = (double) errored / reqs.size();
        assertThat(got).isCloseTo(cfg.errorRate(), within(0.02));
    }

    /**
     * Checks the generator's own invariant: an errored request always has a
     * non-empty errorType, and a non-errored one never does.
     */
    @Test
    void generateErroredRequestsCarryErrorType() {
        for (Request r : reqs) {
            if (r.errored()) {
                assertThat(r.errorType()).as("errored request's errorType").isNotEmpty();
            } else {
                assertThat(r.errorType()).as("non-errored request's errorType").isEmpty();
            }
        }
    }

    /**
     * Guards the delivery test's precondition rather than re-deriving it: two
     * spans per request at the default requestCount must clear the SDK's
     * default 2048-span batch queue with real margin, or a dropped-span
     * regression would go unnoticed the same way session 4's comment
     * describes.
     */
    @Test
    void generateRequestCountExceedsQueueMargin() {
        int defaultQueueSize = 2048;

        int spans = cfg.requestCount() * 2;
        assertThat(spans)
            .as("default config must emit comfortably more than the %d-span default queue", defaultQueueSize)
            .isGreaterThan(defaultQueueSize * 2);
    }

    /** The shared Collector config whose adaptive_tail_sampling rules this dataset exercises. */
    // Gradle runs tests with the subproject directory as the working
    // directory: session-5-cost-sampling-java/seed-cost-sampling-service.
    private static final Path COLLECTOR_CONFIG =
        Path.of("../../session-1-fundamentals/collector/otel-collector-config.yaml");

    /**
     * How many adjustment intervals Config.duration must span. The first
     * interval is sampled flat (see the class comment), so four keeps at
     * least three quarters of the traffic on per-route rates.
     */
    private static final int MIN_ADJUSTMENT_INTERVALS = 4;

    /**
     * Guards the pacing the per-route demo depends on. It reads
     * adjustment_interval from the real Collector config rather than a copied
     * constant, so lengthening the interval there without lengthening
     * duration here fails the build instead of silently reverting the live
     * demo to a flat sample.
     */
    @Test
    void defaultConfigDurationSpansAdjustmentIntervals() throws IOException {
        String raw = Files.readString(COLLECTOR_CONFIG);
        var match = Pattern.compile("(?m)^\\s*adjustment_interval:\\s*(\\S+)\\s*$").matcher(raw);
        assertThat(match.find()).as("adjustment_interval in %s", COLLECTOR_CONFIG).isTrue();

        // The Collector writes Go-style durations such as "15s", which is
        // what GoDuration reads.
        Duration interval = GoDuration.parse(match.group(1))
            .orElseThrow(() -> new AssertionError("cannot parse adjustment_interval " + match.group(1)));

        Duration want = interval.multipliedBy(MIN_ADJUSTMENT_INTERVALS);
        assertThat(cfg.duration())
            .as("Duration must span at least %d adjustment intervals of %s; "
                + "otherwise most traffic would be sampled at the flat cold-start rate",
                MIN_ADJUSTMENT_INTERVALS, interval)
            .isGreaterThanOrEqualTo(want);
    }

    private Map<String, Integer> countByRoute() {
        var counts = new HashMap<String, Integer>();
        for (Request r : reqs) {
            counts.merge(r.route(), 1, Integer::sum);
        }
        return counts;
    }
}
