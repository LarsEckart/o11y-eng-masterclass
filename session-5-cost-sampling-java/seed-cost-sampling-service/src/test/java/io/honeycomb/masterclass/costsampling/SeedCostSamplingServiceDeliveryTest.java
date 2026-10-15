package io.honeycomb.masterclass.costsampling;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This is an end-to-end test of the shipped seeder rather than of a function
 * inside it, for the same reason as session 4's: the BatchSpanProcessor
 * silently drops spans that overflow its queue, and only a real run against a
 * real OTLP receiver can catch that.
 *
 * <p>Like Go's delivery test, it runs the command as a child process ({@code
 * go run .} there; a fresh JVM started on the seeder's own main class here,
 * with the classpath passed in through the {@code seeder.classpath} system
 * property by build.gradle) against an in-process gRPC receiver. The receiver
 * is a real gRPC server, not the JDK's {@code HttpServer}: the seeder ships
 * spans with OTLP over gRPC, which needs HTTP/2, and {@code HttpServer} only
 * speaks HTTP/1.1.
 */
class SeedCostSamplingServiceDeliveryTest {

    /**
     * seedCount must produce more spans (two per request) than the queue can
     * hold plus the one batch the worker thread takes off it before it starts
     * exporting, or this test cannot observe a dropped-span regression at all;
     * see {@link #seedVolumeExceedsQueue()}.
     *
     * <p>Tuned by mutation. With the chunked flush removed (flush only at the
     * end), 4500 requests (9000 spans) passed anyway: the queue holds 8192
     * and the worker drains one 1024-span batch first, so 9216 spans fit.
     * 12000 requests (24000 spans) fail the mutation, together with
     * {@link #SLOW_RECEIVER_MILLIS}.
     */
    static final int SEED_COUNT = 12000;

    /**
     * How long the receiver takes per export call. The SDK exports on a
     * background thread and a local receiver is fast, so without a delay the
     * exporter keeps up with the generator and an unchunked seeder would pass
     * too. The delay makes the queue really fill. It costs little for the
     * correct seeder, which waits for each chunk (1000 spans, one export).
     */
    static final long SLOW_RECEIVER_MILLIS = 100;

    /** What the receiver keeps from each span it is sent. */
    record ReceivedSpan(String traceId, String name, String parentId,
                        Map<String, String> attrs, io.opentelemetry.proto.trace.v1.Status.StatusCode status,
                        long startNanos) {}

    /** A fake OTLP/gRPC Collector. It records every span and never samples. */
    static final class Receiver extends TraceServiceGrpc.TraceServiceImplBase implements AutoCloseable {
        private final Server server;
        private final List<ReceivedSpan> spans = new ArrayList<>();
        private final boolean failExports;

        Receiver(boolean failExports) throws IOException {
            this.failExports = failExports;
            server = NettyServerBuilder
                .forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .addService(this)
                .build()
                .start();
        }

        /** Bare host:port, as Go's seeder takes it. */
        String endpoint() {
            return "127.0.0.1:" + server.getPort();
        }

        @Override
        public void export(ExportTraceServiceRequest request, StreamObserver<ExportTraceServiceResponse> responseObserver) {
            // A slow Collector; see SLOW_RECEIVER_MILLIS.
            try {
                Thread.sleep(SLOW_RECEIVER_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (failExports) {
                // Not UNAVAILABLE: the exporter retries that with backoff,
                // which would just make this test slow.
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("rejected by test").asRuntimeException());
                return;
            }
            record(request);
            responseObserver.onNext(ExportTraceServiceResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }

        private synchronized void record(ExportTraceServiceRequest request) {
            for (var rs : request.getResourceSpansList()) {
                for (var ss : rs.getScopeSpansList()) {
                    for (var span : ss.getSpansList()) {
                        var attrs = new HashMap<String, String>();
                        for (KeyValue kv : span.getAttributesList()) {
                            attrs.put(kv.getKey(), renderValue(kv.getValue()));
                        }
                        spans.add(new ReceivedSpan(
                            HexFormat.of().formatHex(span.getTraceId().toByteArray()),
                            span.getName(),
                            HexFormat.of().formatHex(span.getParentSpanId().toByteArray()),
                            attrs,
                            span.getStatus().getCode(),
                            span.getStartTimeUnixNano()));
                    }
                }
            }
        }

        synchronized List<ReceivedSpan> collected() {
            return List.copyOf(spans);
        }

        @Override
        public void close() throws InterruptedException {
            server.shutdownNow();
            server.awaitTermination(10, TimeUnit.SECONDS);
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

    /** What a finished seeder process left behind. */
    record SeederResult(int exitCode, String output) {}

    /**
     * Starts the seeder in a fresh JVM against the endpoint and waits for it.
     * Go runs {@code go run .}; the Java equivalent is the same main class the
     * Gradle {@code run} task starts, with the same classpath.
     * SEED_SETTLE_WAIT is forced near zero: this receiver never samples, so
     * there is nothing for the seeder's production wait (against the real
     * Collector's adjustment_interval and decision_delay) to accomplish here.
     */
    private static SeederResult startSeeder(String endpoint, int count, Duration duration) throws Exception {
        var builder = new ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp", System.getProperty("seeder.classpath"),
            SeedCostSamplingService.class.getName())
            .redirectErrorStream(true);
        builder.environment().put("OTEL_EXPORTER_OTLP_ENDPOINT", endpoint);
        builder.environment().put("SEED_COUNT", Integer.toString(count));
        builder.environment().put("SEED_SETTLE_WAIT", "10ms");
        builder.environment().put("SEED_DURATION", GoDuration.format(duration));

        var process = builder.start();
        // Read to the end first: that returns when the process exits, and
        // keeps the output pipe from filling and blocking the child.
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(3, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("seeder did not finish in 3 minutes:\n" + output);
        }
        return new SeederResult(process.exitValue(), output);
    }

    /**
     * Starts a receiver, runs the seeder against it, and returns everything
     * the receiver saw.
     */
    private static List<ReceivedSpan> runSeeder(int count, Duration duration) throws Exception {
        try (var receiver = new Receiver(false)) {
            var result = startSeeder(receiver.endpoint(), count, duration);
            assertThat(result.exitCode()).as("seeder exit code:\n%s", result.output()).isZero();
            assertThat(result.output()).as("seeder did not report success").contains("seeded");
            return receiver.collected();
        }
    }

    // Four of the five Go tests share one full-size run (Go runs the seeder
    // again for each). One run is enough: they all inspect the same output.
    private static List<ReceivedSpan> fullRun;

    @BeforeAll
    static void seedOnce() throws Exception {
        fullRun = runSeeder(SEED_COUNT, Duration.ZERO);
    }

    /**
     * Keeps the delivery test honest, same rationale as session 4's test of
     * the same name: below the queue depth, an unchunked flush would deliver
     * everything anyway and this test would pass on broken code.
     */
    @Test
    void seedVolumeExceedsQueue() {
        int spans = SEED_COUNT * 2; // two spans per request
        // The batch worker takes one batch off the queue before it exports, so
        // that many extra spans fit without loss.
        int fitsWithoutLoss = SeedCostSamplingService.MAX_QUEUE_SIZE + SeedCostSamplingService.MAX_EXPORT_BATCH_SIZE;
        assertThat(spans)
            .as("test seeds %d requests (%d spans) against a %d-span queue plus a %d-span batch in flight; "
                + "nothing would be dropped even without chunked flushing, so the delivery test proves "
                + "nothing. Raise SEED_COUNT.",
                SEED_COUNT, spans, SeedCostSamplingService.MAX_QUEUE_SIZE, SeedCostSamplingService.MAX_EXPORT_BATCH_SIZE)
            .isGreaterThan(fitsWithoutLoss);
    }

    /**
     * The seeder must fail loudly when exports fail. The Java SDK's
     * forceFlush reports success even if every export was rejected (see
     * SeedCostSamplingService.flush), so without the seeder's own check this
     * would exit zero and print a cheerful success message.
     */
    @Test
    void seederFailsWhenExportsFail() throws Exception {
        try (var receiver = new Receiver(true)) {
            var result = startSeeder(receiver.endpoint(), 100, Duration.ofMillis(10));
            assertThat(result.exitCode()).as("seeder exit code; output:\n%s", result.output()).isNotZero();
            assertThat(result.output()).as("seeder output").contains("flush failed");
            assertThat(result.output()).as("seeder must not claim success").doesNotContain("seeded");
        }
    }

    /**
     * Guards the pacing the per-route sampling demo depends on (see
     * Scenario's class comment). It measures the spread of span start times
     * rather than the run's wall time, so JVM startup or setup cost can't
     * hide a seeder that never sleeps.
     */
    @Test
    void seederPacesEmissionOverDuration() throws Exception {
        int count = 200;
        Duration duration = Duration.ofSeconds(2);
        List<ReceivedSpan> spans = runSeeder(count, duration);
        assertThat(spans).as("received spans").isNotEmpty();

        long first = spans.stream().mapToLong(ReceivedSpan::startNanos).min().orElseThrow();
        long last = spans.stream().mapToLong(ReceivedSpan::startNanos).max().orElseThrow();

        // The last request starts one spacing short of the full duration.
        // In a fresh JVM the first request is slow (class loading, cold code),
        // which pushes the first start time later than its slot and shrinks
        // the spread. Allow 10% for that; a seeder that never sleeps spreads
        // its spans over milliseconds, nowhere near this.
        long want = duration.toNanos() * (count - 1) / count * 9 / 10;
        assertThat(last - first)
            .as("span start times must span at least %s; otherwise the seeder isn't pacing emission over SEED_DURATION",
                GoDuration.format(Duration.ofNanos(want)))
            .isGreaterThanOrEqualTo(want);
    }

    @Test
    void seederDeliversEveryRequest() {
        assertThat(fullRun)
            .as("spans are being dropped between the SDK and the wire")
            .hasSize(SEED_COUNT * 2);
    }

    /**
     * Guards the shape the Collector's adaptive_tail_sampling processor
     * depends on: it decides per trace, which only means something if every
     * trace really does have a root and a child that share a trace, not two
     * unrelated single-span traces.
     */
    @Test
    void everyTraceHasTwoSpansWithAMatchingParent() {
        int roots = 0;
        int children = 0;
        for (ReceivedSpan s : fullRun) {
            if (s.name().equals("db.query")) {
                children++;
                assertThat(s.parentId()).as("db.query span's parent; it should be a child of the root HTTP span").isNotEmpty();
                continue;
            }
            roots++;
            assertThat(s.parentId()).as("parent of span %s; expected a root span", s.name()).isEmpty();
        }
        assertThat(roots).as("root spans").isEqualTo(SEED_COUNT);
        assertThat(children).as("db.query spans").isEqualTo(SEED_COUNT);
    }

    /**
     * Guards against attribute-naming drift that the Collector's rules and
     * the honeycomb-setup queries depend on.
     */
    @Test
    void seederEmitsExpectedAttributes() {
        Set<String> seen = new HashSet<>();
        for (ReceivedSpan s : fullRun) {
            seen.addAll(s.attrs().keySet());
        }

        for (String key : List.of("http.request.method", "http.route", "service.environment", "db.system")) {
            assertThat(seen).as("some span carried %s", key).contains(key);
        }
    }

    /**
     * Checks the demo's other load-bearing fact survives serialization: a
     * meaningful share of traces really do carry STATUS_CODE_ERROR (2), at a
     * rate close to the configured 0.15, once the data has been through the
     * SDK and the exporter, not just in the generator
     * (ScenarioTest.generateErrorRateMatchesConfig already covers the
     * generator itself).
     */
    @Test
    void errorRateIsVisibleOnTheWire() {
        Map<String, List<ReceivedSpan>> traces = new HashMap<>();
        for (ReceivedSpan s : fullRun) {
            traces.computeIfAbsent(s.traceId(), k -> new ArrayList<>()).add(s);
        }

        int errored = 0;
        for (var entry : traces.entrySet()) {
            List<ReceivedSpan> ss = entry.getValue();
            assertThat(ss).as("trace %s should have 2 spans (root + db.query)", entry.getKey()).hasSize(2);

            boolean e0 = ss.get(0).status() == io.opentelemetry.proto.trace.v1.Status.StatusCode.STATUS_CODE_ERROR;
            boolean e1 = ss.get(1).status() == io.opentelemetry.proto.trace.v1.Status.StatusCode.STATUS_CODE_ERROR;
            assertThat(e0)
                .as("trace %s: root and child spans must agree on error status. The seeder's contract is "
                    + "both spans of an errored request carry STATUS_CODE_ERROR, which is what the "
                    + "Collector's keep-errors rule depends on", entry.getKey())
                .isEqualTo(e1);
            if (e0) {
                errored++;
            }
        }

        double rate = (double) errored / traces.size();
        assertThat(rate)
            .as("error-status trace share on the wire, want close to the configured 0.15")
            .isBetween(0.10, 0.20);
    }
}
