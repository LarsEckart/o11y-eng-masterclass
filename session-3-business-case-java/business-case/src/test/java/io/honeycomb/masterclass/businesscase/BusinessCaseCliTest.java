package io.honeycomb.masterclass.businesscase;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Go version has no test for {@code cmd/business-case}. These cover what
 * Go's {@code flag} package gave it for free, because the Java port has to
 * parse the command line by hand.
 */
class BusinessCaseCliTest {

    private record Result(int status, String out, String err) {}

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = BusinessCaseCli.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void noFlagsPrintsPlaceholderWarningAndTheDefaultCase() {
        Result r = run();

        assertThat(r.status()).isZero();
        assertThat(r.out())
                .contains("THESE ARE PLACEHOLDER NUMBERS. Do not present this.")
                // 120 x 4h x 3 x $125 = 180,000; the placeholder inputs from placeholders().
                .contains("Incident response labour               $180,000 / yr")
                .contains("120 incidents/yr x 4.0h x 3.0 responders x $125/h")
                .contains("TOTAL                                  $5,440,000 / yr")
                .contains("Current observability spend: $400,000 / yr")
                .contains("No revenue-at-risk figure was supplied");
    }

    @Test
    void ownNumbersSuppressTheWarningAndAcceptBothFlagStyles() {
        Result r = run("-engineers", "120", "--loaded-cost=260000", "-incidents-per-month", "14",
                "-mttr-hours=3.5", "-responders", "3", "-escalation-rate", "0.45",
                "-escalation-extra", "2", "-unplanned-share", "0.22", "-observability-spend", "350000");

        assertThat(r.status()).isZero();
        assertThat(r.out()).doesNotContain("PLACEHOLDER");
        assertThat(r.out()).contains("120 engineers x $260,000 loaded x 22% of time");
    }

    @Test
    void sourcesFlagPrintsTheChapterFigures() {
        Result r = run("-sources");

        assertThat(r.status()).isZero();
        assertThat(r.out()).contains("PUBLIC FIGURES CITED IN CHAPTER 26").contains("Amazon").contains("Walmart").contains("Staples");
        assertThat(r.out()).doesNotContain("COST OF THE CURRENT STATE");
    }

    @Test
    void unknownFlagFailsWithUsageAndStatusTwo() {
        Result r = run("-nope");

        assertThat(r.status()).isEqualTo(2);
        assertThat(r.err()).contains("flag provided but not defined: -nope").contains("Usage of business-case:");
    }

    @Test
    void badNumberFailsWithStatusTwo() {
        Result r = run("-engineers", "lots");

        assertThat(r.status()).isEqualTo(2);
        assertThat(r.err()).contains("invalid value \"lots\" for flag -engineers");
    }

    @Test
    void helpPrintsUsageAndSucceeds() {
        Result r = run("-h");

        assertThat(r.status()).isZero();
        assertThat(r.err()).contains("  -mttr-hours float");
    }
}
