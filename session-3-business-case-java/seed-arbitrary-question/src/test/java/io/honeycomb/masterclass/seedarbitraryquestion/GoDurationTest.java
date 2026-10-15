package io.honeycomb.masterclass.seedarbitraryquestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Java-only: {@link GoDuration} replaces Go's {@code time.ParseDuration}/{@code String}. */
class GoDurationTest {

    @ParameterizedTest
    @CsvSource({
        "168h, 604800000000000",
        "4h, 14400000000000",
        "1h30m, 5400000000000",
        "90s, 90000000000",
        "1.5s, 1500000000",
        "250ms, 250000000",
        "-90m, -5400000000000",
        "0, 0",
    })
    void parsesGoSyntax(String text, long nanos) {
        assertThat(GoDuration.parse(text)).isEqualTo(Duration.ofNanos(nanos));
    }

    @Test
    void rejectsWhatGoRejects() {
        for (String bad : new String[] {"", "h", "4", "1x", "1h 30m", "PT4H", "."}) {
            assertThatThrownBy(() -> GoDuration.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "604800000000000, 168h0m0s",
        "14400000000000, 4h0m0s",
        "5400000000000, 1h30m0s",
        "90000000000, 1m30s",
        "1500000000, 1.5s",
        "5000000000, 5s",
        "250000000, 250ms",
        "1500000, 1.5ms",
        "10, 10ns",
        "0, 0s",
    })
    void formatsLikeGo(long nanos, String want) {
        assertThat(GoDuration.format(Duration.ofNanos(nanos))).isEqualTo(want);
    }
}
