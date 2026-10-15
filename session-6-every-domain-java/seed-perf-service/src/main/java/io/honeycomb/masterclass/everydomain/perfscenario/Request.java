package io.honeycomb.masterclass.everydomain.perfscenario;

import java.time.Duration;
import java.time.Instant;

/** One generated request, fully resolved. */
public record Request(
        Instant start,
        String queryText,
        String userType,
        String arch,
        Duration duration) {
}
