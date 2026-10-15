package io.honeycomb.masterclass.feedbackloops.seedcanaryregression;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An end-to-end test of the shipped command rather than of a function inside
 * it, because the bug it exists to prevent lived precisely in the gap between
 * the two.
 *
 * <p>The BatchSpanProcessor silently drops spans that overflow its queue (2048
 * by default in the OTel SDKs). An earlier version of this seeder generated all
 * 10,000 requests and then flushed once at the end, so ~95% of them were
 * dropped, and the command exited zero, printing a cheerful success message.
 * Every unit test passed, because the scenario package was generating the data
 * correctly. The data just never arrived.</p>
 *
 * <p>So: run the real program in its own JVM, point it at a real OTLP receiver,
 * and count what actually lands on the wire.</p>
 *
 * <p>The receiver is a gRPC server, not the JDK's {@code HttpServer}. The
 * seeder ships spans with OTLP over gRPC, which needs HTTP/2, and
 * {@code HttpServer} only speaks HTTP/1.1.</p>
 */
class DeliveryTest {

    /**
     * Must produce more spans than {@link SeedCanaryRegression#MAX_QUEUE_SIZE},
     * or this test cannot observe a dropped-span regression at all.
     * {@link #seedVolumeExceedsQueue()} enforces that rather than leaving it to
     * a comment.
     */
    private static final int SEED_COUNT = 2500;

    /**
     * The root plus its four steps. Both routes have exactly four steps, so
     * this is deterministic.
     */
    private static final int WANT_SPANS_PER_TRACE = 5;

    private record ReceivedSpan(String name, String parentId, Map<String, String> attrs, long start, long end) {
        long durationNanos() {
            return end - start;
        }
    }

    /** The fake Collector: remembers every span it is sent. */
    private static final class Collector extends TraceServiceGrpc.TraceServiceImplBase {
        // gRPC calls Export on several threads, so access is synchronised
        // (Go's version guards its slice with a mutex).
        private final List<ReceivedSpan> spans = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void export(ExportTraceServiceRequest request, StreamObserver<ExportTraceServiceResponse> responseObserver) {
            for (var rs : request.getResourceSpansList()) {
                for (var ss : rs.getScopeSpansList()) {
                    for (var span : ss.getSpansList()) {
                        var attrs = new HashMap<String, String>();
                        for (KeyValue kv : span.getAttributesList()) {
                            attrs.put(kv.getKey(), renderValue(kv.getValue()));
                        }
                        spans.add(new ReceivedSpan(
                                span.getName(),
                                hex(span.getParentSpanId().toByteArray()),
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

    private static String hex(byte[] bytes) {
        var sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Starts an in-process OTLP receiver, runs the seeder against it, and
     * returns everything the receiver saw.
     */
    private static List<ReceivedSpan> runSeeder() throws Exception {
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
                    SeedCanaryRegression.class.getName())
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

            return sink.collected();
        } finally {
            server.shutdownNow();
            server.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Keeps the delivery test honest. At a volume below the queue depth, an
     * unchunked flush delivers everything anyway and the delivery test passes
     * on broken code, which is exactly what happened the first time this test
     * was written.
     */
    @Test
    void seedVolumeExceedsQueue() {
        int spans = SEED_COUNT * WANT_SPANS_PER_TRACE;
        assertThat(spans)
                .as("test seeds %d spans against a %d-span queue; nothing would be dropped even without chunked flushing, "
                        + "so the delivery test proves nothing. Raise SEED_COUNT.", spans, SeedCanaryRegression.MAX_QUEUE_SIZE)
                .isGreaterThan(SeedCanaryRegression.MAX_QUEUE_SIZE);
    }

    @Test
    void deliversEveryTrace() throws Exception {
        var spans = runSeeder();

        var roots = spans.stream().filter(s -> s.parentId().isEmpty()).toList();

        // The load-bearing assertion. SEED_COUNT * 5 spans is well past the
        // default 2048-span queue, so an unchunked flush fails here rather than
        // in production.
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
    void emitsExpectedAttributes() throws Exception {
        var spans = runSeeder();

        var seen = new java.util.HashSet<String>();
        for (var s : spans) {
            if (s.parentId().isEmpty()) {
                seen.addAll(s.attrs().keySet());
            }
        }

        for (var key : List.of(
                "http.request.method",
                "http.response.status_code",
                "http.route",
                "url.path",
                "user.id",
                "user.type",
                "service.version",
                "service.environment",
                "error",
                "error.type")) {
            assertThat(seen).as("no root span carried \"%s\"; the deck and the saved queries reference it", key).contains(key);
        }

        // These are the names the book replaced. Their absence is the assertion.
        for (var stale : List.of("http.method", "http.status_code", "customer.tier", "deployment.environment")) {
            assertThat(seen).as("root spans carry \"%s\", which the book's Chapter 6 tables do not use", stale).doesNotContain(stale);
        }
    }

    /**
     * Checks that the demo's whole point survives serialization: the affected
     * cohort really is slower once the data has been through the SDK and the
     * exporter, not just in the generator.
     */
    @Test
    void regressionIsVisibleOnTheWire() throws Exception {
        var spans = runSeeder();

        var canary = new ArrayList<Long>();
        var baseline = new ArrayList<Long>();
        var versions = new HashMap<String, Integer>();

        for (var s : spans) {
            if (!s.parentId().isEmpty()) {
                continue;
            }
            versions.merge(s.attrs().get("service.version"), 1, Integer::sum);

            if (!"/api/billing".equals(s.attrs().get("http.route")) || !"enterprise".equals(s.attrs().get("user.type"))) {
                continue;
            }
            switch (s.attrs().get("service.version")) {
                case "1.5.0" -> canary.add(s.durationNanos());
                case "1.4.2" -> baseline.add(s.durationNanos());
                default -> { }
            }
        }

        assertThat(versions.keySet()).as("service.version values on the wire: %s", versions).hasSize(2);
        assertThat(canary)
                .as("enterprise billing requests on the canary build; there is nothing for BubbleUp to find")
                .isNotEmpty();
        assertThat(baseline)
                .as("enterprise billing requests on the baseline build; there is no comparison to draw")
                .isNotEmpty();

        Collections.sort(canary);
        Collections.sort(baseline);
        long canaryP50 = canary.get(canary.size() / 2);
        long baselineP50 = baseline.get(baseline.size() / 2);

        double ratio = (double) canaryP50 / baselineP50;
        assertThat(ratio)
                .as("canary P50 vs baseline for the affected cohort (%d ns vs %d ns); too subtle to demo", canaryP50, baselineP50)
                .isGreaterThanOrEqualTo(1.2);
    }
}
