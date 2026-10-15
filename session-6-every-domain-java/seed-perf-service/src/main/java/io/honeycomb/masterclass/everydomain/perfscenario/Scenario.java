package io.honeycomb.masterclass.everydomain.perfscenario;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates the Masterclass 6 performance-engineering dataset. (Go:
 * {@code internal/perfscenario}.) Plain Java with no OpenTelemetry in it: it
 * decides what the requests look like, and {@code SeedPerfService} turns them
 * into spans.
 *
 * <h2>The scenario</h2>
 *
 * <p>One service, five parameterized {@code db.query.text} templates, and a
 * Graviton (amd64 to arm64) migration marker halfway through the seeded window.
 * Chapter 20's workflow needs three different query duration shapes to
 * demonstrate its first three steps, and a stable shape across the migration
 * point to demonstrate the fourth:</p>
 *
 * <ul>
 *   <li>Fast: three templates (Users, Orders, Inventory) with tight jitter
 *       around a low baseline, the uninteresting majority of traffic.</li>
 *   <li>Bimodal (OrdersByStatus): most requests are fast, but a fixed share
 *       land on a slow path (a missing index on one status value, left unnamed
 *       on purpose: the discovery is the heatmap's two clusters, not a
 *       groupby). A single P99 number over this template averages the two
 *       clusters together and hides both of them; a heatmap doesn't.</li>
 *   <li>Long-tail (AuditLog): a continuous spread from the baseline up to
 *       several multiples of it, with no second cluster. Chapter 20's point
 *       that "a bimodal heatmap is a different problem than a long tail" only
 *       lands if the dataset actually contains one of each; see
 *       {@code ScenarioTest.bimodalHasTwoClustersLongTailDoesNot}.</li>
 * </ul>
 *
 * <p>Every request also carries a {@code user.type} (free/premium/enterprise),
 * and enterprise requests run measurably slower (larger accounts, more data),
 * which is Chapter 20's third step ({@code AVG(duration_ms) GROUP BY user.type}).</p>
 *
 * <h2>The migration</h2>
 *
 * <p>{@code host.arch} is amd64 for the first half of the window and arm64 for
 * the second, split at {@link Config#migrationAgo()}, with no change to any
 * duration distribution. That's the point: Chapter 20's worked example trusts
 * production data to show the migration was safe, and the seeded data has to
 * actually be safe for a P50/P95 before/after comparison to say so honestly. See
 * {@code ScenarioTest.noLatencyRegressionAcrossMigration}.</p>
 *
 * <h2>Delivery test sizing</h2>
 *
 * <p>One span per request (no nested trace: this dataset is about query
 * duration analysis, not a waterfall), so {@code requestCount} alone must clear
 * the SDK's default 2048-span queue with margin; see the seeder's
 * {@code MAX_QUEUE_SIZE} and {@code ScenarioTest.requestCountExceedsQueueMargin}.</p>
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
     * different requests.</p>
     */
    public static List<Request> generate(Config cfg, Random rng) {
        Instant migrationStart = cfg.migrationStart();
        var out = new ArrayList<Request>(cfg.requestCount());

        CumulativeShares userShares = CumulativeShares.of(cfg.userTypeShares());

        for (int i = 0; i < cfg.requestCount(); i++) {
            // Go: Int63n(window). Random.nextLong(bound) is the same idea:
            // uniform in [0, bound).
            Instant start = cfg.now().minusNanos(rng.nextLong(cfg.window().toNanos()));
            QueryTemplate tmpl = cfg.templates().get(rng.nextInt(cfg.templates().size()));
            String userType = userShares.pick(rng);

            Duration duration = durationFor(tmpl, rng);
            if (userType.equals("enterprise")) {
                duration = Duration.ofNanos((long) (duration.toNanos() * cfg.enterpriseMultiplier()));
            }

            // At or after the migration instant, so: not before.
            String arch = start.isBefore(migrationStart) ? "amd64" : "arm64";

            out.add(new Request(start, tmpl.text(), userType, arch, duration));
        }

        return out;
    }

    private static Duration durationFor(QueryTemplate tmpl, Random rng) {
        return switch (tmpl.kind()) {
            case BIMODAL -> {
                if (rng.nextDouble() < tmpl.slowShare()) {
                    yield jitter(rng, tmpl.slowDuration(), 0.1);
                }
                yield jitter(rng, tmpl.baseline(), 0.1);
            }
            // Exponential tail: most draws stay near the baseline, a continuous
            // minority stretch out to several multiples of it. An exponential
            // draw has mean 1, so scaling it by the baseline keeps the tail's
            // shape tied to the template's own baseline rather than a fixed
            // constant.
            //
            // Go has rng.ExpFloat64(). Java's Random has no exponential draw
            // (only uniform and Gaussian), so we use inverse-transform
            // sampling: -ln(1 - U) for uniform U in [0, 1) is exponentially
            // distributed with mean 1. It is the same distribution; Go just
            // uses a faster algorithm (the ziggurat) to get it.
            case LONG_TAIL -> {
                double exp = -Math.log(1 - rng.nextDouble());
                yield tmpl.baseline().plus(Duration.ofNanos((long) (exp * tmpl.baseline().toNanos() * 1.5)));
            }
            case FAST -> jitter(rng, tmpl.baseline(), 0.15);
        };
    }

    private static Duration jitter(Random rng, Duration base, double spread) {
        double factor = 1 + spread * (2 * rng.nextDouble() - 1);
        long nanos = (long) (base.toNanos() * factor);
        return nanos < 0 ? Duration.ZERO : Duration.ofNanos(nanos);
    }

    /**
     * A share map turned into a stable-ordered list of keys and their
     * cumulative boundaries, so {@link #pick}'s selection is deterministic for
     * a given {@code rng} sequence regardless of map iteration order. Sorted
     * rather than hard-coded so a caller adding a user type to
     * {@link Config#userTypeShares()} gets it included instead of silently
     * dropped.
     *
     * <p>Go returns two parallel slices from a function; a small record keeps
     * the two lists together in Java.</p>
     */
    private record CumulativeShares(List<String> keys, double[] cumulative) {

        static CumulativeShares of(Map<String, Double> shares) {
            List<String> keys = shares.keySet().stream().sorted().toList();
            double[] cum = new double[keys.size()];
            double running = 0;
            for (int i = 0; i < keys.size(); i++) {
                running += shares.get(keys.get(i));
                cum[i] = running;
            }
            return new CumulativeShares(keys, cum);
        }

        String pick(Random rng) {
            double r = rng.nextDouble();
            for (int i = 0; i < cumulative.length; i++) {
                if (r < cumulative[i]) {
                    return keys.get(i);
                }
            }
            return keys.get(keys.size() - 1);
        }
    }

    /**
     * Returns the p-th percentile (0-100) duration from a list of requests.
     * Exposed so both the tests and the seeder's summary output can compute it
     * the same way.
     */
    public static Duration percentile(List<Request> reqs, double p) {
        if (reqs.isEmpty()) {
            return Duration.ZERO;
        }
        List<Duration> durations = reqs.stream()
                .map(Request::duration)
                .sorted(Comparator.naturalOrder())
                .toList();
        int idx = (int) Math.ceil(p / 100 * durations.size()) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= durations.size()) {
            idx = durations.size() - 1;
        }
        return durations.get(idx);
    }
}
