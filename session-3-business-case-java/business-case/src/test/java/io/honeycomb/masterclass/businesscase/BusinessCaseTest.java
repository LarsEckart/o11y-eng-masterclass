package io.honeycomb.masterclass.businesscase;

import io.honeycomb.masterclass.businesscase.BusinessCase.Case;
import io.honeycomb.masterclass.businesscase.BusinessCase.Inputs;
import io.honeycomb.masterclass.businesscase.BusinessCase.LineItem;
import io.honeycomb.masterclass.businesscase.BusinessCase.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessCaseTest {

    /**
     * A mid-sized org with round numbers, so the expected values below can be
     * checked by hand rather than copied from a previous run.
     */
    private static Inputs sample() {
        return Inputs.builder()
                .engineers(100)
                .loadedCostPerEngineer(260000) // $125/h at 2080h
                .incidentsPerMonth(10) // 120/yr
                .meanTimeToResolveHours(4)
                .respondersPerIncident(3)
                .escalationRate(0.5)
                .escalationExtraResponders(2)
                .unplannedWorkShare(0.20)
                .revenuePerHourAtRisk(0)
                .observabilityAnnualSpend(400000)
                .build();
    }

    @Test
    void hourlyRate() {
        assertThat(sample().hourlyRate()).isEqualTo(125.0);
    }

    /**
     * Checks each line against a hand-computed figure. These numbers end up in
     * front of a CFO, so they get checked individually rather than only in
     * aggregate.
     */
    @Test
    void lineItemArithmetic() {
        Case c = BusinessCase.build(sample(), null);

        // label -> expected, with how it was worked out
        Map<String, Double> want = Map.of(
                "Incident response labour", 180000.0, // 120 incidents x 4h x 3 people x $125
                "Escalation drag", 60000.0, // 120 x 0.5 x 2 extra x 4h x $125
                "Unplanned work and rework", 5200000.0); // 100 engineers x $260k x 20%

        Map<String, Double> byLabel = new HashMap<>();
        for (LineItem it : c.currentState()) {
            byLabel.put(it.label(), it.annual());
        }

        want.forEach((label, expected) -> assertThat(byLabel).as(label).containsEntry(label, expected));

        assertThat(c.currentTotal()).isEqualTo(180000.0 + 60000 + 5200000);
    }

    /**
     * Guards the honesty of the default: a leader who cannot defend a
     * revenue-at-risk figure should not get one invented for them.
     */
    @Test
    void revenueLineIsOptional() {
        Case withoutRevenue = BusinessCase.build(sample(), null);
        assertThat(withoutRevenue.currentState())
                .extracting(LineItem::label)
                .doesNotContain("Revenue exposure during degradation");

        Inputs in = sample().toBuilder().revenuePerHourAtRisk(10000).build();
        Case withRevenue = BusinessCase.build(in, null);

        assertThat(withRevenue.currentState())
                .filteredOn(it -> it.label().equals("Revenue exposure during degradation"))
                .as("revenue line present when RevenuePerHourAtRisk is set")
                .singleElement()
                .satisfies(it -> assertThat(it.annual()).isEqualTo(120.0 * 4 * 10000));
    }

    /**
     * The important structural property. MTTR improvements must not be applied
     * to unplanned work, and vice versa; otherwise one optimistic assumption
     * inflates the whole total, which is the failure mode that makes these cases
     * easy to dismiss.
     */
    @Test
    void scenarioSavingsSeparateTheLevers() {
        Inputs in = sample();

        Case onlyMttr = BusinessCase.build(in, List.of(new Scenario("mttr only", 0.5, 0)));
        Case onlyUnplanned = BusinessCase.build(in, List.of(new Scenario("unplanned only", 0, 0.5)));
        Case both = BusinessCase.build(in, List.of(new Scenario("both", 0.5, 0.5)));

        // MTTR-sensitive lines total 240,000; half is 120,000.
        assertThat(onlyMttr.scenarios().get(0).annualSaving()).isEqualTo(120000.0);
        // Unplanned work is 5,200,000; half is 2,600,000.
        assertThat(onlyUnplanned.scenarios().get(0).annualSaving()).isEqualTo(2600000.0);
        // The two levers must simply add.
        assertThat(both.scenarios().get(0).annualSaving()).isEqualTo(2720000.0);
    }

    /**
     * Keeps the output honest: if the conservative case were as good as the
     * optimistic one, the range would be decoration.
     */
    @Test
    void defaultScenariosAreOrderedAndConservative() {
        Case c = BusinessCase.build(sample(), null);

        assertThat(c.scenarios()).hasSize(3);
        for (int i = 1; i < c.scenarios().size(); i++) {
            assertThat(c.scenarios().get(i).annualSaving())
                    .as("scenario %s should exceed %s; the range is not ordered",
                            c.scenarios().get(i).name(), c.scenarios().get(i - 1).name())
                    .isGreaterThan(c.scenarios().get(i - 1).annualSaving());
        }

        double spread = c.scenarios().get(2).annualSaving() / c.scenarios().get(0).annualSaving();
        assertThat(spread)
                .as("optimistic vs conservative; the range is too narrow to be an honest range")
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * Asserts the tool will report an unfavourable answer. A calculator that
     * cannot conclude "this does not pay for itself" is a marketing instrument,
     * not an analysis.
     */
    @Test
    void netOfCanBeNegative() {
        Inputs in = sample().toBuilder()
                .engineers(5)
                .incidentsPerMonth(1)
                .unplannedWorkShare(0.02)
                .observabilityAnnualSpend(500000)
                .build();

        Case c = BusinessCase.build(in, null);
        assertThat(c.netOf(c.scenarios().get(0)))
                .as("conservative net for a tiny org on a large contract")
                .isNegative();
    }

    /** Covers the first-run case where a leader has only some of the numbers to hand. */
    @Test
    void zeroInputsDoNotBlowUp() {
        Case c = BusinessCase.build(Inputs.builder().build(), null);

        assertThat(c.currentTotal()).isZero();
        for (Scenario s : c.scenarios()) {
            assertThat(s.annualSaving()).as(s.name()).isFinite();
        }
    }

    /**
     * Covers the boundaries where hand-rolled separator logic usually breaks:
     * the exact multiples of 1000, and negatives.
     */
    @ParameterizedTest
    @CsvSource({
        "0, 0",
        "7, 7",
        "999, 999",
        "1000, '1,000'",
        "1234, '1,234'",
        "999999, '999,999'",
        "1000000, '1,000,000'",
        "5440000, '5,440,000'",
        "-104000, '-104,000'",
        "-1000, '-1,000'",
        "-7, -7",
    })
    void formatMoney(double in, String want) {
        assertThat(BusinessCase.formatMoney(in)).isEqualTo(want);
    }

    /**
     * Java-only: the Go tool prints with {@code %.0f}, which rounds an exact
     * tie to the even digit. {@code String.format} would round it up instead.
     */
    @ParameterizedTest
    @CsvSource({"0.5, 0, 0", "1.5, 0, 2", "2.5, 0, 2", "12.5, 0, 12", "3.25, 1, 3.2", "3.5, 1, 3.5", "0.15, 2, 0.15"})
    void fixedRoundsTiesToEvenLikeGo(double in, int decimals, String want) {
        assertThat(BusinessCase.fixed(in, decimals)).isEqualTo(want);
    }
}
