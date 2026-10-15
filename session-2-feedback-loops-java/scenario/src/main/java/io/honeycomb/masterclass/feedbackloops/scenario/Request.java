package io.honeycomb.masterclass.feedbackloops.scenario;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One generated root request, fully resolved. The generator decides everything
 * about a request up front so the emitter stays dumb and the scenario logic
 * stays testable without an OTel SDK.
 *
 * @param start     when the request began
 * @param route     which endpoint it hit
 * @param userType  the user's plan: free, premium, or enterprise
 * @param userId    a number in [0, 5000); the emitter renders it as {@code user_<n>}
 * @param version   the {@code service.version} that served it
 * @param regressed true when this request hit the canary regression. Exposed so
 *                  tests can assert on the population rather than re-deriving
 *                  the rule.
 * @param errored   whether the request failed. This and {@code errorType} carry
 *                  the ordinary background failure rate. They are deliberately
 *                  independent of the canary: the book's example is a pure
 *                  latency regression, and keeping errors uncorrelated means
 *                  BubbleUp has to surface the latency dimensions on their own
 *                  merits rather than riding on an error signal that would have
 *                  tripped a conventional alert anyway.
 * @param errorType empty when the request did not fail
 * @param steps     the child spans, in order
 */
public record Request(
        Instant start,
        Route route,
        String userType,
        int userId,
        String version,
        boolean regressed,
        boolean errored,
        String errorType,
        List<Step> steps) {

    /** The HTTP status this request returned. */
    public int statusCode() {
        if (!errored) {
            return route == Route.BILLING ? 200 : 201;
        }
        return 402;
    }

    /** The total wall time of the request. */
    public Duration duration() {
        var total = Duration.ZERO;
        for (var step : steps) {
            total = total.plus(step.duration());
        }
        return total;
    }
}
