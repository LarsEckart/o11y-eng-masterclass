package io.honeycomb.masterclass.slisslos.seedsliservice;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** No Go counterpart: Go's own library provides {@code time.ParseDuration}. */
class GoDurationTest {

    @Test
    void parsesGoStyleDurations() {
        assertThat(GoDuration.parse("6h")).contains(Duration.ofHours(6));
        assertThat(GoDuration.parse("60m")).contains(Duration.ofMinutes(60));
        assertThat(GoDuration.parse("1h30m")).contains(Duration.ofMinutes(90));
        assertThat(GoDuration.parse("1.5h")).contains(Duration.ofMinutes(90));
        assertThat(GoDuration.parse("300ms")).contains(Duration.ofMillis(300));
        assertThat(GoDuration.parse("0")).contains(Duration.ZERO);
    }

    @Test
    void rejectsBadInput() {
        assertThat(GoDuration.parse("")).isEmpty();
        assertThat(GoDuration.parse("6")).isEmpty();
        assertThat(GoDuration.parse("PT6H")).isEmpty();
        assertThat(GoDuration.parse("h")).isEmpty();
    }

    @Test
    void formatsLikeGo() {
        assertThat(GoDuration.format(Duration.ofHours(6))).isEqualTo("6h0m0s");
        assertThat(GoDuration.format(Duration.ofMinutes(90))).isEqualTo("1h30m0s");
        assertThat(GoDuration.format(Duration.ofMinutes(45))).isEqualTo("45m0s");
        assertThat(GoDuration.format(Duration.ofSeconds(5))).isEqualTo("5s");
    }
}
