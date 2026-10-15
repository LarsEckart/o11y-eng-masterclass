package io.honeycomb.masterclass.everydomain.perfscenario;

/** Determines how a template's duration is drawn. */
public enum QueryKind {
    /** Tight jitter around the baseline. */
    FAST,
    /** The baseline most of the time and the slow duration the rest. */
    BIMODAL,
    /**
     * The baseline plus an exponential tail, so most requests are fast but a
     * continuous minority run progressively slower.
     */
    LONG_TAIL
}
