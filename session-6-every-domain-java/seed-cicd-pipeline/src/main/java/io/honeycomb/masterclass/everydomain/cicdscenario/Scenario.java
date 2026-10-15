package io.honeycomb.masterclass.everydomain.cicdscenario;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates the Masterclass 6 CI/CD pipeline dataset. (Go:
 * {@code internal/cicdscenario}.) Plain Java with no OpenTelemetry in it: it
 * decides what the runs look like, and {@code SeedCicdPipeline} turns them into
 * spans.
 *
 * <h2>The scenario</h2>
 *
 * <p>One pipeline, masterclass-app-ci, run many times over a two-week window.
 * Each run is a trace: a root pipeline span, four child task spans (checkout,
 * build, test, deploy, in that order), and the test task fans out one child span
 * per test case. Chapter 18 stops at the job and step level and tracks
 * flakiness as a percentage of successful retries; per-test-case spans are this
 * session's extension of that ontology, which is what makes flakiness queryable
 * by test name. Attributes follow OTel's own CI/CD semantic conventions
 * ({@code cicd.pipeline.name}, {@code cicd.pipeline.task.name},
 * {@code cicd.pipeline.task.run.result}, {@code vcs.change.id} for the PR
 * number, {@code vcs.ref.head.name} for the branch) rather than inventing names,
 * per Chapter 6's own instrumentation checklist. Test-case spans divide the test
 * task's own duration evenly across however many test cases ran; their per-span
 * duration is a placeholder to make the span tile its parent, not an independent
 * signal. Only {@link #FLAKY_TEST_NAME}'s pass/fail result carries a real story,
 * so don't query test-case durations for outliers.</p>
 *
 * <h2>The regression</h2>
 *
 * <p>The build task's duration is flat at a baseline (about 12 minutes, with
 * jitter) for the whole window, except for runs after
 * {@link Config#regressionAgo()}, which jump to a regressed duration (about 22
 * minutes): the curriculum's "a build-time regression from 12 to 22 minutes
 * caught on day one." That step change, not a gradual drift, is what a
 * P95-duration trigger on a trailing window catches quickly; see
 * {@code ScenarioTest.regressionIsAStepChange}.</p>
 *
 * <h2>The branch split</h2>
 *
 * <p>{@link Config#mainBranchShare()} of runs are on main; the rest are on
 * per-PR branches. That split exists because Chapter 18 calls the repository and
 * branch the most important telemetry a pipeline emits: one inappropriately slow
 * or flaky test on a PR branch costs its author, and the same test on main costs
 * everyone. The flaky test fails at the same rate on both, so breaking the
 * flakiness query down by {@code vcs.ref.head.name} is what turns one number
 * into a blast-radius argument.</p>
 *
 * <h2>The flaky test</h2>
 *
 * <p>Exactly one test case ({@link #FLAKY_TEST_NAME}) fails at
 * {@link Config#flakyTestFailureRate()} on every run, independent of the build
 * regression: a separate story from the duration regression, on purpose. Chapter
 * 18 treats flakiness and duration as two different things build observability
 * answers, and conflating them into one seeded failure would only teach one
 * lesson instead of two. Every other test case always passes.</p>
 *
 * <h2>Delivery test sizing</h2>
 *
 * <p>Spans per run: 1 (pipeline root) + 4 (tasks) + testCount (one per test
 * case). At the defaults (20 tests), that is 25 spans per run, so
 * {@code runCount * 25} must clear the SDK's default 2048-span queue with
 * margin; see the seeder's {@code MAX_QUEUE_SIZE} and
 * {@code ScenarioTest.runCountExceedsQueueMargin}.</p>
 */
public final class Scenario {

    /**
     * The one test case that fails intermittently. Named so the seeder and any
     * query built against this dataset can refer to it by a literal rather than
     * an index.
     */
    public static final String FLAKY_TEST_NAME = "TestPaymentRetryIsIdempotent";

    /**
     * The trunk branch, where a flaky test costs every developer rather than
     * only its author.
     */
    public static final String MAIN_BRANCH_NAME = "main";

    private Scenario() {
    }

    /**
     * Produces the dataset, in emission order.
     *
     * <p>The order of the {@code rng} calls follows the Go code, so the shape
     * of the data matches. The exact numbers do not: Go's {@code math/rand} and
     * Java's {@link Random} are different generators, so the same seed gives
     * different runs.</p>
     */
    public static List<Run> generate(Config cfg, Random rng) {
        Instant regressionStart = cfg.regressionStart();
        var out = new ArrayList<Run>(cfg.runCount());

        for (int i = 0; i < cfg.runCount(); i++) {
            // Go: Int63n(window). Random.nextLong(bound) is the same idea:
            // uniform in [0, bound).
            Instant start = cfg.now().minusNanos(rng.nextLong(cfg.window().toNanos()));
            boolean regressed = start.isAfter(regressionStart);

            Duration buildDuration = jitter(rng, cfg.baselineBuildDuration(), 0.15);
            if (regressed) {
                buildDuration = jitter(rng, cfg.regressedBuildDuration(), 0.15);
            }

            var tests = new ArrayList<TestCase>(cfg.testCount());
            boolean testTaskFailed = false;
            for (int t = 0; t < cfg.testCount(); t++) {
                String name = String.format("TestCase%02d", t);
                TaskResult result = TaskResult.SUCCESS;
                if (t == 0) {
                    name = FLAKY_TEST_NAME;
                    if (rng.nextDouble() < cfg.flakyTestFailureRate()) {
                        result = TaskResult.FAILURE;
                        testTaskFailed = true;
                    }
                }
                tests.add(new TestCase(name, result));
            }
            TaskResult testResult = testTaskFailed ? TaskResult.FAILURE : TaskResult.SUCCESS;

            // Java evaluates arguments left to right, like Go, so the jitter
            // draws happen in the same order as in the Go slice literal.
            List<Task> tasks = List.of(
                    new Task("checkout", jitter(rng, Duration.ofSeconds(8), 0.2), TaskResult.SUCCESS),
                    new Task("build", buildDuration, TaskResult.SUCCESS),
                    new Task("test", jitter(rng, Duration.ofMinutes(2), 0.2), testResult, tests),
                    new Task("deploy", jitter(rng, Duration.ofSeconds(30), 0.2), TaskResult.SUCCESS));

            TaskResult runResult = testTaskFailed ? TaskResult.FAILURE : TaskResult.SUCCESS;

            int prNumber = 1000 + rng.nextInt(4000);
            String branch = "pr-" + prNumber;
            if (rng.nextDouble() < cfg.mainBranchShare()) {
                branch = MAIN_BRANCH_NAME;
            }

            out.add(new Run(start, String.format("run-%06d", i), prNumber, branch, regressed, tasks, runResult));
        }

        return out;
    }

    /** {@code base} scaled by a random factor within plus or minus {@code spread}. */
    private static Duration jitter(Random rng, Duration base, double spread) {
        double factor = 1 + spread * (2 * rng.nextDouble() - 1);
        return Duration.ofNanos((long) (base.toNanos() * factor));
    }
}
