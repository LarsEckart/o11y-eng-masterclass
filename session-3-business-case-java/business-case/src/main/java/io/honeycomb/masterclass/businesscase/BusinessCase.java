package io.honeycomb.masterclass.businesscase;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns an organisation's own numbers into the one-page argument Masterclass 3's
 * lab asks for. This is the Java port of Go's {@code internal/businesscase}.
 *
 * <h2>Why this is not an ROI calculator</h2>
 *
 * <p>Chapter 26 gives cited public figures for the link between latency and
 * revenue, and then says plainly that "turning this into monetary estimates is
 * harder to give guidance on." That restraint is deliberate and worth keeping:
 * a tool that emits one confident number invites a CFO to attack the number
 * instead of engaging with the argument, and the number will not survive
 * scrutiny because most of its inputs are assumptions.
 *
 * <p>So this class does three things instead:
 *
 * <ul>
 *   <li>It computes the cost of the current state from numbers a leader can
 *       actually look up: incident count, time to resolve, who gets pulled in,
 *       and how much engineering time goes to unplanned work.
 *   <li>It shows the arithmetic for every line, so each figure can be checked
 *       or disputed on its own.
 *   <li>It reports a range across three explicitly-labelled improvement
 *       assumptions rather than a point estimate.
 * </ul>
 *
 * <p>The output is an argument with your numbers in it, not a forecast.
 *
 * <p>Nothing here uses OpenTelemetry. The five-tests worksheet is the
 * observability part of the session; this class only does arithmetic.
 */
public final class BusinessCase {

    /** Label of the incident-response line. Shared with the MTTR grouping below. */
    static final String INCIDENT_RESPONSE = "Incident response labour";
    static final String ESCALATION_DRAG = "Escalation drag";
    static final String UNPLANNED_WORK = "Unplanned work and rework";
    static final String REVENUE_EXPOSURE = "Revenue exposure during degradation";

    /**
     * Converts an annual loaded salary into an hourly rate. 2,080 is 40 hours
     * across 52 weeks; it deliberately ignores leave, which makes the resulting
     * hourly rate slightly conservative, a defensible direction to err when the
     * audience is finance.
     */
    public static final int WORKING_HOURS_PER_YEAR = 2080;

    private BusinessCase() {}

    /**
     * Renders a whole-dollar amount with thousands separators. Written out
     * rather than taken from a dependency because this repo is meant to be read
     * by attendees, and it is shared between the formulas below and the CLI so
     * the two cannot drift.
     */
    public static String formatMoney(double v) {
        boolean neg = v < 0;
        if (neg) {
            v = -v;
        }

        String digits = fixed(v, 0);
        StringBuilder b = new StringBuilder();
        if (neg) {
            b.append('-');
        }
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                b.append(',');
            }
            b.append(digits.charAt(i));
        }
        return b.toString();
    }

    /**
     * Formats a number with a fixed count of decimals, like Go's {@code %.0f}
     * and {@code %.1f}.
     *
     * <p>Why not {@code String.format("%.1f", v)}? Java rounds exact ties up
     * (12.5 becomes "13") while Go rounds them to even (12.5 becomes "12"). The
     * output has to match the Go tool digit for digit, so we round the exact
     * binary value half-to-even ourselves. It also keeps the result independent
     * of the machine's locale (no decimal commas).
     */
    public static String fixed(double v, int decimals) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "+Inf" : "-Inf";
        }
        return new BigDecimal(v).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString();
    }

    /**
     * The numbers a leader supplies. Every one of them is observable from an
     * incident tracker, a payroll system, or a deploy pipeline; none of them
     * requires a survey or a consultant.
     *
     * @param engineers engineers in the organisation whose work this covers.
     * @param loadedCostPerEngineer annual fully-loaded cost: salary, benefits,
     *     taxes, equipment. Finance will have this number and it is usually well
     *     above base salary.
     * @param incidentsPerMonth incidents that pull someone away from planned
     *     work, not only customer-visible outages.
     * @param meanTimeToResolveHours wall-clock from "someone noticed" to
     *     "resolved", which is the interval engineers actually spend.
     * @param respondersPerIncident how many people are engaged on average. This
     *     is the number most often underestimated: incident channels pull in
     *     observers who stop doing their own work.
     * @param escalationRate share of incidents that cannot be resolved by
     *     whoever picked them up and must be escalated. This is the
     *     Two-or-Three People Test expressed as a cost.
     * @param escalationExtraResponders how many additional people an escalation
     *     pulls in, on top of {@code respondersPerIncident}.
     * @param unplannedWorkShare fraction of total engineering time spent on
     *     unplanned work and rework rather than on roadmap. Chapter 27's
     *     "firefighting trap" in numeric form.
     * @param revenuePerHourAtRisk revenue exposed per hour of degradation. Leave
     *     at zero if you cannot defend a figure; the case then rests on
     *     engineering cost alone, which is often the stronger ground anyway.
     * @param observabilityAnnualSpend current tooling spend, for comparison.
     *     Zero is fine.
     */
    public record Inputs(
            int engineers,
            double loadedCostPerEngineer,
            double incidentsPerMonth,
            double meanTimeToResolveHours,
            double respondersPerIncident,
            double escalationRate,
            double escalationExtraResponders,
            double unplannedWorkShare,
            double revenuePerHourAtRisk,
            double observabilityAnnualSpend) {

        /** The fully-loaded cost of one engineer-hour. */
        public double hourlyRate() {
            if (loadedCostPerEngineer == 0) {
                return 0;
            }
            return loadedCostPerEngineer / WORKING_HOURS_PER_YEAR;
        }

        /** The annualised incident count. */
        public double incidentsPerYear() {
            return incidentsPerMonth * 12;
        }

        /** An all-zero builder. Go gets this for free from struct zero values. */
        public static Builder builder() {
            return new Builder();
        }

        /** A builder pre-filled with these values, for "same inputs but...". */
        public Builder toBuilder() {
            return new Builder()
                    .engineers(engineers)
                    .loadedCostPerEngineer(loadedCostPerEngineer)
                    .incidentsPerMonth(incidentsPerMonth)
                    .meanTimeToResolveHours(meanTimeToResolveHours)
                    .respondersPerIncident(respondersPerIncident)
                    .escalationRate(escalationRate)
                    .escalationExtraResponders(escalationExtraResponders)
                    .unplannedWorkShare(unplannedWorkShare)
                    .revenuePerHourAtRisk(revenuePerHourAtRisk)
                    .observabilityAnnualSpend(observabilityAnnualSpend);
        }

        /**
         * Go's {@code Inputs} is a mutable struct that the CLI fills in one flag
         * at a time. Records are immutable, so the CLI fills in this builder
         * instead and calls {@link #build()} once the flags are parsed.
         */
        public static final class Builder {
            private int engineers;
            private double loadedCostPerEngineer;
            private double incidentsPerMonth;
            private double meanTimeToResolveHours;
            private double respondersPerIncident;
            private double escalationRate;
            private double escalationExtraResponders;
            private double unplannedWorkShare;
            private double revenuePerHourAtRisk;
            private double observabilityAnnualSpend;

            public Builder engineers(int v) { engineers = v; return this; }
            public Builder loadedCostPerEngineer(double v) { loadedCostPerEngineer = v; return this; }
            public Builder incidentsPerMonth(double v) { incidentsPerMonth = v; return this; }
            public Builder meanTimeToResolveHours(double v) { meanTimeToResolveHours = v; return this; }
            public Builder respondersPerIncident(double v) { respondersPerIncident = v; return this; }
            public Builder escalationRate(double v) { escalationRate = v; return this; }
            public Builder escalationExtraResponders(double v) { escalationExtraResponders = v; return this; }
            public Builder unplannedWorkShare(double v) { unplannedWorkShare = v; return this; }
            public Builder revenuePerHourAtRisk(double v) { revenuePerHourAtRisk = v; return this; }
            public Builder observabilityAnnualSpend(double v) { observabilityAnnualSpend = v; return this; }

            public Inputs build() {
                return new Inputs(
                        engineers,
                        loadedCostPerEngineer,
                        incidentsPerMonth,
                        meanTimeToResolveHours,
                        respondersPerIncident,
                        escalationRate,
                        escalationExtraResponders,
                        unplannedWorkShare,
                        revenuePerHourAtRisk,
                        observabilityAnnualSpend);
            }
        }
    }

    /**
     * One component of the current-state cost, carrying the formula that
     * produced it so the figure can be audited rather than trusted.
     */
    public record LineItem(String label, String formula, double annual) {}

    /**
     * One improvement assumption and what it would be worth. The reductions are
     * inputs, not findings; {@code name} exists so the output can say so.
     *
     * @param mttrReduction fraction between 0 and 1.
     * @param unplannedWorkReduction fraction between 0 and 1.
     * @param annualSaving what those reductions are worth against the current
     *     state.
     */
    public record Scenario(String name, double mttrReduction, double unplannedWorkReduction, double annualSaving) {

        /** Convenience for the input side, where the saving is not known yet. */
        public Scenario(String name, double mttrReduction, double unplannedWorkReduction) {
            this(name, mttrReduction, unplannedWorkReduction, 0);
        }
    }

    /** The assembled argument. */
    public record Case(Inputs inputs, List<LineItem> currentState, double currentTotal, List<Scenario> scenarios) {

        /**
         * Returns a scenario's saving less current observability spend.
         * Negative means the tooling costs more than this scenario recovers,
         * which is a real possible answer and one the tool should be willing to
         * print.
         */
        public double netOf(Scenario s) {
            return s.annualSaving() - inputs.observabilityAnnualSpend();
        }
    }

    /**
     * The three improvement assumptions the output reports.
     *
     * <p>These are ranges to reason about, not measurements. They are
     * deliberately wide and the conservative case is deliberately
     * unimpressive, because a business case that only works at its optimistic
     * end is not a business case.
     */
    public static List<Scenario> defaultScenarios() {
        return List.of(
                new Scenario("Conservative", 0.15, 0.05),
                new Scenario("Moderate", 0.35, 0.15),
                new Scenario("Optimistic", 0.60, 0.25));
    }

    /** Assembles the case using {@link #defaultScenarios()}. */
    public static Case build(Inputs in) {
        return build(in, null);
    }

    /** Assembles the case. {@code scenarios} may be null to use the defaults. */
    public static Case build(Inputs in, List<Scenario> scenarios) {
        if (scenarios == null) {
            scenarios = defaultScenarios();
        }

        double hourly = in.hourlyRate();
        double incidents = in.incidentsPerYear();

        double responderHours = incidents * in.meanTimeToResolveHours() * in.respondersPerIncident();
        double escalationHours = incidents * in.escalationRate() * in.escalationExtraResponders()
                * in.meanTimeToResolveHours();

        List<LineItem> items = new ArrayList<>();
        items.add(new LineItem(
                INCIDENT_RESPONSE,
                "%s incidents/yr x %sh x %s responders x $%s/h".formatted(
                        fixed(incidents, 0),
                        fixed(in.meanTimeToResolveHours(), 1),
                        fixed(in.respondersPerIncident(), 1),
                        formatMoney(hourly)),
                responderHours * hourly));
        items.add(new LineItem(
                ESCALATION_DRAG,
                "%s incidents/yr x %s%% escalated x %s extra people x %sh x $%s/h".formatted(
                        fixed(incidents, 0),
                        fixed(in.escalationRate() * 100, 0),
                        fixed(in.escalationExtraResponders(), 1),
                        fixed(in.meanTimeToResolveHours(), 1),
                        formatMoney(hourly)),
                escalationHours * hourly));
        items.add(new LineItem(
                UNPLANNED_WORK,
                "%s engineers x $%s loaded x %s%% of time".formatted(
                        in.engineers(),
                        formatMoney(in.loadedCostPerEngineer()),
                        fixed(in.unplannedWorkShare() * 100, 0)),
                (double) in.engineers() * in.loadedCostPerEngineer() * in.unplannedWorkShare()));

        if (in.revenuePerHourAtRisk() > 0) {
            items.add(new LineItem(
                    REVENUE_EXPOSURE,
                    "%s incidents/yr x %sh x $%s/h at risk".formatted(
                            fixed(incidents, 0),
                            fixed(in.meanTimeToResolveHours(), 1),
                            formatMoney(in.revenuePerHourAtRisk())),
                    incidents * in.meanTimeToResolveHours() * in.revenuePerHourAtRisk()));
        }

        double total = 0;
        for (LineItem it : items) {
            total += it.annual();
        }

        // Only the time-to-resolve-sensitive lines shrink with MTTR; unplanned
        // work is its own lever. Keeping them separate stops a single
        // optimistic assumption from moving the entire total.
        double mttrSensitive = 0;
        for (LineItem it : items) {
            switch (it.label()) {
                case INCIDENT_RESPONSE, ESCALATION_DRAG, REVENUE_EXPOSURE -> mttrSensitive += it.annual();
                default -> {
                    // Not MTTR-sensitive: handled as the separate "unplanned" lever below.
                }
            }
        }
        double unplanned = (double) in.engineers() * in.loadedCostPerEngineer() * in.unplannedWorkShare();

        List<Scenario> out = new ArrayList<>(scenarios.size());
        for (Scenario s : scenarios) {
            double saving = mttrSensitive * s.mttrReduction() + unplanned * s.unplannedWorkReduction();
            out.add(new Scenario(s.name(), s.mttrReduction(), s.unplannedWorkReduction(), saving));
        }

        return new Case(in, List.copyOf(items), total, List.copyOf(out));
    }
}
