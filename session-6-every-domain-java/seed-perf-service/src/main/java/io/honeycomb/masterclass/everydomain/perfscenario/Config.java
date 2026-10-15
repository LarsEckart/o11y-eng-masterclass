package io.honeycomb.masterclass.everydomain.perfscenario;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Describes one generated dataset.
 *
 * <p>Go's {@code Config} is a mutable struct the seeder edits field by field.
 * This is an immutable record, so an override such as {@code SEED_COUNT}
 * produces a new one through a {@code with...} method.</p>
 *
 * @param userTypeShares       must sum to 1.0; see
 *                             {@code ScenarioTest.userTypeSharesSumToOne}
 * @param enterpriseMultiplier scales duration for {@code user.type=enterprise}
 *                             requests on top of whatever the template produced
 */
public record Config(
        int requestCount,
        Duration window,
        Duration migrationAgo,
        Instant now,
        List<QueryTemplate> templates,
        Map<String, Double> userTypeShares,
        double enterpriseMultiplier) {

    /**
     * The tuned demo configuration described in {@link Scenario}, anchored at
     * {@code now}.
     */
    public static Config defaults(Instant now) {
        return new Config(
                24000,
                Duration.ofHours(48),
                Duration.ofHours(24),
                now,
                List.of(
                        new QueryTemplate("SELECT * FROM users WHERE id = $1", QueryKind.FAST, Duration.ofMillis(5)),
                        new QueryTemplate("SELECT * FROM orders WHERE user_id = $1", QueryKind.FAST, Duration.ofMillis(8)),
                        new QueryTemplate("UPDATE inventory SET quantity = $1 WHERE sku = $2", QueryKind.FAST, Duration.ofMillis(12)),
                        new QueryTemplate("SELECT * FROM orders WHERE status = $1", QueryKind.BIMODAL,
                                Duration.ofMillis(10), Duration.ofMillis(250), 0.20),
                        new QueryTemplate("SELECT * FROM audit_log WHERE created_at > $1", QueryKind.LONG_TAIL, Duration.ofMillis(20))),
                Map.of(
                        "free", 0.50,
                        "premium", 0.30,
                        "enterprise", 0.20),
                1.8);
    }

    public Config withRequestCount(int requestCount) {
        return new Config(requestCount, window, migrationAgo, now, templates, userTypeShares, enterpriseMultiplier);
    }

    /**
     * The instant the Graviton migration completed. Requests before this are
     * amd64; at and after, arm64.
     */
    public Instant migrationStart() {
        return now.minus(migrationAgo);
    }
}
