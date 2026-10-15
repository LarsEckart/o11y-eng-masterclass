package io.honeycomb.masterclass.everydomain.perfscenario;

import java.time.Duration;

/**
 * One parameterized {@code db.query.text} shape.
 *
 * @param slowDuration used only for {@link QueryKind#BIMODAL}
 * @param slowShare    used only for {@link QueryKind#BIMODAL}
 */
public record QueryTemplate(
        String text,
        QueryKind kind,
        Duration baseline,
        Duration slowDuration,
        double slowShare) {

    /** A template with no slow cluster (fast and long-tail kinds). */
    public QueryTemplate(String text, QueryKind kind, Duration baseline) {
        this(text, kind, baseline, Duration.ZERO, 0);
    }
}
