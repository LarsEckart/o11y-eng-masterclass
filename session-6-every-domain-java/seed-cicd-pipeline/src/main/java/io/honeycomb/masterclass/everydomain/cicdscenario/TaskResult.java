package io.honeycomb.masterclass.everydomain.cicdscenario;

/** The outcome of one task or test-case span. */
public enum TaskResult {
    SUCCESS("success"),
    FAILURE("failure");

    private final String value;

    TaskResult(String value) {
        this.value = value;
    }

    /** The string OTel's CI/CD conventions use for this outcome. */
    public String value() {
        return value;
    }
}
