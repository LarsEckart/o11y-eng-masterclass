package io.honeycomb.masterclass.seedarbitraryquestion;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.honeycomb.masterclass.seedarbitraryquestion.wideevent.Event;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An end-to-end test of the shipped command rather than of a function inside
 * it, because the bug it exists to prevent lived in the gap between "the data
 * was built correctly" and "the data arrived".
 *
 * <p>The BatchSpanProcessor silently drops spans that overflow its queue (2048
 * by default in the OTel SDKs; this seeder sets 8192). An earlier version of
 * this seeder generated all 10,000 requests and then flushed once at the end,
 * so ~95% of them were dropped, and the command exited zero, printing a
 * cheerful success message. Every unit test passed, because the generator was
 * producing the data correctly. The data just never arrived.
 *
 * <p>So: run the real program in its own JVM, point it at a real OTLP receiver,
 * and count what actually lands on the wire.
 *
 * <p>The receiver is a gRPC server, not the JDK's {@code HttpServer}. The
 * seeder ships spans with OTLP over gRPC, which needs HTTP/2, and
 * {@code HttpServer} only speaks HTTP/1.1.
 *
 * <p>Where this differs from the Go test: "the real binary" is a child JVM
 * started with the main classes on its classpath, where Go uses
 * {@code go run .}, and the seeder runs once with the tests sharing what it
 * delivered. Go runs it once per test, which costs more and checks nothing
 * extra.
 */
class DeliveryTest {

    /**
     * Must produce more spans than the queue holds, or this test cannot observe
     * a dropped-span regression at all. The real threshold is
     * {@link #DROP_THRESHOLD}, not just {@code MAX_QUEUE_SIZE}:
     * {@link #seedVolumeExceedsQueue()} enforces it rather than leaving it to a
     * comment. One span per event here, so the count itself has to clear it.
     */
    private static final int SEED_COUNT = 12000;

    /** One span per event: this dataset is canonical-log shaped, not trace shaped. */
    private static final int WANT_SPANS_PER_TRACE = 1;

    /**
     * Spans an unchunked seeder can hold without dropping any. The queue holds
     * {@code MAX_QUEUE_SIZE}, and the Java batch worker takes one batch off the
     * queue before it exports, so one more batch fits in flight. Only past this
     * count does the last-flush-only mutation lose spans.
     */
    private static final int DROP_THRESHOLD =
            SeedArbitraryQuestion.MAX_QUEUE_SIZE + SeedArbitraryQuestion.MAX_EXPORT_BATCH_SIZE;

    /**
     * Delay per Export call, so the receiver acts like a Collector under load
     * instead of an instant loopback. Slow exports let the queue fill while the
     * seeder keeps generating spans. Tuned by removing the chunked flush from
     * the seeder (flush only at the end) and running this test: it fails with
     * {@link #SEED_COUNT} = 12000 (above {@link #DROP_THRESHOLD} = 9216), with
     * or without this delay. The delay is margin for fast machines, where the
     * worker might otherwise drain the queue as quickly as it fills.
     */
    private static final long RECEIVER_DELAY_MILLIS = 20;

    private record ReceivedSpan(String name, String parentId, Map<String, String> attrs, long start, long end) {}

    /** The fake Collector: remembers every span it is sent. */
    private static final class Collector extends TraceServiceGrpc.TraceServiceImplBase {
        // gRPC calls Export on several threads, so access is synchronised
        // (Go's version guards its slice with a mutex).
        private final List<ReceivedSpan> spans = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void export(ExportTraceServiceRequest request, StreamObserver<ExportTraceServiceResponse> responseObserver) {
            try {
                Thread.sleep(RECEIVER_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (var rs : request.getResourceSpansList()) {
                for (var ss : rs.getScopeSpansList()) {
                    for (var span : ss.getSpansList()) {
                        var attrs = new HashMap<String, String>();
                        for (KeyValue kv : span.getAttributesList()) {
                            attrs.put(kv.getKey(), renderValue(kv.getValue()));
                        }
                        spans.add(new ReceivedSpan(
                                span.getName(),
                                HexFormat.of().formatHex(span.getParentSpanId().toByteArray()),
                                attrs,
                                span.getStartTimeUnixNano(),
                                span.getEndTimeUnixNano()));
                    }
                }
            }
            responseObserver.onNext(ExportTraceServiceResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }

        List<ReceivedSpan> collected() {
            synchronized (spans) {
                return List.copyOf(spans);
            }
        }
    }

    private static String renderValue(AnyValue v) {
        return switch (v.getValueCase()) {
            case STRING_VALUE -> v.getStringValue();
            case INT_VALUE -> Long.toString(v.getIntValue());
            case BOOL_VALUE -> Boolean.toString(v.getBoolValue());
            default -> v.toString();
        };
    }

    private static List<ReceivedSpan> received;

    /**
     * Starts an in-process OTLP/gRPC receiver, runs the seeder in a child JVM
     * against it, and returns everything the receiver saw. Runs once; later
     * calls reuse the result.
     */
    private static synchronized List<ReceivedSpan> runSeeder() throws Exception {
        if (received != null) {
            return received;
        }
        var sink = new Collector();
        Server server = NettyServerBuilder
                .forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .addService(sink)
                .build()
                .start();
        try {
            // Go runs `go run .`. The Java equivalent is the same main class
            // the Gradle `run` task starts, in a fresh JVM with the same
            // classpath. The classpath comes from the build file.
            var builder = new ProcessBuilder(
                    System.getProperty("java.home") + "/bin/java",
                    "-cp", System.getProperty("seeder.classpath"),
                    SeedArbitraryQuestion.class.getName())
                    .redirectErrorStream(true);
            builder.environment().put("OTEL_EXPORTER_OTLP_ENDPOINT", "127.0.0.1:" + server.getPort());
            builder.environment().put("SEED_COUNT", Integer.toString(SEED_COUNT));

            var process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(3, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new AssertionError("seeder did not finish in 3 minutes:\n" + output);
            }
            assertThat(process.exitValue()).as("seeder exit code:\n%s", output).isZero();
            assertThat(output).as("seeder did not report success").contains("seeded");

            received = sink.collected();
            return received;
        } finally {
            server.shutdownNow();
            server.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Keeps the delivery test honest. At a volume below {@link #DROP_THRESHOLD},
     * an unchunked flush delivers everything anyway and the delivery test
     * passes on broken code, which is exactly what happened the first time this
     * file was written.
     */
    @Test
    void seedVolumeExceedsQueue() {
        int spans = SEED_COUNT * WANT_SPANS_PER_TRACE;
        assertThat(spans)
                .as("test seeds %d spans but an unchunked seeder holds %d (a %d-span queue plus one in-flight batch); "
                        + "nothing would be dropped even without chunked flushing, so the delivery test proves "
                        + "nothing. Raise SEED_COUNT.",
                        spans, DROP_THRESHOLD, SeedArbitraryQuestion.MAX_QUEUE_SIZE)
                .isGreaterThan(DROP_THRESHOLD);
    }

    @Test
    void seederDeliversEveryTrace() throws Exception {
        List<ReceivedSpan> spans = runSeeder();

        List<ReceivedSpan> roots = spans.stream().filter(s -> s.parentId().isEmpty()).toList();

        // The load-bearing assertion. SEED_COUNT spans is well past
        // DROP_THRESHOLD, so an unchunked flush fails here rather than in
        // production.
        assertThat(roots)
                .as("root spans received; spans are being dropped between the SDK and the wire")
                .hasSize(SEED_COUNT);
        assertThat(spans).as("spans received in total").hasSize(SEED_COUNT * WANT_SPANS_PER_TRACE);
    }

    /**
     * Guards against the attribute-naming drift that the slides and the book
     * depend on. If a rename lands here, the deck's BubbleUp screenshots and
     * Masterclass 4's SLI stop matching the data.
     */
    @Test
    void seederEmitsExpectedAttributes() throws Exception {
        List<ReceivedSpan> spans = runSeeder();

        Set<String> seen = new HashSet<>();
        for (ReceivedSpan s : spans) {
            if (s.parentId().isEmpty()) {
                seen.addAll(s.attrs().keySet());
            }
        }

        assertThat(seen).as("keys the deck and the saved queries reference").contains(
                // The four the book's question filters on.
                "user_agent.device",
                "user_agent.app_version",
                "geo.region.iso_code",
                "error",
                // A sample of the width that makes the question answerable
                // without having been anticipated.
                "http.request.method",
                "http.response.status_code",
                "http.route",
                "url.path",
                "user.id",
                "user.type",
                "user.org.id",
                "service.version",
                "service.environment",
                "localization.currency",
                "ratelimit.remaining",
                "stats.postgres_query_count",
                "error.type",
                "exception.slug");

        // These are the names the book replaced. Their absence is the assertion.
        assertThat(seen)
                .as("root spans carry names the book's Chapter 6 tables do not use")
                .doesNotContain("http.method", "http.status_code", "customer.tier", "deployment.environment");
    }

    /**
     * The one that matters for the demo: after serialization, the dataset must
     * still contain events matching all four of the book's filters. An empty
     * result on stage is the failure mode.
     */
    @Test
    void questionIsAnswerableOnTheWire() throws Exception {
        List<ReceivedSpan> spans = runSeeder();

        int matches = 0;
        for (ReceivedSpan s : spans) {
            if (!"true".equals(s.attrs().get("error"))) {
                continue;
            }
            if (!Event.ANSWER_DEVICE.equals(s.attrs().get("user_agent.device"))) {
                continue;
            }
            if (!Event.ANSWER_REGION.equals(s.attrs().get("geo.region.iso_code"))) {
                continue;
            }
            if (!Event.ANSWER_VERSION.equals(s.attrs().get("user_agent.app_version"))) {
                continue;
            }
            // request.local_hour rather than deriving from the span timestamp:
            // the generator builds lunch hour in local time, so a UTC
            // conversion here would silently disagree with it.
            int hour;
            try {
                hour = Integer.parseInt(s.attrs().get("request.local_hour"));
            } catch (NumberFormatException e) {
                continue;
            }
            if (hour < Event.LUNCH_HOUR_START || hour >= Event.LUNCH_HOUR_END) {
                continue;
            }
            matches++;
        }

        assertThat(matches)
                .as("events on the wire that answer the book's arbitrary question; the live demo would show an empty result")
                .isPositive();
        System.out.printf("%d events on the wire answer the arbitrary question%n", matches);
    }
}
