package io.honeycomb.masterclass.seedarbitraryquestion.wideevent;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * One checkout attempt, fully resolved. Every field here becomes an attribute;
 * see {@link Generator} on why there are so many.
 *
 * <p>This is the Java port of Go's {@code wideevent.Event}. Go keeps this
 * struct, the constants below and the generator in one package; in Java the
 * record gets its own file and the generator lives in {@link Generator}.
 *
 * @param localHour the hour of {@code start} in the user's local time, 0-23.
 *     Carried as an explicit attribute rather than left implicit in the
 *     timestamp: "during lunch hour" is a local-time concept, and filtering on
 *     an integer is both unambiguous and something Honeycomb can do without a
 *     derived column.
 */
public record Event(
        ZonedDateTime start,
        Duration duration,
        int localHour,

        // Outcome
        int statusCode,
        boolean errored,
        String errorType,
        String errorSlug,

        // Request and execution
        String route,
        String urlPath,
        String method,
        long durationDb,

        // Identity and business context
        String userId,
        String userType,
        String userOrgId,
        String authMethod,

        // Client
        String device,
        String app,
        String appVersion,
        String osName,

        // Geography
        String countryIso,
        String regionIso,

        // Localization
        String language,
        String currency,

        // Service and code context
        String serviceVersion,
        String serviceEnv,
        String buildGitHash,
        long deployAgeMin,
        boolean featureFlagNew,

        // Operational
        long rateLimitLimit,
        long rateLimitRemaining,
        long postgresQueryCount,
        long redisQueryCount,
        String instanceType,
        String cloudRegion) {

    /**
     * {@code LUNCH_HOUR_START} and {@code LUNCH_HOUR_END} bound "lunch hour" in
     * the user's local time. The question asks about lunch hour, so the
     * generator has to concentrate enough traffic there for the answer to be
     * non-empty. The end is exclusive.
     */
    public static final int LUNCH_HOUR_START = 12;

    public static final int LUNCH_HOUR_END = 14;

    /**
     * {@code ANSWER_VERSION}, {@code ANSWER_DEVICE}, and {@code ANSWER_REGION}
     * are the values the book's question filters on. Public so the tests and the
     * seeder's closing summary can report how many events actually match,
     * rather than asserting a number nobody verified.
     */
    public static final String ANSWER_VERSION = "2.3.1";

    public static final String ANSWER_DEVICE = "phone";
    public static final String ANSWER_REGION = "US-CA";

    /**
     * Reports whether this event is one the book's question is asking for. The
     * generator, the seeder summary, and the tests all go through this so the
     * definition exists in exactly one place.
     */
    public boolean matchesArbitraryQuestion() {
        if (!errored) {
            return false;
        }
        if (!device.equals(ANSWER_DEVICE) || !regionIso.equals(ANSWER_REGION) || !appVersion.equals(ANSWER_VERSION)) {
            return false;
        }
        return localHour >= LUNCH_HOUR_START && localHour < LUNCH_HOUR_END;
    }
}
