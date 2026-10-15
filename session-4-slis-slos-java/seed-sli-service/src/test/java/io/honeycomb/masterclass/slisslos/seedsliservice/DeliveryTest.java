package io.honeycomb.masterclass.slisslos.seedsliservice;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.honeycomb.masterclass.slisslos.scenario.Config;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the shipped command rather than of a function inside it,
 * because the bug it exists to prevent lived precisely in the gap between the
 * two.
 *
 * <p>The BatchSpanProcessor silently drops spans that overflow its queue (2048
 * by default). An earlier seeder in this repo (session 2's) generated
 * everything and flushed once at the end, so ~95% of it was dropped, and the
 * command exited zero, printing a cheerful success message. Every unit test
 * passed, because the generator was producing the data correctly. The data just
 * never arrived. This test runs the real program in its own JVM, like Go's
 * {@code go run .}, against a real OTLP/gRPC receiver and counts what actually
 * lands on the wire.
 *
 * <p>The receiver is a gRPC server, not the JDK's {@code HttpServer}. The
 * seeder ships spans with OTLP over gRPC, which needs HTTP/2, and
 * {@code HttpServer} only speaks HTTP/1.1.
 */
class DeliveryTest {

    /**
     * Must produce more spans than the processor can hold, which is
     * {@link SeedSliService#MAX_QUEUE_SIZE} plus one export batch (the batch in
     * flight has already left the queue). Go only needs to beat the queue size;
     * Java's worker thread takes a batch out of the queue first, so 9000 spans
     * would still fit. Below that, this test cannot observe a dropped-span
     * regression at all.
     * {@link #seedVolumeExceedsQueue} enforces that rather than leaving it to a
     * comment.
     *
     * <p>Tuned by mutation: with the chunked flush removed (flush only at the
     * end) this test must fail, and it does. The margin over the threshold
     * (12000 vs 9216) plus the slow receiver below are what make it fail
     * reliably instead of by luck.
     */
    private static final int SEED_COUNT = 12000;

    /**
     * A slow Collector. The Java SDK exports on a background thread and a local
     * receiver is fast, so without a delay per request the exporter keeps up
     * with the generator and an unchunked seeder would pass too. The delay
     * makes the queue actually fill.
     */
    private static final long SLOW_RECEIVER_MILLIS = 100;

    /** What the receiver keeps from each span. */
    private record ReceivedSpan(String name, String parentId, Map<String, String> attrs, long start, long end) {
    }

    /** The fake Collector: remembers every span it is sent, or rejects every export. */
    private static final class Collector extends TraceServiceGrpc.TraceServiceImplBase {
        // gRPC calls Export on several threads, so access is synchronised.
        private final List<ReceivedSpan> spans = new ArrayList<>();
        private final boolean failExports;

        Collector(boolean failExports) {
            this.failExports = failExports;
        }

        @Override
        public void export(ExportTraceServiceRequest request, StreamObserver<ExportTraceServiceResponse> responseObserver) {
            sleep(SLOW_RECEIVER_MILLIS);
            if (failExports) {
                // INTERNAL is not retried by the exporter. UNAVAILABLE would be,
                // with backoff, and the failure test would take many seconds.
                responseObserver.onError(Status.INTERNAL.withDescription("rejected by test").asRuntimeException());
                return;
            }
            record(request);
            responseObserver.onNext(ExportTraceServiceResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }

        private synchronized void record(ExportTraceServiceRequest request) {
            for (ResourceSpans rs : request.getResourceSpansList()) {
                for (ScopeSpans ss : rs.getScopeSpansList()) {
                    for (Span span : ss.getSpansList()) {
                        Map<String, String> attrs = new HashMap<>();
                        span.getAttributesList().forEach(kv -> attrs.put(kv.getKey(), renderValue(kv.getValue())));
                        spans.add(new ReceivedSpan(
                                span.getName(),
                                HexFormat.of().formatHex(span.getParentSpanId().toByteArray()),
                                attrs,
                                span.getStartTimeUnixNano(),
                                span.getEndTimeUnixNano()));
                    }
                }
            }
        }

        synchronized List<ReceivedSpan> collected() {
            return List.copyOf(spans);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    /** What the seeder process printed and how it exited. */
    private record SeederRun(int exitCode, String output) {
    }

    /**
     * Starts an in-process OTLP receiver, runs the seeder as a separate JVM
     * against it, and returns the process result.
     */
    private static SeederRun startSeeder(Collector sink, int seedCount) throws Exception {
        Server server = NettyServerBuilder
                .forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .addService(sink)
                .build()
                .start();
        try {
            // Go runs `go run .`. The Java equivalent is the same main class
            // the Gradle `run` task starts, in a fresh JVM with the same
            // classpath. The classpath comes from the build file.
            ProcessBuilder builder = new ProcessBuilder(
                    System.getProperty("java.home") + "/bin/java",
                    "-cp", System.getProperty("seeder.classpath"),
                    SeedSliService.class.getName())
                    .redirectErrorStream(true);
            builder.environment().put("OTEL_EXPORTER_OTLP_ENDPOINT", "127.0.0.1:" + server.getPort());
            builder.environment().put("SEED_COUNT", Integer.toString(seedCount));

            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(3, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new AssertionError("seeder did not finish in 3 minutes:\n" + output);
            }
            return new SeederRun(process.exitValue(), output);
        } finally {
            server.shutdownNow();
            server.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /** Runs the seeder against a healthy receiver and returns everything the receiver saw. */
    private static List<ReceivedSpan> runSeeder() throws Exception {
        Collector sink = new Collector(false);
        SeederRun run = startSeeder(sink, SEED_COUNT);
        assertThat(run.exitCode()).as("seeder exit code:\n%s", run.output()).isZero();
        assertThat(run.output()).as("seeder did not report success").contains("seeded");
        return sink.collected();
    }

    /**
     * Keeps the delivery test honest. At a volume below the queue depth, an
     * unchunked flush delivers everything anyway and the delivery test passes on
     * broken code, which is exactly what happened the first time a seeder like
     * this was written in this repo.
     */
    @Test
    void seedVolumeExceedsQueue() {
        int capacity = SeedSliService.MAX_QUEUE_SIZE + SeedSliService.MAX_EXPORT_BATCH_SIZE;
        assertThat(SEED_COUNT)
                .as("test seeds %d requests (one span each) against room for %d spans; nothing would be dropped"
                        + " even without chunked flushing, so the delivery test proves nothing. Raise SEED_COUNT.",
                        SEED_COUNT, capacity)
                .isGreaterThan(capacity);
    }

    @Test
    void deliversEveryRequest() throws Exception {
        List<ReceivedSpan> received = runSeeder();

        // The load-bearing assertion. SEED_COUNT spans (one per request, no
        // child spans in this dataset) is well past the 8192-span
        // queue plus one batch, so an unchunked flush fails here rather than in production.
        assertThat(received)
                .as("spans are being dropped between the SDK and the wire")
                .hasSize(SEED_COUNT);
    }

    /**
     * No Go counterpart (Go's ForceFlush returns the export error by itself).
     * Java's SDK reports a flush as successful even when the export failed, so
     * the seeder wraps the exporter to notice. Without that, a seed against a
     * dead Collector would print "seeded N requests" and deliver nothing.
     */
    @Test
    void failsLoudlyWhenTheReceiverRejectsExports() throws Exception {
        SeederRun run = startSeeder(new Collector(true), 50);

        assertThat(run.exitCode()).as("seeder exit code:\n%s", run.output()).isNotZero();
        assertThat(run.output()).contains("is the Collector up?").doesNotContain("seeded");
    }

    /**
     * Guards against the attribute-naming drift that the slides and the derived
     * column depend on. If a rename lands here, the SLI's
     * {@code IF(LT($http.response.status_code, 400), ...)} expression stops
     * matching the data.
     */
    @Test
    void emitsExpectedAttributes() throws Exception {
        Set<String> seen = new HashSet<>();
        for (ReceivedSpan s : runSeeder()) {
            seen.addAll(s.attrs().keySet());
        }

        for (String key : List.of(
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
            assertThat(seen)
                    .as("no span carried \"%s\"; the derived column and saved queries reference it", key)
                    .contains(key);
        }
    }

    /**
     * Checks that the demo's two load-bearing facts survive serialization:
     * /healthz traffic actually exists (so the exclusion clause has something to
     * exclude) and the incident cohort (buggy build, enterprise, inside the
     * recent incident window) really does error at an elevated rate once the
     * data has been through the SDK and the exporter, not just in the generator.
     * The window matters here as much as in the scenario tests: buggy-build-and-
     * enterprise traffic from before the incident started is just ordinary
     * background-rate traffic, so leaving out the recency filter would dilute the
     * rate this test is checking.
     */
    @Test
    void healthzAndIncidentAreVisibleOnTheWire() throws Exception {
        List<ReceivedSpan> received = runSeeder();

        Config cfg = Config.defaults(Instant.now());
        // The seeder ran moments ago with now == its own Instant.now(), so an
        // incident window measured back from this test's Instant.now() still
        // contains the same requests, with room to spare for the run time.
        Instant incidentStartInstant = Instant.now().minus(cfg.incidentAgo());
        long incidentStart = incidentStartInstant.getEpochSecond() * 1_000_000_000L + incidentStartInstant.getNano();

        int healthz = 0;
        int incidentErrors = 0;
        int incidentTotal = 0;
        for (ReceivedSpan s : received) {
            if ("/healthz".equals(s.attrs().get("http.route"))) {
                healthz++;
                continue;
            }
            if (!cfg.buggyVersion().equals(s.attrs().get("service.version"))
                    || !"enterprise".equals(s.attrs().get("user.type"))) {
                continue;
            }
            if (s.start() < incidentStart) {
                continue;
            }
            incidentTotal++;
            if ("true".equals(s.attrs().get("error"))) {
                incidentErrors++;
            }
        }

        assertThat(healthz)
                .as("no /healthz spans on the wire; the exclusion clause has nothing to demonstrate")
                .isGreaterThan(0);
        assertThat(incidentTotal)
                .as("no service.version=%s + user.type=enterprise spans inside the incident window on the wire;"
                        + " nothing for BubbleUp or the burn query to find", cfg.buggyVersion())
                .isGreaterThan(0);
        double rate = (double) incidentErrors / incidentTotal;
        assertThat(rate)
                .as("incident cohort error rate on the wire; too subtle to demo")
                .isGreaterThanOrEqualTo(0.3);
    }
}
