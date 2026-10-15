package io.honeycomb.masterclass.everydomain.seedllmevalpipeline;

import io.honeycomb.masterclass.everydomain.testsupport.FakeCollector;
import io.honeycomb.masterclass.everydomain.testsupport.ReceivedSpan;
import io.honeycomb.masterclass.everydomain.testsupport.SeederProcess;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;

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
     * One span per conversation, so this must exceed the queue plus one export
     * batch, or this test cannot observe a dropped-span regression at all.
     * {@link #seedVolumeExceedsQueue()} enforces that rather than leaving it to
     * a comment.
     */
    private static final int SEED_COUNT = 10000;

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
            return SeederProcess.run(SeedLlmEvalPipeline.class, collector, SEED_COUNT);
        }
    }

    /**
     * Java's {@code forceFlush()} reports success even when every export
     * failed, so the seeder wraps its exporter in {@code FailureTrackingExporter}.
     * Without this test, deleting that wrapper breaks nothing: a Collector that
     * rejects the data would still get "seeded N" and exit code 0. The receiver
     * answers INTERNAL, which the exporter does not retry, so the test stays fast.
     */
    @Test
    void failsLoudlyWhenTheReceiverRejectsExports() throws Exception {
        try (var collector = FakeCollector.startRejecting()) {
            var result = SeederProcess.runAllowingFailure(SeedLlmEvalPipeline.class, collector, 50);

            assertThat(result.exitCode()).as("seeder exit code:\n%s", result.output()).isNotZero();
            assertThat(result.output())
                    .contains("is the Collector up?")
                    .doesNotContain("seeded");
        }
    }

    /**
     * Keeps the delivery test honest. At a volume below the queue depth, an
     * unchunked flush delivers everything anyway and the delivery test passes
     * on broken code.
     */
    @Test
    void seedVolumeExceedsQueue() {
        int mostTheSdkCanHold = SeedLlmEvalPipeline.MAX_QUEUE_SIZE + SeedLlmEvalPipeline.MAX_EXPORT_BATCH_SIZE;
        assertThat(SEED_COUNT)
                .as("test seeds %d conversations (one span each) against a %d-span queue plus a %d-span batch; nothing would be "
                                + "dropped even without chunked flushing, so the delivery test proves nothing. Raise SEED_COUNT.",
                        SEED_COUNT, SeedLlmEvalPipeline.MAX_QUEUE_SIZE, SeedLlmEvalPipeline.MAX_EXPORT_BATCH_SIZE)
                .isGreaterThan(mostTheSdkCanHold);
    }

    @Test
    void deliversEveryConversation() throws Exception {
        List<ReceivedSpan> spans = runSeeder();

        assertThat(spans)
                .as("spans received; spans are being dropped between the SDK and the wire")
                .hasSize(SEED_COUNT);
    }

    /**
     * Guards against the attribute-naming and type drift that MC4's reused SLI
     * formula depends on: {@code llm.response.quality_score} must arrive as a
     * numeric value Honeycomb's COUNT/comparison operators can actually
     * threshold against.
     */
    @Test
    void qualityScoreIsANumberOnTheWire() throws Exception {
        List<ReceivedSpan> spans = runSeeder();

        var names = new HashSet<String>();
        int parsed = 0;
        for (ReceivedSpan s : spans) {
            names.add(s.name());
            String raw = s.attrs().get("llm.response.quality_score");
            assertThat(raw).as("span missing llm.response.quality_score").isNotNull();
            double v;
            try {
                v = Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                throw new AssertionError("llm.response.quality_score \"" + raw + "\" did not parse as a number", e);
            }
            assertThat(v).as("llm.response.quality_score is outside the expected 0-1 range").isBetween(0.0, 1.0);
            parsed++;
        }
        assertThat(parsed).as("parsed quality scores").isEqualTo(SEED_COUNT);
        assertThat(names).as("span names").containsExactly("scored conversation");
    }
}
