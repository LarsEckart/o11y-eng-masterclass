package io.honeycomb.masterclass.slisslos.scenario;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** Produces the dataset described in the package documentation. */
public final class Generator {

    private Generator() {
    }

    /**
     * Produces the dataset. Requests come back in arbitrary time order, which
     * is fine: each carries its own explicit start timestamp.
     *
     * <p>The order of the random draws below matches the Go version, so the
     * distributions are the same. The exact numbers for a given seed are not:
     * Go's {@code math/rand} and Java's {@link java.util.Random} are different
     * algorithms.
     */
    public static List<Request> generate(Config cfg, RandomGenerator rng) {
        Instant incidentStart = cfg.incidentStart();
        List<Request> out = new ArrayList<>(cfg.requestCount());

        for (int i = 0; i < cfg.requestCount(); i++) {
            Instant start = cfg.now().minusNanos(rng.nextLong(cfg.window().toNanos()));

            if (rng.nextDouble() < cfg.healthzShare()) {
                out.add(new Request(
                        start,
                        Route.HEALTHZ,
                        "GET",
                        "system",
                        0,
                        versionFor(cfg, rng),
                        false,
                        false,
                        "",
                        jitter(rng, Duration.ofMillis(2), Duration.ofMillis(6))));
                continue;
            }

            String version = versionFor(cfg, rng);
            String userType = userTypeFor(cfg, rng);
            boolean inWindow = start.isAfter(incidentStart);
            boolean incident = inWindow && version.equals(cfg.buggyVersion()) && userType.equals("enterprise");

            double errRate = incident ? cfg.incidentErrorRate() : cfg.baselineErrorRate();
            boolean errored = rng.nextDouble() < errRate;
            String errorType = errored ? "dependency_unavailable" : "";

            out.add(new Request(
                    start,
                    Route.ORDERS,
                    "POST",
                    userType,
                    rng.nextInt(5000),
                    version,
                    incident,
                    errored,
                    errorType,
                    jitter(rng, Duration.ofMillis(20), Duration.ofMillis(90))));
        }

        return out;
    }

    private static String versionFor(Config cfg, RandomGenerator rng) {
        return rng.nextDouble() < cfg.buggyShare() ? cfg.buggyVersion() : cfg.healthyVersion();
    }

    /**
     * Splits traffic free/premium/enterprise, holding the free:premium ratio at
     * 5:3 among the non-enterprise remainder regardless of
     * {@code cfg.enterpriseShare()}.
     */
    private static String userTypeFor(Config cfg, RandomGenerator rng) {
        double nonEnterprise = 1 - cfg.enterpriseShare();
        double freeCut = nonEnterprise * 5 / 8;
        double premiumCut = nonEnterprise;
        double r = rng.nextDouble();
        if (r < freeCut) {
            return "free";
        }
        if (r < premiumCut) {
            return "premium";
        }
        return "enterprise";
    }

    private static Duration jitter(RandomGenerator rng, Duration min, Duration max) {
        return min.plusNanos(rng.nextLong(max.minus(min).toNanos()));
    }
}
