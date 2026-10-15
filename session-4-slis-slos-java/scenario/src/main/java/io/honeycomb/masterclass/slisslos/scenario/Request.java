package io.honeycomb.masterclass.slisslos.scenario;

import java.time.Duration;
import java.time.Instant;

/**
 * One generated request, fully resolved. The generator decides everything up
 * front so the emitter stays dumb and the scenario logic stays testable
 * without an OTel SDK.
 *
 * @param incident true when this request falls inside the affected population:
 *     buggy build, enterprise, /api/orders, incident window. Exposed so tests
 *     can assert on the population rather than re-deriving the rule.
 */
public record Request(
        Instant start,
        Route route,
        String method,
        String userType,
        int userId,
        String version,
        boolean incident,
        boolean errored,
        String errorType,
        Duration duration) {

    /** The HTTP status this request returned. */
    public int statusCode() {
        return errored ? 503 : 200;
    }
}
