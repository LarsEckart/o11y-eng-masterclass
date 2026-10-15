package io.honeycomb.masterclass.everydomain.seedcicdpipeline;

import io.honeycomb.masterclass.everydomain.testsupport.FakeCollector;
import io.honeycomb.masterclass.everydomain.testsupport.ReceivedSpan;
import io.honeycomb.masterclass.everydomain.testsupport.SeederProcess;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An end-to-end test of the shipped command rather than of a function inside
 * it, for the same reason as every other seeder in this repo: the
 * BatchSpanProcessor silently drops spans that overflow its queue, and only a
 * real run against a real OTLP receiver can catch that.
 *
 * <p>The seeder runs in its own JVM against a real gRPC receiver; see
 * {@link SeederProcess} and {@link FakeCollector}.</p>
 */
class DeliveryTest {

    /**
     * Runs; times {@link #SPANS_PER_RUN} must exceed the queue plus one export
     * batch, or this test cannot observe a dropped-span regression at all.
     * {@link #seedVolumeExceedsQueue()} enforces that rather than leaving it to
     * a comment.
     */
    private static final int SEED_COUNT = 400;

    /**
     * Matches {@code Config.defaults()} test count (20): 1 root + 4 tasks + 20
     * test cases.
     */
    private static final int SPANS_PER_RUN = 1 + 4 + 20;

    /**
     * How long the fake Collector takes to answer each export. The SDK's queue
     * only overflows when the seeder produces spans faster than the receiver
     * drains them. A receiver on localhost already loses that race, because
     * generating spans is much faster than a network round trip (with the
     * chunked flush removed, it receives exactly queue + one batch and nothing
     * more). The delay makes that independent of how fast the machine is, and
     * costs the passing runs about a quarter of a second.
     */
    private static final Duration RECEIVER_DELAY = Duration.ofMillis(20);

    private static List<ReceivedSpan> runSeeder() throws Exception {
        try (var collector = FakeCollector.start(RECEIVER_DELAY)) {
            return SeederProcess.run(SeedCicdPipeline.class, collector, SEED_COUNT);
        }
    }

    /**
     * Keeps the delivery test honest. At a volume below the queue depth, an
     * unchunked flush delivers everything anyway and the delivery test passes
     * on broken code.
     */
    @Test
    void seedVolumeExceedsQueue() {
        int spans = SEED_COUNT * SPANS_PER_RUN;
        int mostTheSdkCanHold = SeedCicdPipeline.MAX_QUEUE_SIZE + SeedCicdPipeline.MAX_EXPORT_BATCH_SIZE;
        assertThat(spans)
                .as("test seeds %d runs (%d spans) against a %d-span queue plus a %d-span batch; nothing would be "
                                + "dropped even without chunked flushing, so the delivery test proves nothing. Raise SEED_COUNT.",
                        SEED_COUNT, spans, SeedCicdPipeline.MAX_QUEUE_SIZE, SeedCicdPipeline.MAX_EXPORT_BATCH_SIZE)
                .isGreaterThan(mostTheSdkCanHold);
    }

    @Test
    void deliversEveryRun() throws Exception {
        List<ReceivedSpan> spans = runSeeder();

        assertThat(spans)
                .as("spans received; spans are being dropped between the SDK and the wire")
                .hasSize(SEED_COUNT * SPANS_PER_RUN);
    }

    /**
     * Checks the shape a flakiness/duration query depends on: one trace per
     * run, a root plus four task children, with the test task itself parenting
     * its test-case spans.
     */
    @Test
    void everyRunIsOneTraceWithFiveTaskLevelSpans() throws Exception {
        List<ReceivedSpan> spans = runSeeder();

        Map<String, List<ReceivedSpan>> traces = new HashMap<>();
        for (ReceivedSpan s : spans) {
            traces.computeIfAbsent(s.traceId(), k -> new ArrayList<>()).add(s);
        }

        assertThat(traces).as("distinct traces, one per run").hasSize(SEED_COUNT);

        traces.forEach((traceId, ss) -> {
            assertThat(ss).as("spans in trace %s", traceId).hasSize(SPANS_PER_RUN);
            int roots = 0;
            int tasks = 0;
            int tests = 0;
            for (ReceivedSpan s : ss) {
                if (s.isRoot()) {
                    roots++;
                } else if (!s.attrs().getOrDefault("cicd.pipeline.task.name", "").isEmpty()) {
                    tasks++;
                } else {
                    tests++;
                }
            }
            assertThat(new int[] {roots, tasks, tests})
                    .as("trace %s: roots, tasks, test cases", traceId)
                    .containsExactly(1, 4, 20);
        });
    }

    /** Guards against attribute-naming drift that a Honeycomb query against this dataset would depend on. */
    @Test
    void emitsExpectedAttributes() throws Exception {
        List<ReceivedSpan> spans = runSeeder();

        var seen = new HashSet<String>();
        for (ReceivedSpan s : spans) {
            seen.addAll(s.attrs().keySet());
        }

        for (String key : List.of(
                "cicd.pipeline.name",
                "cicd.pipeline.run.id",
                "vcs.change.id",
                "cicd.pipeline.task.name",
                "test.name")) {
            assertThat(seen).as("no span carried \"%s\"", key).contains(key);
        }
    }

    /**
     * Checks the build task survives serialization at all. The scenario's own
     * test ({@code ScenarioTest.regressionIsAStepChange}) already covers the
     * duration-shape claim precisely against the generator's output; this
     * test's narrower job is confirming the wire format.
     */
    @Test
    void buildTaskSpansAreOnTheWire() throws Exception {
        List<ReceivedSpan> spans = runSeeder();

        List<ReceivedSpan> buildSpans = spans.stream()
                .filter(s -> "build".equals(s.attrs().get("cicd.pipeline.task.name")))
                .toList();
        assertThat(buildSpans).as("build-task spans on the wire, one per run").hasSize(SEED_COUNT);
    }

    /**
     * Checks the other load-bearing fact survives serialization: the flaky test
     * case really does fail some of the time, with a real STATUS_CODE_ERROR (2)
     * on the wire.
     */
    @Test
    void flakyTestFailureIsVisibleOnTheWire() throws Exception {
        List<ReceivedSpan> spans = runSeeder();

        int flakyTotal = 0;
        int flakyFailed = 0;
        for (ReceivedSpan s : spans) {
            if (!"TestPaymentRetryIsIdempotent".equals(s.attrs().get("test.name"))) {
                continue;
            }
            flakyTotal++;
            if (s.status() == ReceivedSpan.STATUS_CODE_ERROR) {
                flakyFailed++;
            }
        }

        assertThat(flakyTotal).as("flaky-test spans, one per run").isEqualTo(SEED_COUNT);
        assertThat(flakyFailed)
                .as("flaky test failures; it never failed on the wire across the whole seed, so there is nothing for a flakiness query to find")
                .isNotZero();
        assertThat(flakyFailed)
                .as("flaky test failures; it failed on every run, which is a broken test, not a flaky one")
                .isLessThan(flakyTotal);
    }
}
