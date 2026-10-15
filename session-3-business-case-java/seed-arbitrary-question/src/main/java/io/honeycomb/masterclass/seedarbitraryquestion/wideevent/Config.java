package io.honeycomb.masterclass.seedarbitraryquestion.wideevent;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * Describes one generated dataset.
 *
 * @param eventCount the number of checkout attempts to generate.
 * @param window how far back from {@code now} the dataset extends. The question
 *     asks about "the past week", so this defaults to 7 days.
 * @param now anchors the dataset; passed in rather than read from the clock so
 *     generation is deterministic under test. It is a {@link ZonedDateTime}
 *     (not an {@code Instant}) because "lunch hour" is read in this time zone.
 */
public record Config(int eventCount, Duration window, ZonedDateTime now) {

    /**
     * The defaults: 20,000 events over a week ending at {@code now}. Go's
     * {@code DefaultConfig()} leaves {@code Now} zero and the caller sets it;
     * Java records are immutable, so the caller passes it in here instead.
     */
    public static Config defaultConfig(ZonedDateTime now) {
        return new Config(20000, Duration.ofDays(7), now);
    }

    public Config withEventCount(int eventCount) {
        return new Config(eventCount, window, now);
    }

    public Config withWindow(Duration window) {
        return new Config(eventCount, window, now);
    }
}
