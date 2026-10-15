package io.honeycomb.masterclass.seedarbitraryquestion;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.honeycomb.masterclass.seedarbitraryquestion.wideevent.Event;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the shipped command, for the same reason as the other
 * seeders: the bug this prevents lived in the gap between "the data was built
 * correctly" and "the data arrived".
 *
 * <p>The BatchSpanProcessor silently drops spans that overflow its queue (2048
 * by default in Go, 2048 in Java too). An earlier version of this seeder
 * generated all 10,000 requests and then flushed once at the end, so ~95% of
 * them were dropped, and the command exited zero, printing a cheerful success
 * message. Every unit test passed, because the generator was producing the data
 * correctly. The data just never arrived.
 *
 * <p>So: run the real program, point it at a real OTLP receiver, and count what
 * actually lands on the wire.
 *
 * <p>Where this differs from the Go test:
 *
 * <ul>
 *   <li>The receiver is a JDK {@link HttpServer} speaking OTLP over HTTP, not a
 *       gRPC server. The seeder switches to HTTP through the standard
 *       {@code OTEL_EXPORTER_OTLP_PROTOCOL} variable.
 *   <li>"The real binary" is a child JVM started with the main classes on its
 *       classpath, where Go uses {@code go run .}.
 *   <li>The seeder runs once and the tests share what it delivered. Go runs it
 *       once per test, which costs more and checks nothing extra.
 * </ul>
 */
class DeliveryTest {

    /**
     * Must produce more spans than {@code MAX_QUEUE_SIZE}, or this test cannot
     * observe a dropped-span regression at all.
     * {@link #seedVolumeExceedsQueue()} enforces that rather than leaving it to
     * a comment. One span per event here, so the count itself has to clear the
     * queue depth.
     */
    private static final int SEED_COUNT = 12000;

    /** One span per event: this dataset is canonical-log shaped, not trace shaped. */
    private static final int WANT_SPANS_PER_TRACE = 1;

    private record ReceivedSpan(String name, String parentId, Map<String, String> attrs, long start, long end) {}

    private static HttpServer server;
    private static List<ReceivedSpan> received;

    /** Collects every span the fake receiver is sent. */
    private static final List<ReceivedSpan> sink = new ArrayList<>();

    @AfterAll
    static void stopReceiver() {
        if (server != null) {
            server.stop(0);
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

    /** The OTLP/HTTP endpoint: decode the protobuf body and remember the spans. */
    private static void handleExport(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = "gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))
                ? new GZIPInputStream(exchange.getRequestBody())
                : exchange.getRequestBody()) {
            body = in.readAllBytes();
        }

        ExportTraceServiceRequest req = ExportTraceServiceRequest.parseFrom(body);
        List<ReceivedSpan> batch = new ArrayList<>();
        for (ResourceSpans rs : req.getResourceSpansList()) {
            for (ScopeSpans ss : rs.getScopeSpansList()) {
                for (Span span : ss.getSpansList()) {
                    Map<String, String> attrs = new HashMap<>();
                    for (KeyValue kv : span.getAttributesList()) {
                        attrs.put(kv.getKey(), renderValue(kv.getValue()));
                    }
                    batch.add(new ReceivedSpan(
                            span.getName(),
                            HexFormat.of().formatHex(span.getParentSpanId().toByteArray()),
                            attrs,
                            span.getStartTimeUnixNano(),
                            span.getEndTimeUnixNano()));
                }
            }
        }
        synchronized (sink) {
            sink.addAll(batch);
        }

        byte[] response = ExportTraceServiceResponse.getDefaultInstance().toByteArray();
        exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
        exchange.sendResponseHeaders(200, response.length == 0 ? -1 : response.length);
        if (response.length > 0) {
            exchange.getResponseBody().write(response);
        }
        exchange.close();
    }

    /**
     * Starts an in-process OTLP receiver, runs the seeder against it, and
     * returns everything the receiver saw. Runs once; later calls reuse the
     * result.
     */
    private static synchronized List<ReceivedSpan> runSeeder() throws Exception {
        if (received != null) {
            return received;
        }

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/traces", DeliveryTest::handleExport);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-cp", System.getProperty("seeder.classpath"), SeedArbitraryQuestion.class.getName());
        pb.environment().put("OTEL_EXPORTER_OTLP_ENDPOINT", "127.0.0.1:" + server.getAddress().getPort());
        pb.environment().put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf");
        pb.environment().put("SEED_COUNT", Integer.toString(SEED_COUNT));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(3, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("seeder did not finish in 3 minutes:\n" + out);
        }
        assertThat(process.exitValue()).as("seeder failed:\n%s", out).isZero();
        assertThat(out).as("seeder did not report success").contains("seeded");

        synchronized (sink) {
            received = List.copyOf(sink);
        }
        return received;
    }

    /**
     * Keeps the delivery test honest. At a volume below the queue depth, an
     * unchunked flush delivers everything anyway and the delivery test passes
     * on broken code, which is exactly what happened the first time this file
     * was written.
     */
    @Test
    void seedVolumeExceedsQueue() {
        int spans = SEED_COUNT * WANT_SPANS_PER_TRACE;
        assertThat(spans)
                .as("test seeds %d spans against a %d-span queue; nothing would be dropped even without "
                        + "chunked flushing, so the delivery test proves nothing. Raise SEED_COUNT.",
                        spans, SeedArbitraryQuestion.MAX_QUEUE_SIZE)
                .isGreaterThan(SeedArbitraryQuestion.MAX_QUEUE_SIZE);
    }

    @Test
    void seederDeliversEveryTrace() throws Exception {
        List<ReceivedSpan> spans = runSeeder();

        List<ReceivedSpan> roots = spans.stream().filter(s -> s.parentId().isEmpty()).toList();

        // The load-bearing assertion. SEED_COUNT spans is well past the
        // 8192-span queue, so an unchunked flush fails here rather than in
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
