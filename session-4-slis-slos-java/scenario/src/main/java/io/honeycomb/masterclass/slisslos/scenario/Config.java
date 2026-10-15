package io.honeycomb.masterclass.slisslos.scenario;

import java.time.Duration;
import java.time.Instant;

/**
 * Describes one generated dataset.
 *
 * <p>A record is immutable, so where the Go code assigns {@code cfg.Now = ...}
 * the Java code calls {@code cfg.withNow(...)} and gets a modified copy.
 *
 * @param requestCount total number of requests across the window, /healthz
 *     included.
 * @param window how far back from {@code now} the dataset extends.
 * @param incidentAgo how long before {@code now} the incident started. It is
 *     still ongoing at {@code now}, deliberately, so a trailing-1-hour burn
 *     query and a live trigger both see it as current rather than historical.
 * @param now anchors the dataset. Passed in rather than read from the clock so
 *     generation is deterministic and testable.
 * @param buggyShare fraction of /api/orders traffic running {@code buggyVersion},
 *     throughout the whole window (an incomplete rollout, not a deploy that
 *     happens mid-window, so there is no single deploy time to mark here).
 * @param healthzShare fraction of all requests that are health checks rather
 *     than /api/orders. Health checks always succeed.
 * @param enterpriseShare fraction of /api/orders traffic on the enterprise
 *     plan. The free:premium split among the remainder holds the same 5:3
 *     ratio regardless of this value; see {@code Generator.userTypeFor}.
 * @param baselineErrorRate ordinary background 5xx rate on /api/orders,
 *     outside the incident population.
 * @param incidentErrorRate 5xx rate for the affected population: buggy build,
 *     enterprise plan, /api/orders, inside the incident window.
 */
public record Config(
        int requestCount,
        Duration window,
        Duration incidentAgo,
        Instant now,
        String healthyVersion,
        String buggyVersion,
        double buggyShare,
        double healthzShare,
        double enterpriseShare,
        double baselineErrorRate,
        double incidentErrorRate) {

    /**
     * The tuned demo configuration described in the package comment.
     *
     * @param now the anchor instant; the caller supplies it (see {@link #now}).
     */
    public static Config defaults(Instant now) {
        return new Config(
                15000,
                Duration.ofHours(6),
                Duration.ofMinutes(60),
                now,
                "1.5.0",
                "1.4.2",
                0.43,
                0.57,
                0.33,
                0.01,
                0.95);
    }

    /** The instant the incident began. It runs from here through {@code now}. */
    public Instant incidentStart() {
        return now.minus(incidentAgo);
    }

    public Config withRequestCount(int requestCount) {
        return new Config(requestCount, window, incidentAgo, now, healthyVersion, buggyVersion,
                buggyShare, healthzShare, enterpriseShare, baselineErrorRate, incidentErrorRate);
    }

    public Config withWindow(Duration window) {
        return new Config(requestCount, window, incidentAgo, now, healthyVersion, buggyVersion,
                buggyShare, healthzShare, enterpriseShare, baselineErrorRate, incidentErrorRate);
    }

    public Config withIncidentAgo(Duration incidentAgo) {
        return new Config(requestCount, window, incidentAgo, now, healthyVersion, buggyVersion,
                buggyShare, healthzShare, enterpriseShare, baselineErrorRate, incidentErrorRate);
    }

    public Config withNow(Instant now) {
        return new Config(requestCount, window, incidentAgo, now, healthyVersion, buggyVersion,
                buggyShare, healthzShare, enterpriseShare, baselineErrorRate, incidentErrorRate);
    }
}
