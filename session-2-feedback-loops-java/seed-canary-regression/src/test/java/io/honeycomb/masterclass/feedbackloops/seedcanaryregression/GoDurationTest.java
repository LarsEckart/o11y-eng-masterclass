package io.honeycomb.masterclass.feedbackloops.seedcanaryregression;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Go has no matching test: {@code time.ParseDuration} comes with the language.
 * Java needs {@link GoDuration}, so it gets its own check that "90m" and
 * "4h0m0s" mean the same thing as in the Go seeder.
 */
class GoDurationTest {

    @Test
    void parsesGoSyntax() {
        assertThat(GoDuration.parse("90m")).contains(Duration.ofMinutes(90));
        assertThat(GoDuration.parse("4h")).contains(Duration.ofHours(4));
        assertThat(GoDuration.parse("1h30m")).contains(Duration.ofMinutes(90));
        assertThat(GoDuration.parse("1.5s")).contains(Duration.ofMillis(1500));
        assertThat(GoDuration.parse("300ms")).contains(Duration.ofMillis(300));
        assertThat(GoDuration.parse("0")).contains(Duration.ZERO);
    }

    @Test
    void rejectsBadInput() {
        assertThat(GoDuration.parse(null)).isEmpty();
        assertThat(GoDuration.parse("")).isEmpty();
        assertThat(GoDuration.parse("90")).isEmpty();
        assertThat(GoDuration.parse("PT90M")).isEmpty();
        assertThat(GoDuration.parse("abc")).isEmpty();
    }

    @Test
    void formatsLikeGo() {
        assertThat(GoDuration.format(Duration.ofHours(4))).isEqualTo("4h0m0s");
        assertThat(GoDuration.format(Duration.ofMinutes(90))).isEqualTo("1h30m0s");
        assertThat(GoDuration.format(Duration.ofSeconds(90))).isEqualTo("1m30s");
        assertThat(GoDuration.format(Duration.ofMillis(1500))).isEqualTo("1.5s");
        assertThat(GoDuration.format(Duration.ofMillis(300))).isEqualTo("300ms");
        assertThat(GoDuration.format(Duration.ZERO)).isEqualTo("0s");
    }
}
