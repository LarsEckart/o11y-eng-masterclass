package io.honeycomb.masterclass.everydomain.llmscenario;

/**
 * A closed range of scores. Go uses a {@code [2]float64} array; a record gives
 * the two numbers names.
 */
public record Range(double low, double high) {

    @Override
    public String toString() {
        return "[" + low + " " + high + "]";
    }
}
