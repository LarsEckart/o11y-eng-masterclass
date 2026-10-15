package io.honeycomb.masterclass.everydomain.cicdscenario;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class ScenarioTest {

    private static Config testConfig() {
        return Config.defaults(Instant.parse("2026-09-16T12:00:00Z"));
    }

    private static List<Run> generate(Config cfg) {
        return Scenario.generate(cfg, new Random(7));
    }

    private static Task buildTask(Run r) {
        return r.tasks().stream()
                .filter(t -> t.name().equals("build"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("run has no build task"));
    }

    /**
     * Guards the class comment's central claim: build duration is flat at
     * baseline, then flat at a higher plateau, with nothing in between. A
     * gradual drift wouldn't demonstrate why a trailing-window trigger (rather
     * than a slow-moving average) is the right tool.
     */
    @Test
    void regressionIsAStepChange() {
        Config cfg = testConfig();
        List<Run> runs = generate(cfg);

        final double jitterMargin = 0.20; // matches jitter's 0.15 spread plus slack
        Duration baselineHigh = Duration.ofNanos((long) (cfg.baselineBuildDuration().toNanos() * (1 + jitterMargin)));
        Duration regressedLow = Duration.ofNanos((long) (cfg.regressedBuildDuration().toNanos() * (1 - jitterMargin)));

        assertThat(baselineHigh)
                .as("baseline jitter ceiling must be below the regressed jitter floor, or the step change would not be visible")
                .isLessThan(regressedLow);

        for (Run r : runs) {
            Duration d = buildTask(r).duration();
            if (r.regressed()) {
                assertThat(d)
                        .as("regressed run %s build duration is below the regressed floor", r.runId())
                        .isGreaterThanOrEqualTo(regressedLow);
            } else {
                assertThat(d)
                        .as("baseline run %s build duration is above the baseline ceiling", r.runId())
                        .isLessThanOrEqualTo(baselineHigh);
            }
        }
    }

    /**
     * Guards that enough runs fall after the regression start for a
     * trailing-window P95 to be computed from, not just a handful of points.
     */
    @Test
    void regressionPopulationIsDemoSized() {
        long regressed = generate(testConfig()).stream().filter(Run::regressed).count();
        assertThat(regressed)
                .as("regressed runs; too few for a trailing-window P95 to be meaningful")
                .isGreaterThanOrEqualTo(10);
    }

    /**
     * Checks the flaky test's observed failure rate independent of the build
     * regression: the class comment's claim that these are two separate
     * stories, not one.
     */
    @Test
    void flakyTestFailureRateMatchesConfig() {
        Config cfg = testConfig();
        List<Run> runs = generate(cfg);

        long failed = runs.stream()
                .flatMap(r -> r.tasks().stream())
                .filter(task -> task.name().equals("test"))
                .flatMap(task -> task.tests().stream())
                .filter(tc -> tc.name().equals(Scenario.FLAKY_TEST_NAME) && tc.result() == TaskResult.FAILURE)
                .count();

        double got = (double) failed / runs.size();
        assertThat(got)
                .as("flaky test failure rate, want close to configured %.3f", cfg.flakyTestFailureRate())
                .isBetween(cfg.flakyTestFailureRate() - 0.05, cfg.flakyTestFailureRate() + 0.05);
    }

    /**
     * Guards the class comment's other claim: every non-flaky test case always
     * passes, so a flakiness query against this dataset has exactly one real
     * answer.
     */
    @Test
    void onlyTheFlakyTestEverFails() {
        for (Run r : generate(testConfig())) {
            for (Task task : r.tasks()) {
                if (!task.name().equals("test")) {
                    continue;
                }
                for (TestCase tc : task.tests()) {
                    if (!tc.name().equals(Scenario.FLAKY_TEST_NAME) && tc.result() == TaskResult.FAILURE) {
                        fail("test case \"%s\" failed in run %s; only %s should ever fail",
                                tc.name(), r.runId(), Scenario.FLAKY_TEST_NAME);
                    }
                }
            }
        }
    }

    /**
     * Checks the roll-up: a run's overall result is failure exactly when its
     * test task's result is failure, which is what makes
     * {@code cicd.pipeline.result} a meaningful filter.
     */
    @Test
    void runResultReflectsTestFailure() {
        for (Run r : generate(testConfig())) {
            TaskResult testResult = null;
            for (Task task : r.tasks()) {
                if (task.name().equals("test")) {
                    testResult = task.result();
                }
            }
            assertThat(r.result())
                    .as("run %s: overall result, want to match test task result", r.runId())
                    .isEqualTo(testResult);
        }
    }

    /**
     * Guards the delivery test's precondition: 1 root + 4 tasks + testCount
     * test-case spans per run must clear the SDK's default 2048-span queue with
     * margin at the default run count.
     */
    @Test
    void runCountExceedsQueueMargin() {
        Config cfg = Config.defaults(Instant.now());
        final int defaultQueueSize = 2048;

        int spansPerRun = 1 + 4 + cfg.testCount();
        int total = cfg.runCount() * spansPerRun;
        assertThat(total)
                .as("default config emits %d spans, not comfortably past the %d-span default queue; raise runCount",
                        total, defaultQueueSize)
                .isGreaterThan(defaultQueueSize * 2);
    }

    /**
     * Checks the trunk-versus-PR split lands near {@code mainBranchShare} and
     * that both cohorts are populated. The breakdown query in honeycomb-setup
     * needs two rows to make Chapter 18's blast-radius point; one empty cohort
     * would quietly reduce it to a single number.
     */
    @Test
    void branchSplitMatchesConfig() {
        Config cfg = testConfig();
        List<Run> runs = generate(cfg);

        long onTrunk = runs.stream().filter(r -> r.branch().equals(Scenario.MAIN_BRANCH_NAME)).count();
        long prRuns = runs.size() - onTrunk;

        assertThat(onTrunk).as("runs on trunk; the breakdown demo needs both cohorts").isNotZero();
        assertThat(prRuns).as("runs on PR branches; the breakdown demo needs both cohorts").isNotZero();

        double got = (double) onTrunk / runs.size();
        final double tolerance = 0.05;
        assertThat(got)
                .as("trunk share, want %.3f within %.2f", cfg.mainBranchShare(), tolerance)
                .isBetween(cfg.mainBranchShare() - tolerance, cfg.mainBranchShare() + tolerance);
    }

    /**
     * Asserts {@code vcs.ref.head.name} and {@code vcs.change.id} stay
     * consistent: a PR run's branch is derived from its own PR number, and a
     * trunk run still carries the PR number that landed, so the two attributes
     * never contradict each other within a trace.
     */
    @Test
    void branchNameTracksPrNumber() {
        for (Run r : generate(testConfig())) {
            assertThat(r.prNumber()).as("run %s: PR number; every run traces back to one", r.runId()).isNotZero();
            if (r.branch().equals(Scenario.MAIN_BRANCH_NAME)) {
                continue;
            }
            assertThat(r.branch()).as("run %s: branch", r.runId()).isEqualTo("pr-" + r.prNumber());
        }
    }

    /**
     * Guards the reason the split exists at all. The flaky test is
     * branch-independent, so it has to fail on trunk as well as on PR branches:
     * that is what makes "same test, different blast radius" a claim about
     * ownership rather than about one unlucky branch.
     */
    @Test
    void flakyTestFailsOnBothBranches() {
        // Keyed by "is this run on trunk".
        Map<Boolean, Integer> failures = new HashMap<>();
        for (Run r : generate(testConfig())) {
            for (Task task : r.tasks()) {
                for (TestCase tc : task.tests()) {
                    if (tc.name().equals(Scenario.FLAKY_TEST_NAME) && tc.result() == TaskResult.FAILURE) {
                        failures.merge(r.branch().equals(Scenario.MAIN_BRANCH_NAME), 1, Integer::sum);
                    }
                }
            }
        }

        assertThat(failures.getOrDefault(true, 0))
                .as("flaky-test failures on trunk; must be non-zero (PR branches: %d)", failures.getOrDefault(false, 0))
                .isNotZero();
        assertThat(failures.getOrDefault(false, 0))
                .as("flaky-test failures on PR branches; must be non-zero")
                .isNotZero();
    }
}
