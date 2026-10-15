package io.honeycomb.masterclass.slisslos.scenario;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Port of the Go {@code scenario_test.go}. */
class ScenarioTest {

    private static Config testConfig() {
        // Fixed anchor so the dataset is fully deterministic.
        return Config.defaults(Instant.parse("2026-09-16T12:00:00Z"));
    }

    private static List<Request> generate(Config cfg) {
        return Generator.generate(cfg, new Random(7));
    }

    /**
     * Pins the rule down: all three conditions are required. If a refactor lets
     * any two of the three trigger it, the demo stops teaching that BubbleUp is
     * ranking a real multi-dimension population rather than one dimension that
     * happens to correlate.
     */
    @Test
    void incidentRule() {
        Config cfg = testConfig();
        Instant incidentStart = cfg.incidentStart();

        for (Request r : generate(cfg)) {
            if (r.route() == Route.HEALTHZ) {
                assertThat(r.incident())
                        .as("healthz request marked incident; health checks are never affected")
                        .isFalse();
                continue;
            }
            boolean want = r.start().isAfter(incidentStart)
                    && r.version().equals(cfg.buggyVersion())
                    && r.userType().equals("enterprise");
            assertThat(r.incident())
                    .as("incident for start=%s version=%s type=%s", r.start(), r.version(), r.userType())
                    .isEqualTo(want);
        }
    }

    /**
     * Guards the assumption the burn-rate demo and its trigger both depend on:
     * the incident is still happening at now, not a past event with a clean end
     * boundary.
     */
    @Test
    void incidentIsOngoing() {
        Config cfg = testConfig();

        boolean sawIncidentNearNow = false;
        for (Request r : generate(cfg)) {
            if (!r.incident()) {
                continue;
            }
            assertThat(r.start())
                    .as("incident request predates the incident window start")
                    .isAfterOrEqualTo(cfg.now().minus(cfg.incidentAgo()));
            if (Duration.between(r.start(), cfg.now()).compareTo(Duration.ofMinutes(5)) < 0) {
                sawIncidentNearNow = true;
            }
        }
        assertThat(sawIncidentNearNow)
                .as("no incident request within 5 minutes of now; the incident should still be live, not just historical")
                .isTrue();
    }

    /**
     * Checks the affected population is large enough to move a trailing-1-hour
     * AVG() query clearly, matching the package comment's arithmetic.
     */
    @Test
    void errorSpikeIsDemoSized() {
        Config cfg = testConfig();

        int incidentPop = 0;
        int incidentErrors = 0;
        for (Request r : generate(cfg)) {
            if (!r.incident()) {
                continue;
            }
            incidentPop++;
            if (r.errored()) {
                incidentErrors++;
            }
        }

        assertThat(incidentPop)
                .as("requests in the incident population; too few to move a 1-hour AVG() query")
                .isGreaterThanOrEqualTo(25);
        double got = (double) incidentErrors / incidentPop;
        assertThat(got)
                .as("incident population error rate, want close to configured %.2f", cfg.incidentErrorRate())
                .isGreaterThanOrEqualTo(cfg.incidentErrorRate() * 0.6);
    }

    /**
     * The flip side: blended across the whole 6h window and every user type,
     * the SLI barely moves. That's what lets the session say a 30-day-baseline
     * target wouldn't have caught this on its own; only the short trailing
     * window does.
     */
    @Test
    void overallSliStaysHealthy() {
        int total = 0;
        int good = 0;
        for (Request r : generate(testConfig())) {
            if (r.route() == Route.HEALTHZ) {
                continue;
            }
            total++;
            if (r.statusCode() < 400) {
                good++;
            }
        }

        double ratio = (double) good / total;
        assertThat(ratio)
                .as("overall SLI (route != healthz); incident is dominating the whole window instead of hiding inside it")
                .isGreaterThanOrEqualTo(0.95);
    }

    /**
     * The numeric version of the package comment's pedagogical claim. It
     * computes the SLI two ways, with and without the "route != healthz" filter,
     * restricted to the incident window, and asserts that skipping the filter
     * meaningfully hides the drop. If this test can't tell the difference, the
     * demo's whole reason for excluding health checks stops being true of the
     * data.
     */
    @Test
    void healthzExclusionMasksTheDrop() {
        Config cfg = testConfig();
        Instant incidentStart = cfg.incidentStart();

        int withFilterTotal = 0;
        int withFilterGood = 0;
        int noFilterTotal = 0;
        int noFilterGood = 0;

        for (Request r : generate(cfg)) {
            if (!r.start().isAfter(incidentStart)) {
                continue;
            }
            noFilterTotal++;
            if (r.statusCode() < 400) {
                noFilterGood++;
            }
            if (r.route() == Route.HEALTHZ) {
                continue;
            }
            withFilterTotal++;
            if (r.statusCode() < 400) {
                withFilterGood++;
            }
        }

        double filtered = (double) withFilterGood / withFilterTotal;
        double unfiltered = (double) noFilterGood / noFilterTotal;

        assertThat(unfiltered)
                .as("unfiltered SLI vs route-filtered SLI (%.3f); healthz traffic should be diluting the incident away", filtered)
                .isGreaterThan(filtered);
        assertThat(unfiltered - filtered)
                .as("healthz dilution; too small to make the exclusion clause visibly matter in a live query")
                .isGreaterThanOrEqualTo(0.03);
    }

    // BURN_WINDOW_SECONDS and BURN_THRESHOLD mirror
    // ../session-4-slis-slos/honeycomb-setup/terraform/variables.tf's
    // burn_window_seconds and burn_threshold defaults. Duplicated here (Java
    // can't read .tf files) so this test fails if either side drifts from the
    // other.
    private static final int BURN_WINDOW_SECONDS = 3600;
    private static final double BURN_THRESHOLD = 0.90;

    /**
     * Regression test for the bug where the trigger never fired: an earlier
     * tuning's expected-value arithmetic looked fine, but the trailing-1-hour
     * AVG() stayed above burn_threshold in every one of 1000 simulated seeds,
     * because the generator is reseeded on every real run and the affected
     * population was too small a fraction of the trailing window's traffic to
     * reliably drag the blended ratio down that far. Runs across many
     * deterministic seeds rather than one, since the bug this guards against was
     * invisible at a single fixed seed.
     */
    @Test
    void burnWindowReliablyCrossesThreshold() {
        Config cfg = testConfig();
        Instant trailingStart = cfg.now().minusSeconds(BURN_WINDOW_SECONDS);

        int seeds = 200;
        for (long seed = 0; seed < seeds; seed++) {
            List<Request> reqs = Generator.generate(cfg, new Random(seed));

            int total = 0;
            int good = 0;
            for (Request r : reqs) {
                if (r.route() == Route.HEALTHZ || !r.start().isAfter(trailingStart)) {
                    continue;
                }
                total++;
                if (r.statusCode() < 400) {
                    good++;
                }
            }

            double avg = (double) good / total;
            assertThat(avg)
                    .as("seed %d: trailing-window AVG does not clear burn_threshold %.2f; the trigger would not fire",
                            seed, BURN_THRESHOLD)
                    .isLessThan(BURN_THRESHOLD);
        }
    }

    /**
     * Approximates what BubbleUp computes: for each dimension value, its
     * prevalence inside the errored population during the incident window versus
     * the healthy baseline population. Both dimensions the slides claim BubbleUp
     * surfaces need a wide enough spread to actually rank near the top on
     * screen.
     */
    @Test
    void bubbleUpDimensionsAreSkewed() {
        Config cfg = testConfig();
        Instant incidentStart = cfg.incidentStart();

        List<Request> errored = new ArrayList<>();
        List<Request> healthy = new ArrayList<>();
        for (Request r : generate(cfg)) {
            if (r.route() == Route.HEALTHZ || !r.start().isAfter(incidentStart)) {
                continue;
            }
            if (r.errored()) {
                errored.add(r);
            } else {
                healthy.add(r);
            }
        }

        // minSpread is in percentage points.
        record Dimension(String name, Function<Request, String> value, String want, double minSpread) {
        }
        List<Dimension> tests = List.of(
                new Dimension("service.version", Request::version, cfg.buggyVersion(), 40),
                new Dimension("user.type", Request::userType, "enterprise", 40));

        for (Dimension tc : tests) {
            double in = prevalence(errored, tc.value(), tc.want());
            double base = prevalence(healthy, tc.value(), tc.want());
            double spread = (in - base) * 100;
            assertThat(spread)
                    .as("%s=%s is %.0f%% inside errors vs %.0f%% healthy baseline; want at least %.0f points to rank in BubbleUp",
                            tc.name(), tc.want(), in * 100, base * 100, tc.minSpread)
                    .isGreaterThanOrEqualTo(tc.minSpread);
        }
    }

    private static double prevalence(List<Request> reqs, Function<Request, String> value, String want) {
        if (reqs.isEmpty()) {
            return 0;
        }
        long n = reqs.stream().filter(r -> value.apply(r).equals(want)).count();
        return (double) n / reqs.size();
    }
}
