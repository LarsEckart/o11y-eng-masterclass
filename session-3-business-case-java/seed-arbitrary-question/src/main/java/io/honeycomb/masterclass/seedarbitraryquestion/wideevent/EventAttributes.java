package io.honeycomb.masterclass.seedarbitraryquestion.wideevent;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.UrlAttributes;

/** Maps an {@link Event} to the attributes that reach Honeycomb. */
public final class EventAttributes {

    private EventAttributes() {}

    /**
     * The canonical mapping from an {@link Event} to the attributes that reach
     * Honeycomb. It lives here rather than in the seeder so the width of the
     * event, the whole premise of the Arbitrary Question Test, is testable
     * without standing up an exporter.
     *
     * <p>Naming follows the book's Chapter 6 tables where it has an opinion, and
     * OTel semantic conventions otherwise. See {@link Generator} for the
     * specifics, including why geo.* is written as plain strings.
     *
     * <p>Go returns a slice of key/value pairs; the Java SDK uses an
     * {@link Attributes} map, so the order here is for the reader only.
     */
    public static Attributes of(Event e) {
        AttributesBuilder attrs = Attributes.builder()
                // Request and execution flow (Table 6-8, 6-11)
                .put(HttpAttributes.HTTP_REQUEST_METHOD, "POST")
                .put(HttpAttributes.HTTP_ROUTE, e.route())
                .put(UrlAttributes.URL_PATH, e.urlPath())
                .put(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, (long) e.statusCode())
                .put("db.query.duration_ms", e.durationDb())
                .put("request.local_hour", (long) e.localHour())

                // User and business context (Table 6-15)
                .put("user.id", e.userId())
                .put("user.type", e.userType())
                .put("user.org.id", e.userOrgId())
                .put("user.auth_method", e.authMethod())

                // Client (Tables 6-9, 6-10). "mobile users" in the book's
                // question means user_agent.device == "phone"; there is no
                // "mobile" value.
                .put("user_agent.device", e.device())
                .put("user_agent.OS", e.osName())

                // Geography. OTel semconv names, Development stability;
                // California is US-CA in ISO 3166-2.
                .put("geo.country.iso_code", e.countryIso())
                .put("geo.region.iso_code", e.regionIso())

                // Localization (Table 6-18)
                .put("localization.language", e.language())
                .put("localization.currency", e.currency())

                // Service and code context (Tables 6-1, 6-5, 6-6)
                .put("service.version", e.serviceVersion())
                .put("service.environment", e.serviceEnv())
                .put("service.build.git_hash", e.buildGitHash())
                .put("service.build.deployment.age_minutes", e.deployAgeMin())
                .put("feature_flag.new_checkout_flow", e.featureFlagNew())

                // Rate limits (Table 6-16)
                .put("ratelimit.limit", e.rateLimitLimit())
                .put("ratelimit.remaining", e.rateLimitRemaining())

                // Async request summaries (Table 6-13)
                .put("stats.postgres_query_count", e.postgresQueryCount())
                .put("stats.redis_query_count", e.redisQueryCount())

                // Infrastructure (Table 6-2, 6-3)
                .put("instance.type", e.instanceType())
                .put("cloud.region", e.cloudRegion());

        // Only mobile clients report an app and version, so these are added
        // conditionally rather than emitted empty.
        if (!e.app().isEmpty()) {
            attrs.put("user_agent.app", e.app());
            attrs.put("user_agent.app_version", e.appVersion());
        }

        // Errors (Table 6-14). error is a literal boolean because that is what
        // the queries filter on.
        if (e.errored()) {
            attrs.put(AttributeKey.booleanKey("error"), true);
            attrs.put("error.type", e.errorType());
            attrs.put("exception.slug", e.errorSlug());
        }

        return attrs.build();
    }
}
