package io.honeycomb.masterclass.feedbackloops.scenario;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioTest {

    private static Config testConfig() {
        // Fixed anchor so the dataset is fully deterministic.
        return Config.defaults(Instant.parse("2026-08-19T12:00:00Z"));
    }

    private record Dataset(Config cfg, List<Request> requests) {
    }

    private static Dataset generate() {
        var cfg = testConfig();
        return new Dataset(cfg, Scenario.generate(cfg, new Random(SEED)));
    }

    /**
     * Go's tests use seed 7 with Go's generator. Java's {@link Random} produces
     * a different stream for the same seed, so the dataset differs. The
     * assertions are statistical bands, so any seed that lands inside them is
     * as good as another.
     */
    private static final long SEED = 7;

    /**
     * Pins the rule down: all three conditions are required, and every request
     * meeting them is regressed. If a refactor lets any two of the three
     * trigger it, the demo stops teaching that single-dimension views miss
     * multicausal problems.
     */
    @Test
    void regressionRule() {
        var data = generate();

        for (var r : data.requests()) {
            boolean want = r.version().equals(data.cfg().canaryVersion())
                    && r.route() == Route.BILLING
                    && r.userType().equals("enterprise");
            assertThat(r.regressed())
                    .as("regressed for version=%s route=%s type=%s", r.version(), r.route(), r.userType())
                    .isEqualTo(want);
        }
    }

    /** Guards the before/after boundary the deploy marker relies on. */
    @Test
    void canaryOnlyAfterDeploy() {
        var data = generate();
        var cfg = data.cfg();
        var deploy = cfg.deployTime();

        for (var r : data.requests()) {
            if (r.version().equals(cfg.canaryVersion())) {
                assertThat(r.start()).as("canary request predates deploy").isAfter(deploy);
            }
            assertThat(r.start()).as("request falls outside the window").isAfterOrEqualTo(cfg.now().minus(cfg.window()));
        }
    }

    /**
     * The load-bearing test. The demo only works inside a narrow band: enough
     * slow requests to draw a BubbleUp box around, few enough that aggregate
     * percentiles stay quiet. Both bounds matter, so both are asserted.
     */
    @Test
    void affectedPopulationIsDemoSized() {
        var reqs = generate().requests();

        long regressed = reqs.stream().filter(Request::regressed).count();

        assertThat(regressed).as("regressed requests; too few to isolate on a heatmap").isGreaterThanOrEqualTo(25);
        double share = (double) regressed / reqs.size();
        assertThat(share).as("regressed share; above 2%% the regression stops being an aggregate blind spot").isLessThanOrEqualTo(0.02);
    }

    /**
     * Asserts the actual pedagogical claim: the median across all traffic is
     * unmoved by the regression. This is what lets the session say "your
     * dashboard would not have caught this."
     */
    @Test
    void invisibleInAggregate() {
        var data = generate();
        var cfg = data.cfg();
        var deploy = cfg.deployTime();

        var before = new ArrayList<Duration>();
        var after = new ArrayList<Duration>();
        for (var r : data.requests()) {
            if (r.start().isAfter(deploy)) {
                after.add(r.duration());
            } else {
                before.add(r.duration());
            }
        }

        double drift = relDelta(percentile(before, 0.50), percentile(after, 0.50));
        assertThat(drift).as("overall P50 drift across the deploy; regression is leaking into the aggregate").isLessThanOrEqualTo(0.05);

        // ...but the same comparison scoped to the affected cohort must show it
        // clearly, or there is nothing for BubbleUp to find.
        var cohortBefore = new ArrayList<Duration>();
        var cohortAfter = new ArrayList<Duration>();
        for (var r : data.requests()) {
            if (r.route() != Route.BILLING || !r.userType().equals("enterprise")) {
                continue;
            }
            if (r.version().equals(cfg.canaryVersion())) {
                cohortAfter.add(r.duration());
            } else {
                cohortBefore.add(r.duration());
            }
        }

        double cohortDrift = relDelta(percentile(cohortBefore, 0.50), percentile(cohortAfter, 0.50));
        assertThat(cohortDrift).as("enterprise-on-billing P50 drift between builds; too subtle to demo").isGreaterThanOrEqualTo(0.15);
    }

    /**
     * Approximates what BubbleUp computes: for each dimension value, its
     * prevalence inside the slow cohort versus the baseline. BubbleUp ranks
     * results by percentage-point difference between the two, so that, not
     * their ratio, is what this asserts. For scale, the book's worked example
     * surfaces a dimension at 98% inside versus 17% baseline, an 81-point
     * spread. All explanatory dimensions here need a spread wide enough to land
     * near the top of that ranked list on screen.
     */
    @Test
    void bubbleUpDimensionsAreSkewed() {
        var data = generate();
        var cfg = data.cfg();

        // Isolate the way an operator would during the demo: the slowest
        // requests on the route where the symptom showed up.
        var billing = new ArrayList<>(data.requests().stream()
                .filter(r -> r.route() == Route.BILLING)
                .toList());
        billing.sort(Comparator.comparing(Request::duration).reversed());

        final int boxSize = 50;
        var inside = billing.subList(0, boxSize);
        var baseline = billing.subList(boxSize, billing.size());

        record Dimension(String name, Function<Request, String> value, String want, double minSpread) {
        }

        var dimensions = List.of(
                new Dimension("service.version", Request::version, cfg.canaryVersion(), 60),
                new Dimension("user.type", Request::userType, "enterprise", 30));

        for (var d : dimensions) {
            double in = prevalence(inside, d.value(), d.want());
            double base = prevalence(baseline, d.value(), d.want());
            double spread = (in - base) * 100;
            assertThat(spread)
                    .as("%s=%s is %.0f%% inside vs %.0f%% baseline; want at least %.0f points to rank in BubbleUp",
                            d.name(), d.want(), in * 100, base * 100, d.minSpread())
                    .isGreaterThanOrEqualTo(d.minSpread());
        }
    }

    /**
     * Checks the book's actual number: the affected cohort's requests, not just
     * one span inside them, are ~40% slower.
     */
    @Test
    void requestIsFortyPercentSlower() {
        var data = generate();
        var cfg = data.cfg();

        var regressed = new ArrayList<Duration>();
        var healthy = new ArrayList<Duration>();
        for (var r : data.requests()) {
            if (r.route() != Route.BILLING || !r.userType().equals("enterprise")) {
                continue;
            }
            if (r.regressed()) {
                regressed.add(r.duration());
            } else {
                healthy.add(r.duration());
            }
        }

        double got = relDelta(percentile(healthy, 0.50), percentile(regressed, 0.50));
        double want = cfg.regressionFactor() - 1;
        assertThat(got)
                .as("affected requests are %.0f%% slower; want ~%.0f%% per the book", got * 100, want * 100)
                .isBetween(want * 0.8, want * 1.2);
    }

    private static double prevalence(List<Request> reqs, Function<Request, String> value, String want) {
        if (reqs.isEmpty()) {
            return 0;
        }
        long n = reqs.stream().filter(r -> value.apply(r).equals(want)).count();
        return (double) n / reqs.size();
    }

    private static Duration percentile(List<Duration> ds, double p) {
        if (ds.isEmpty()) {
            return Duration.ZERO;
        }
        var sorted = new ArrayList<>(ds);
        sorted.sort(Comparator.naturalOrder());
        int idx = (int) (p * (sorted.size() - 1));
        return sorted.get(idx);
    }

    /** Absolute relative change from a to b. */
    private static double relDelta(Duration a, Duration b) {
        if (a.isZero()) {
            return 0;
        }
        double d = (double) (b.toNanos() - a.toNanos()) / a.toNanos();
        return Math.abs(d);
    }
}
