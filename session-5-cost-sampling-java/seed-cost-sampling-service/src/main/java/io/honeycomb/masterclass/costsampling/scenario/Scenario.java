package io.honeycomb.masterclass.costsampling.scenario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Generates the Masterclass 5 cost/sampling dataset.
 *
 * <h2>The scenario</h2>
 *
 * <p>One service, cost-sampling-service, with four routes at a deliberately
 * skewed distribution:
 *
 * <pre>
 * /api/search          85%
 * /api/checkout        10%
 * /api/refund           4%
 * /api/admin/report     1%
 * </pre>
 *
 * <p>The skew is the point: Chapter 15's per-key adaptive rate only differs
 * visibly from a single global rate when low-volume keys exist that a flat
 * sample would erase. Every request is a two-span trace, a root HTTP span
 * plus one db.query child, not a single span, because the Collector's
 * adaptive_tail_sampling processor (see
 * {@code ../session-1-fundamentals/collector/otel-collector-config.yaml})
 * decides per whole trace, and a demo about that needs traces with more than
 * one span to make the "whole trace kept or dropped together" behavior real.
 *
 * <p>A synthetic error rate (default 15%, well above any real service's
 * baseline) marks some requests as errored at both the root and the child
 * span, consistently. That rate is deliberately generous. This dataset
 * exists to make the Collector's keep-errors rule obviously do something
 * within a short, live seed, not to model realistic reliability.
 *
 * <p>Unlike session 4's seeder, this dataset carries no backdated timestamps:
 * the emitter sends spans at real wall-clock time, paced evenly over
 * {@link Config#duration()}, because the sampling decision this scenario
 * demonstrates happens downstream in the Collector as spans actually arrive.
 * A decision_delay window measured against fabricated history would not mean
 * anything.
 *
 * <h2>Why the pacing matters</h2>
 *
 * <p>The adaptive_percentage sampler (dynsampler-go's EMASampleRate) has no
 * per-key rates until its first adjustment_interval tick, and until then
 * returns the flat goal rate for every key. Emit the whole dataset in a burst
 * and every trace lands inside that first interval, so the demo would show a
 * flat 1-in-20 sample while the narration credits per-route fingerprinting.
 * {@link Config#duration()} therefore spans several adjustment intervals:
 * only the first interval's traffic is sampled flat, and the rest carries
 * per-route rates. {@code ScenarioTest.defaultConfigDurationSpansAdjustmentIntervals}
 * enforces this against the Collector config's own adjustment_interval.
 *
 * <p>The sampler also keeps state across runs: a Collector that already saw a
 * previous seed starts from that run's rates, and routes it has no rate for
 * are kept at 1:1. The pre-session checklist restarts the Collector before
 * seeding so every run starts from the same cold state.
 *
 * <h2>Why these numbers</h2>
 *
 * <p>The curriculum's live-demo arithmetic, matching the Collector's
 * cost-sampling-service rule (goal_percentage: 5, see the Collector config
 * for the full rule):
 *
 * <pre>
 * retained = errorRate*1.0 + (1-errorRate)*0.05
 *          = 0.15         + 0.85*0.05
 *          = 0.1925                        (~19%, an ~81% reduction)
 * </pre>
 *
 * <p>{@code ScenarioTest.generateErrorRateMatchesConfig} and the class's other
 * tests guard the inputs to that formula. Nothing automated checks the
 * output. That would mean asserting on the real Collector's actual sampling
 * decision, which needs a live Collector and a query, not a unit test. See
 * the Go README's pre-session checklist: comparing the seeder's printed
 * request count against Honeycomb's weighted COUNT() is the verification
 * step.
 *
 * <h2>Delivery test sizing</h2>
 *
 * <p>Two spans per request means RequestCount*2 must exceed the SDK
 * BatchSpanProcessor's default queue depth (2048) with margin. See
 * {@code SeedCostSamplingService.MAX_QUEUE_SIZE} and
 * {@code SeedCostSamplingServiceDeliveryTest.seedVolumeExceedsQueue}, the
 * same guard session 4 uses for the same reason.
 */
public final class Scenario {

    private Scenario() {}

    /**
     * One endpoint in the dataset, with its share of all requests. Error
     * status is an independent draw (see {@link #generate}), so share is not
     * conditioned on whether a request ends up errored.
     */
    public record Route(String path, double share) {}

    /**
     * Describes one generated dataset.
     *
     * @param requestCount total number of traces (two spans each) to generate
     * @param errorRate    fraction of requests marked errored at both spans
     * @param routes       route distribution every request draws from, errored
     *                     or not. Shares should sum to 1.0; see
     *                     {@code ScenarioTest.defaultConfigRouteSharesSumToOne}
     * @param duration     wall-clock time the emitter spreads requestCount
     *                     requests over. See the class comment for why it must
     *                     span several of the Collector's adjustment intervals
     */
    public record Config(int requestCount, double errorRate, List<Route> routes, Duration duration) {

        // Go's Config is a mutable struct that main() edits in place from env
        // vars. A Java record is immutable, so main() uses these "with" methods
        // to get a modified copy instead.
        public Config withRequestCount(int requestCount) {
            return new Config(requestCount, errorRate, routes, duration);
        }

        public Config withDuration(Duration duration) {
            return new Config(requestCount, errorRate, routes, duration);
        }
    }

    /** Returns the tuned demo configuration described in the class comment. */
    public static Config defaultConfig() {
        return new Config(
            5000,
            0.15,
            List.of(
                new Route("/api/search", 0.85),
                new Route("/api/checkout", 0.10),
                new Route("/api/refund", 0.04),
                new Route("/api/admin/report", 0.01)),
            Duration.ofSeconds(90));
    }

    /**
     * One generated request, fully resolved. The generator decides everything
     * up front so the emitter stays dumb: pick a route, decide
     * errored-or-not, emit two spans, move on.
     *
     * <p>errorType is empty for a request that did not error. Go uses the
     * empty string here, and so do we, so the test can check the same
     * invariant.
     */
    public record Request(String route, boolean errored, String errorType) {}

    /** Produces the dataset, in emission order. */
    public static List<Request> generate(Config cfg, RandomGenerator rng) {
        var out = new ArrayList<Request>(cfg.requestCount());

        for (int i = 0; i < cfg.requestCount(); i++) {
            // Draw order matters only for reproducing a seed: error first,
            // then route. Same order as the Go version.
            boolean errored = rng.nextDouble() < cfg.errorRate();
            String route = routeFor(cfg.routes(), rng);
            out.add(new Request(route, errored, errored ? "dependency_unavailable" : ""));
        }

        return out;
    }

    private static String routeFor(List<Route> routes, RandomGenerator rng) {
        double r = rng.nextDouble();
        double cum = 0;
        for (Route rt : routes) {
            cum += rt.share();
            if (r < cum) {
                return rt.path();
            }
        }
        // Floating-point cumulative sum can fall just short of 1.0; the last
        // route is the correct fallback rather than an empty string.
        return routes.getLast().path();
    }
}
