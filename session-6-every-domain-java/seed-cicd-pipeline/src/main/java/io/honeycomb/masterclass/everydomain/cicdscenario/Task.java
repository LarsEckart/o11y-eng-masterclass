package io.honeycomb.masterclass.everydomain.cicdscenario;

import java.time.Duration;
import java.util.List;

/**
 * One task span within a run.
 *
 * @param tests non-empty only for the "test" task
 */
public record Task(String name, Duration duration, TaskResult result, List<TestCase> tests) {

    /** A task with no test cases (checkout, build, deploy). */
    public Task(String name, Duration duration, TaskResult result) {
        this(name, duration, result, List.of());
    }
}
