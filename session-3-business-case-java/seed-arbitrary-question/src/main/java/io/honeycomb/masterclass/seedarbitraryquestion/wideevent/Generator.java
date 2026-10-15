package io.honeycomb.masterclass.seedarbitraryquestion.wideevent;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates the Masterclass 3 dataset: genuinely wide checkout events for the
 * Arbitrary Question Test. This is the Java port of Go's
 * {@code internal/wideevent} package.
 *
 * <h2>What this is for</h2>
 *
 * <p>Chapter 28's Arbitrary Question Test gives a specific question:
 *
 * <blockquote>"Show me all failed checkout attempts from mobile users in
 * California using version 2.3.1 of the app during lunch hour over the past
 * week."</blockquote>
 *
 * <p>Legacy observability needs those dimensions indexed in advance and answers
 * in hours or days, or not at all. Modern observability answers in under 60
 * seconds because nobody had to anticipate the question.
 *
 * <h2>Why the events are deliberately wide</h2>
 *
 * <p>A dataset carrying exactly the four fields that question needs would prove
 * nothing; an attendee would rightly point out it was built for the demo. So
 * events here carry ~30 attributes drawn from Chapter 6's tables, of which the
 * question happens to use four. The claim being demonstrated is not "we have
 * the right fields," it is "we kept everything, so the question is answerable
 * without having predicted it."
 *
 * <p>This is also the wide event Masterclass 1 talks about and, deliberately,
 * does not have: its seeded events carry about a dozen fields. If you want a
 * dataset that argues the wide-events case on its own, this is the one.
 *
 * <h2>Attribute naming</h2>
 *
 * <p>Names come from the book's Chapter 6 tables where it has an opinion, and
 * from OTel semantic conventions otherwise:
 *
 * <ul>
 *   <li>{@code user_agent.device} with values computer/tablet/phone (Table 6-9).
 *       Note that "mobile users" in the question means device == phone; there
 *       is no "mobile" value in the convention.
 *   <li>{@code user_agent.app} and {@code user_agent.app_version} (Table 6-10).
 *   <li>{@code geo.country.iso_code} and {@code geo.region.iso_code}, from OTel
 *       semconv. These are Development-stage rather than stable, and
 *       "California" is US-CA in ISO 3166-2. They are written as plain string
 *       attributes because the semconv library we depend on only ships stable
 *       names. (In Go the same choice kept the module on one pinned version.)
 *   <li>{@code localization.*} (Table 6-18), {@code service.build.*} (Table
 *       6-5), {@code ratelimit.*} (Table 6-16), {@code stats.*} (Table 6-13),
 *       {@code user.*} (Table 6-15).
 * </ul>
 *
 * <h2>Randomness</h2>
 *
 * <p>The generator takes a {@link Random} so tests can seed it. Go's
 * {@code rand.NewSource(11)} and Java's {@code new Random(11)} are different
 * algorithms, so the same seed gives different events in the two languages. The
 * distributions (weights, ranges) are identical.
 */
public final class Generator {

    private Generator() {}

    /** Produces the dataset. */
    public static List<Event> generate(Config cfg, Random rng) {
        List<Event> out = new ArrayList<>(cfg.eventCount());
        for (int i = 0; i < cfg.eventCount(); i++) {
            out.add(generateOne(cfg, rng));
        }
        return out;
    }

    // The order of the random draws below follows the Go version line by line,
    // so the two programs consume their random streams in the same shape.
    private static Event generateOne(Config cfg, Random rng) {
        ZonedDateTime start = startTime(cfg, rng);

        String device = weighted(rng, wv("phone", 0.55), wv("computer", 0.35), wv("tablet", 0.10));

        Client client = clientFor(device, rng);
        Geo geo = geoFor(rng);
        String userType = weighted(rng, wv("free", 0.60), wv("premium", 0.30), wv("enterprise", 0.10));

        Failure failure = failureFor(rng);

        long dbDuration = 20 + rng.nextInt(120);
        Duration duration = Duration.ofMillis(60 + rng.nextInt(400));

        String userId = "user_" + rng.nextInt(20000);
        String userOrgId = "org_" + rng.nextInt(800);
        String authMethod = weighted(rng, wv("token", 0.6), wv("sso-github", 0.25), wv("basic-auth", 0.15));

        String language = weighted(rng, wv("en-US", 0.7), wv("es-MX", 0.15), wv("fr-CA", 0.1), wv("de-DE", 0.05));
        String currency = weighted(rng, wv("USD", 0.8), wv("CAD", 0.1), wv("EUR", 0.1));

        long deployAgeMin = 30 + rng.nextInt(4000);
        boolean featureFlagNew = rng.nextDouble() < 0.25;

        long rateLimitRemaining = rng.nextInt(200000);
        long postgresQueryCount = 3 + rng.nextInt(12);
        long redisQueryCount = 1 + rng.nextInt(8);
        String instanceType = weighted(rng, wv("m6i.xlarge", 0.6), wv("m7g.xlarge", 0.4));
        String cloudRegion = weighted(rng, wv("us-east-1", 0.5), wv("us-west-2", 0.3), wv("eu-west-1", 0.2));

        return new Event(
                start,
                duration,
                start.getHour(),

                statusFor(failure.errored()),
                failure.errored(),
                failure.type(),
                failure.slug(),

                "/api/checkout",
                "/api/checkout",
                "POST",
                dbDuration,

                userId,
                userType,
                userOrgId,
                authMethod,

                device,
                client.app(),
                client.appVersion(),
                client.osName(),

                geo.country(),
                geo.region(),

                language,
                currency,

                "1.4.2",
                "production",
                "6f6466b0e693470729b669f3745358df29f97e8d",
                deployAgeMin,
                featureFlagNew,

                200000,
                rateLimitRemaining,
                postgresQueryCount,
                redisQueryCount,
                instanceType,
                cloudRegion);
    }

    /**
     * Spreads events across the window with a daily shape that has a real
     * lunch-hour bulge, because the question filters on it.
     */
    static ZonedDateTime startTime(Config cfg, Random rng) {
        // Go: Int63n over the window in nanoseconds. nextLong(bound) is the
        // Java equivalent: uniform in [0, bound).
        ZonedDateTime base = cfg.now().minus(Duration.ofNanos(rng.nextLong(cfg.window().toNanos())));

        // Roughly a fifth of traffic is pulled into the 12:00-14:00 window. Two
        // hours out of 24 would otherwise be ~8% of events, which leaves too
        // few matches once the other three filters are applied.
        if (rng.nextDouble() >= 0.20) {
            return base;
        }

        int hour = Event.LUNCH_HOUR_START + rng.nextInt(Event.LUNCH_HOUR_END - Event.LUNCH_HOUR_START);
        int minute = rng.nextInt(60);
        int second = rng.nextInt(60);
        ZonedDateTime lunch = ZonedDateTime.of(base.toLocalDate(), LocalTime.of(hour, minute, second), base.getZone());

        // Rewriting the hour can move a timestamp across either edge of the
        // window: an event drawn near the start of the window and reset to noon
        // can land before the window opens, and one drawn near the end can land
        // after "now". Nudge by a day where that fixes it, and otherwise leave
        // the event at its original time rather than emit something out of
        // range.
        ZonedDateTime oldest = cfg.now().minus(cfg.window());
        if (lunch.isBefore(oldest)) {
            lunch = lunch.plusDays(1);
        } else if (lunch.isAfter(cfg.now())) {
            lunch = lunch.minusDays(1);
        }
        if (lunch.isBefore(oldest) || lunch.isAfter(cfg.now())) {
            return base;
        }
        return lunch;
    }

    /** App and OS of the client; empty strings mean "no app", as in Go. */
    private record Client(String app, String appVersion, String osName) {}

    /**
     * Keeps the client fields self-consistent: a desktop browser has no app
     * version, and iOS builds do not run on Android.
     */
    private static Client clientFor(String device, Random rng) {
        if (device.equals("computer")) {
            return new Client("", "", weighted(rng, wv("macOS", 0.5), wv("Windows", 0.4), wv("Linux", 0.1)));
        }

        String app = weighted(rng, wv("iOS", 0.6), wv("android", 0.4));
        // 2.3.1 is the version the book's question asks about. A realistic
        // install base is spread across several versions, so it is a
        // plurality, not everything; otherwise the filter would not narrow
        // anything.
        String appVersion = weighted(rng,
                wv("2.3.1", 0.35), wv("2.3.0", 0.25), wv("2.2.4", 0.20), wv("2.4.0-beta", 0.20));
        if (app.equals("iOS")) {
            return new Client(app, appVersion, "iOS");
        }
        return new Client(app, appVersion, "Android");
    }

    private record Geo(String country, String region) {}

    /**
     * Returns country and ISO 3166-2 region. California is deliberately the
     * largest single region, as it would be for a US consumer product.
     */
    private static Geo geoFor(Random rng) {
        double r = rng.nextDouble();
        if (r < 0.28) {
            return new Geo("US", "US-CA");
        } else if (r < 0.44) {
            return new Geo("US", "US-NY");
        } else if (r < 0.56) {
            return new Geo("US", "US-TX");
        } else if (r < 0.66) {
            return new Geo("US", "US-WA");
        } else if (r < 0.74) {
            return new Geo("US", "US-IL");
        } else if (r < 0.86) {
            return new Geo("CA", "CA-ON");
        } else if (r < 0.94) {
            return new Geo("GB", "GB-ENG");
        }
        return new Geo("DE", "DE-BE");
    }

    private record Failure(boolean errored, String type, String slug) {}

    private static Failure failureFor(Random rng) {
        double r = rng.nextDouble();
        if (r < 0.055) {
            return new Failure(true, "payment_declined", "err-stripe-card-declined");
        } else if (r < 0.075) {
            return new Failure(true, "payment_method_expired", "err-stripe-card-expired");
        } else if (r < 0.085) {
            return new Failure(true, "inventory_unavailable", "err-inventory-oversold");
        }
        return new Failure(false, "", "");
    }

    private static int statusFor(boolean errored) {
        return errored ? 402 : 201;
    }

    private record WeightedValue(String value, double weight) {}

    private static WeightedValue wv(String value, double weight) {
        return new WeightedValue(value, weight);
    }

    /**
     * Picks one value with probability proportional to its weight. The weights
     * in this file add up to 1; if rounding leaves a gap at the top, the last
     * value takes it.
     */
    private static String weighted(Random rng, WeightedValue... values) {
        double r = rng.nextDouble();
        double cumulative = 0;
        for (WeightedValue v : values) {
            cumulative += v.weight();
            if (r < cumulative) {
                return v.value();
            }
        }
        return values[values.length - 1].value();
    }
}
