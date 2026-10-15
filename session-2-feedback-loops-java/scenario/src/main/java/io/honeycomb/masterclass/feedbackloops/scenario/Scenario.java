package io.honeycomb.masterclass.feedbackloops.scenario;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Generates the Masterclass 2 canary-regression dataset.
 *
 * <h2>The scenario</h2>
 *
 * <p>This reproduces the worked example from Chapter 2, Practice 5 (p.33):</p>
 *
 * <blockquote>"You can see that the new build is 40% slower for users on the
 * enterprise plan hitting the billing endpoint. That signal is available at 1%.
 * By the time it's moving aggregate metrics, it's already affecting a lot of
 * people."</blockquote>
 *
 * <p>A deploy happens partway through the window. After it, a small canary slice
 * of traffic runs service.version 1.5.0 instead of 1.4.2. On the canary, and
 * only for enterprise users, and only on /api/billing, requests are ~40%
 * slower. Nothing else changes.</p>
 *
 * <p>The point of the demo is that this is nearly invisible in aggregate: P50
 * does not move at all. The core analysis loop (Chapter 8), automated as
 * BubbleUp, isolates it in seconds because every event carries the dimensions
 * needed to explain it.</p>
 *
 * <h2>Why these proportions</h2>
 *
 * <p>The affected population is the product of three independent filters, so the
 * numbers have to be chosen deliberately or the regression rounds away to
 * nothing. With the defaults in {@link Config#defaults} and 10,000 traces over
 * a 4h window with the deploy 90m ago:</p>
 *
 * <pre>
 * post-deploy traces      10000 * (90/240)      ~= 3750
 * ... on /api/billing     * 0.30                ~= 1125
 * ... enterprise          * 0.45 (billing-only) ~=  506
 * ... on the canary       * 0.10                ~=   50
 * </pre>
 *
 * <p>~50 slow requests against 10,000 total. That is enough to draw a BubbleUp
 * box around on a heatmap, while still being only ~0.5% of all traffic, so P50
 * is flat and P99 is barely perturbed. That gap between "invisible in
 * aggregate" and "obvious in BubbleUp" IS the lesson. If you tune these
 * numbers, keep it.</p>
 *
 * <p>Enterprise users are deliberately overrepresented on /api/billing (45%
 * there versus 8% on checkout). That is both realistic, since billing and
 * invoicing skew enterprise, and necessary, since three multiplied filters at
 * uniform rates would leave single-digit affected requests.</p>
 *
 * <p>This class has no OpenTelemetry imports. It only decides what happened;
 * the seeder decides how to emit it.</p>
 */
public final class Scenario {

    private Scenario() {
    }

    /**
     * Produces the dataset. Requests come back in arbitrary time order, which
     * is fine: each carries its own explicit start timestamp.
     *
     * <p>The caller owns the random source. A test passes a seeded
     * {@link java.util.Random} and gets the same dataset every run.</p>
     */
    public static List<Request> generate(Config cfg, RandomGenerator rng) {
        var deploy = cfg.deployTime();
        var out = new ArrayList<Request>(cfg.traceCount());

        for (int i = 0; i < cfg.traceCount(); i++) {
            Instant start = cfg.now().minusNanos(rng.nextLong(cfg.window().toNanos()));

            var route = Route.CHECKOUT;
            if (rng.nextDouble() < 0.30) {
                route = Route.BILLING;
            }

            String userType = userTypeFor(route, rng);

            String version = cfg.baselineVersion();
            boolean onCanary = false;
            if (start.isAfter(deploy) && rng.nextDouble() < cfg.canaryShare()) {
                version = cfg.canaryVersion();
                onCanary = true;
            }

            // The regression rule, stated once: canary build AND enterprise plan
            // AND the billing endpoint. Any two of the three is not enough, which
            // is exactly why a single-dimension dashboard misses it.
            boolean regressed = onCanary && route == Route.BILLING && userType.equals("enterprise");

            double factor = 1.0;
            if (regressed) {
                factor = cfg.regressionFactor();
            }

            Failure failure = failureFor(route, rng);

            out.add(new Request(
                    start,
                    route,
                    userType,
                    rng.nextInt(5000),
                    version,
                    regressed,
                    failure.errored(),
                    failure.errorType(),
                    stepsFor(route, factor, rng)));
        }

        return out;
    }

    /**
     * Go returns {@code (bool, string)} from {@code failureFor}. Java has no
     * multiple return values, so a small record carries both.
     */
    private record Failure(boolean errored, String errorType) {
        static final Failure NONE = new Failure(false, "");
    }

    /**
     * The ordinary background error rate, matching the failure mode
     * Masterclass 1's checkout dataset already uses so the two sessions' data
     * reads consistently.
     */
    private static Failure failureFor(Route route, RandomGenerator rng) {
        if (route == Route.BILLING) {
            if (rng.nextDouble() < 0.02) {
                return new Failure(true, "payment_method_expired");
            }
            return Failure.NONE;
        }
        if (rng.nextDouble() < 0.07) {
            return new Failure(true, "payment_declined");
        }
        return Failure.NONE;
    }

    /**
     * Picks a plan, conditioned on route. Billing skews enterprise; see the
     * class comment for why that matters.
     */
    private static String userTypeFor(Route route, RandomGenerator rng) {
        double r = rng.nextDouble();
        if (route == Route.BILLING) {
            if (r < 0.20) {
                return "free";
            } else if (r < 0.55) {
                return "premium";
            }
            return "enterprise";
        }
        if (r < 0.65) {
            return "free";
        } else if (r < 0.92) {
            return "premium";
        }
        return "enterprise";
    }

    /**
     * Builds the child spans for a route.
     *
     * <p>When factor &gt; 1, the whole request must end up factor-times slower,
     * because the book's claim is about the request ("the new build is 40%
     * slower"). But all of that added time is loaded onto a single step rather
     * than smeared across every step, so the trace waterfall still shows
     * <em>where</em> it went once you drill in from BubbleUp. Those two
     * requirements together mean the slow step absorbs more than 40%: it
     * carries the whole delta.</p>
     */
    private static List<Step> stepsFor(Route route, double factor, RandomGenerator rng) {
        List<Step> steps;
        int slowStep = -1;

        switch (route) {
            case BILLING -> {
                // These bands are deliberately tight, a ~77-114ms total. A 40%
                // shift has to land clearly outside the healthy distribution, or
                // the slowest normal requests crowd into the BubbleUp box
                // alongside the regressed ones and dilute the very dimensions
                // the demo needs to surface. An earlier, looser version of this
                // (65-157ms) left the box only ~56% canary, which is not a story
                // you can tell on stage.
                steps = new ArrayList<>(List.of(
                        new Step("auth", jitter(rng, millis(5), millis(10))),
                        new Step("load_account", jitter(rng, millis(18), millis(28))),
                        // The regression lives in invoice rendering: an N+1 query
                        // pattern that only bites on accounts with many line
                        // items, which in practice means enterprise accounts.
                        new Step("render_invoice", jitter(rng, millis(50), millis(68))),
                        new Step("emit_receipt", jitter(rng, millis(4), millis(8)))));
                slowStep = 2;
            }
            case CHECKOUT -> steps = new ArrayList<>(List.of(
                    new Step("auth", jitter(rng, millis(5), millis(15))),
                    new Step("inventory_check", jitter(rng, millis(10), millis(30))),
                    new Step("payment", jitter(rng, millis(20), millis(60))),
                    new Step("confirmation", jitter(rng, millis(5), millis(10)))));
            default -> throw new IllegalArgumentException("unknown route " + route);
        }

        if (factor > 1 && slowStep >= 0) {
            var base = Duration.ZERO;
            for (var step : steps) {
                base = base.plus(step.duration());
            }
            var slow = steps.get(slowStep);
            var extra = Duration.ofNanos((long) (base.toNanos() * (factor - 1)));
            steps.set(slowStep, new Step(slow.name(), slow.duration().plus(extra)));
        }

        return List.copyOf(steps);
    }

    private static Duration millis(long ms) {
        return Duration.ofMillis(ms);
    }

    /** A random duration in [min, max), at nanosecond resolution. */
    private static Duration jitter(RandomGenerator rng, Duration min, Duration max) {
        return min.plusNanos(rng.nextLong(max.minus(min).toNanos()));
    }
}
