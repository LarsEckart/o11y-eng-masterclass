package io.honeycomb.masterclass.everydomain.cicdscenario;

/** One test-case span within a run's test task. */
public record TestCase(String name, TaskResult result) {
}
