package io.honeycomb.masterclass.slisslos.seedsliservice;

import com.sun.net.httpserver.HttpServer;
import io.honeycomb.masterclass.slisslos.scenario.Config;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end test of the seeder's {@code run} method rather than of a function
 * inside it, because the bug it exists to prevent lived precisely in the gap
 * between the two.
 *
 * <p>The BatchSpanProcessor silently drops spans that overflow its queue (2048
 * by default). An earlier seeder in this repo (session 2's) generated
 * everything and flushed once at the end, so ~95% of it was dropped, and the
 * command exited zero, printing a cheerful success message. Every unit test
 * passed, because the generator was producing the data correctly. The data just
 * never arrived. This test runs the real program against a real OTLP receiver
 * and counts what actually lands on the wire.
 *
 * <p>Two differences from the Go test. The Go one runs {@code go run .} as a
 * subprocess and receives spans over gRPC. This one calls {@code run} in the
 * same JVM and receives spans over OTLP/HTTP, because the JDK has an HTTP
 * server ({@link HttpServer}) but not a gRPC one. The batching, queue, and
 * export code under test are the same for both transports.
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
     */
    private static final int SEED_COUNT = 12000;

    private static final long SLOW_RECEIVER_MILLIS = 100;

    /** What the receiver keeps from each span. */
    private record ReceivedSpan(String name, String parentId, Map<String, String> attrs, long start, long end) {
    }

    private HttpServer server;
    private final List<ReceivedSpan> spans = new ArrayList<>();
    private volatile boolean failExports;

    /** The in-process OTLP receiver: accepts POST /v1/traces and records every span. */
    @BeforeEach
    void startReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/traces", exchange -> {
            try (exchange) {
                // A slow Collector. The Java SDK exports on a background thread
                // and a local receiver is fast, so without this delay the
                // exporter keeps up with the generator and an unchunked seeder
                // would pass too. The delay makes the queue actually fill.
                sleep(SLOW_RECEIVER_MILLIS);
                if (failExports) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
                byte[] body = exchange.getRequestBody().readAllBytes();
                ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(body);
                record(request);
                // An empty ExportTraceServiceResponse is zero bytes of protobuf.
                exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterEach
    void stopReceiver() {
        server.stop(0);
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

    private synchronized List<ReceivedSpan> collected() {
        return List.copyOf(spans);
    }

    private static String renderValue(AnyValue v) {
        return switch (v.getValueCase()) {
            case STRING_VALUE -> v.getStringValue();
            case INT_VALUE -> Long.toString(v.getIntValue());
            case BOOL_VALUE -> Boolean.toString(v.getBoolValue());
            default -> v.toString();
        };
    }

    /** Runs the seeder against the in-process receiver and returns everything the receiver saw. */
    private List<ReceivedSpan> runSeeder() {
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        Map<String, String> env = Map.of(
                "OTEL_EXPORTER_OTLP_ENDPOINT", endpoint,
                "OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf",
                "SEED_COUNT", Integer.toString(SEED_COUNT));

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        SeedSliService.run(env, new PrintStream(captured, true, StandardCharsets.UTF_8));

        assertThat(captured.toString(StandardCharsets.UTF_8))
                .as("seeder did not report success")
                .contains("seeded");
        return collected();
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
    void deliversEveryRequest() {
        List<ReceivedSpan> received = runSeeder();

        // The load-bearing assertion. SEED_COUNT spans (one per request, no
        // child spans in this dataset) is well past the 8192-span
        // queue, so an unchunked flush fails here rather than in production.
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
    void failsLoudlyWhenTheReceiverRejectsExports() {
        failExports = true;
        Map<String, String> env = Map.of(
                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:" + server.getAddress().getPort(),
                "OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf",
                "SEED_COUNT", "50");

        assertThatThrownBy(() -> SeedSliService.run(env, new PrintStream(new ByteArrayOutputStream())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is the Collector up?");
    }

    /**
     * Guards against the attribute-naming drift that the slides and the derived
     * column depend on. If a rename lands here, the SLI's
     * {@code IF(LT($http.response.status_code, 400), ...)} expression stops
     * matching the data.
     */
    @Test
    void emitsExpectedAttributes() {
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
    void healthzAndIncidentAreVisibleOnTheWire() {
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
