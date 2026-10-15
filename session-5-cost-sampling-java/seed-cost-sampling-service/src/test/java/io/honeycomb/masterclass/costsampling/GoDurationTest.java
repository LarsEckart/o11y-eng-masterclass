package io.honeycomb.masterclass.costsampling;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The env vars SEED_DURATION and SEED_SETTLE_WAIT use Go's duration spelling.
 * This pins the forms the course and the delivery test actually use.
 */
class GoDurationTest {

    @Test
    void parsesGoForms() {
        assertThat(GoDuration.parse("90s")).contains(Duration.ofSeconds(90));
        assertThat(GoDuration.parse("10ms")).contains(Duration.ofMillis(10));
        assertThat(GoDuration.parse("1m30s")).contains(Duration.ofSeconds(90));
        assertThat(GoDuration.parse("1.5h")).contains(Duration.ofMinutes(90));
        assertThat(GoDuration.parse("0")).contains(Duration.ZERO);
        assertThat(GoDuration.parse("0s")).contains(Duration.ZERO);
    }

    @Test
    void rejectsWhatGoRejects() {
        assertThat(GoDuration.parse("")).isEmpty();
        assertThat(GoDuration.parse("10")).isEmpty();
        assertThat(GoDuration.parse("s")).isEmpty();
        assertThat(GoDuration.parse("PT10S")).isEmpty();
        assertThat(GoDuration.parse("5 s")).isEmpty();
    }

    @Test
    void formatsLikeGo() {
        assertThat(GoDuration.format(Duration.ZERO)).isEqualTo("0s");
        assertThat(GoDuration.format(Duration.ofMillis(10))).isEqualTo("10ms");
        assertThat(GoDuration.format(Duration.ofSeconds(2))).isEqualTo("2s");
        assertThat(GoDuration.format(Duration.ofMillis(1500))).isEqualTo("1.5s");
        assertThat(GoDuration.format(Duration.ofSeconds(15))).isEqualTo("15s");
        assertThat(GoDuration.format(Duration.ofSeconds(90))).isEqualTo("1m30s");
        assertThat(GoDuration.format(Duration.ofHours(1))).isEqualTo("1h0m0s");
    }
}
