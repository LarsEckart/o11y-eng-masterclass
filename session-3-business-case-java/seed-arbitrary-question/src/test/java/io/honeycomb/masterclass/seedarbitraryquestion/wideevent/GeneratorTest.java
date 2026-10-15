package io.honeycomb.masterclass.seedarbitraryquestion.wideevent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class GeneratorTest {

    private static Config cfg;
    private static List<Event> events;

    /**
     * Every test reads the same 20,000 events. Go regenerates them per test;
     * generating once is faster and equivalent, because the seed is fixed.
     */
    @BeforeAll
    static void generate() {
        cfg = Config.defaultConfig(ZonedDateTime.of(2026, 9, 2, 15, 0, 0, 0, ZoneOffset.UTC));
        events = Generator.generate(cfg, new Random(11));
    }

    /**
     * The load-bearing test: the demo is Liz running the book's question live,
     * so the dataset has to contain enough matching events to return a
     * non-trivial answer. Too few and the screen shows an empty result; too
     * many and the four filters are obviously not narrowing anything, which
     * undercuts the point.
     */
    @Test
    void arbitraryQuestionHasAnAnswer() {
        long matches = events.stream().filter(Event::matchesArbitraryQuestion).count();

        assertThat(matches)
                .as("events that match the book's question; the live demo would show a near-empty result")
                .isGreaterThanOrEqualTo(5);
        double share = (double) matches / events.size();
        assertThat(share)
                .as("share of events that match; the filters are not narrowing enough to be interesting")
                .isLessThanOrEqualTo(0.01);
        System.out.printf("%d of %d events match the arbitrary question%n", matches, events.size());
    }

    /**
     * Guards the premise. If the event narrows to a handful of fields, the demo
     * stops demonstrating that the question was answerable without being
     * anticipated, and starts looking staged.
     */
    @Test
    void eventsAreActuallyWide() {
        // Counted off the attributes the emitter produces, which is the number
        // that actually reaches Honeycomb.
        final int wantAtLeast = 25;
        assertThat(EventAttributes.of(events.get(0)).size())
                .as("attributes on one event; too few for the wide-event claim to hold")
                .isGreaterThanOrEqualTo(wantAtLeast);
    }

    /**
     * Catches the sloppiness that makes a synthetic dataset obvious: desktop
     * browsers reporting a mobile app version, or iOS builds running on Android.
     */
    @Test
    void clientFieldsAreSelfConsistent() {
        for (Event e : events) {
            switch (e.device()) {
                case "computer" -> assertThat(e.app().isEmpty() && e.appVersion().isEmpty())
                        .as("desktop event reports app=%s version=%s", e.app(), e.appVersion())
                        .isTrue();
                case "phone", "tablet" -> {
                    assertThat(e.app().isEmpty() || e.appVersion().isEmpty())
                            .as("%s event has no app or version", e.device())
                            .isFalse();
                    if (e.app().equals("iOS")) {
                        assertThat(e.osName()).as("iOS app reporting os").isEqualTo("iOS");
                    }
                    if (e.app().equals("android")) {
                        assertThat(e.osName()).as("android app reporting os").isEqualTo("Android");
                    }
                }
                default -> fail("unexpected device %s", e.device());
            }

            assertThat(e.errored()).as("errored=%s with status %d", e.errored(), e.statusCode())
                    .isEqualTo(e.statusCode() == 402);
            if (e.errored()) {
                assertThat(e.errorType().isEmpty() || e.errorSlug().isEmpty())
                        .as("errored event missing type or slug")
                        .isFalse();
            } else {
                assertThat(e.errorType().isEmpty() && e.errorSlug().isEmpty())
                        .as("successful event carries error fields")
                        .isTrue();
            }
        }
    }

    private record Filter(String name, Predicate<Event> match, double wantBelow, double wantAtLeast) {}

    /**
     * Checks that each of the four dimensions in the question removes a
     * meaningful share of events. If any one of them matched nearly everything,
     * the demo would imply a precision the data does not have.
     */
    @Test
    void everyFilterActuallyNarrows() {
        double total = events.size();

        List<Filter> filters = List.of(
                new Filter("errored", Event::errored, 0.20, 0.02),
                new Filter("device=phone", e -> e.device().equals(Event.ANSWER_DEVICE), 0.70, 0.30),
                new Filter("region=US-CA", e -> e.regionIso().equals(Event.ANSWER_REGION), 0.45, 0.15),
                new Filter("app_version=2.3.1", e -> e.appVersion().equals(Event.ANSWER_VERSION), 0.45, 0.10),
                new Filter("lunch hour",
                        e -> e.localHour() >= Event.LUNCH_HOUR_START && e.localHour() < Event.LUNCH_HOUR_END,
                        0.45, 0.10));

        for (Filter f : filters) {
            double share = events.stream().filter(f.match()).count() / total;
            assertThat(share)
                    .as("%s matches %.1f%% of events; outside the useful range", f.name(), share * 100)
                    .isLessThanOrEqualTo(f.wantBelow())
                    .isGreaterThanOrEqualTo(f.wantAtLeast());
        }
    }

    /** Guards the "past week" part of the question. */
    @Test
    void generateStaysWithinWindow() {
        ZonedDateTime oldest = cfg.now().minus(cfg.window());

        for (Event e : events) {
            assertThat(e.start().isBefore(oldest) || e.start().isAfter(cfg.now()))
                    .as("event at %s falls outside the %s window ending %s", e.start(), cfg.window(), cfg.now())
                    .isFalse();
        }
    }

    /**
     * Java-only: the same window guard in a time zone with daylight saving, since
     * the lunch-hour rewrite works on local dates and clocks.
     */
    @Test
    void generateStaysWithinWindowAcrossDaylightSavingChange() {
        ZonedDateTime now = ZonedDateTime.of(2026, 3, 10, 15, 0, 0, 0, java.time.ZoneId.of("America/Los_Angeles"));
        Config c = new Config(5000, Duration.ofDays(7), now);

        for (Event e : Generator.generate(c, new Random(5))) {
            assertThat(e.start().isBefore(now.minus(c.window())) || e.start().isAfter(now)).isFalse();
        }
    }
}
