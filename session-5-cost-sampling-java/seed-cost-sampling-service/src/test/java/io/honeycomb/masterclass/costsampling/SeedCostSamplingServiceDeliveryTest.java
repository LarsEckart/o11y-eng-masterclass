package io.honeycomb.masterclass.costsampling;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
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
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This is an end-to-end test of the shipped seeder rather than of a function
 * inside it, for the same reason as session 4's: the BatchSpanProcessor
 * silently drops spans that overflow its queue, and only a real run against a
 * real OTLP receiver can catch that.
 *
 * <p>Go runs the command as a child process ({@code go run .}) against an
 * in-process gRPC receiver. Here the seeder's {@code run} method is called
 * in-process, with its environment passed as a map, against a fake receiver
 * built on the JDK's {@link HttpServer}. The JDK server cannot speak gRPC, so
 * the seeder is told to use OTLP over HTTP ({@code OTEL_EXPORTER_OTLP_PROTOCOL
 * =http/protobuf}). The spans are still the SDK's real exporter output,
 * encoded as protobuf on the wire; only the transport differs.
 */
class SeedCostSamplingServiceDeliveryTest {

    /**
     * seedCount must produce more than MAX_QUEUE_SIZE spans (two per request)
     * or this test cannot observe a dropped-span regression at all.
     */
    static final int SEED_COUNT = 4500;

    /** What the receiver keeps from each span it is sent. */
    record ReceivedSpan(String traceId, String name, String parentId,
                        Map<String, String> attrs, Status.StatusCode status, long startNanos) {}

    /** A fake OTLP/HTTP receiver. It records every span and never samples. */
    static final class Receiver implements AutoCloseable {
        private final HttpServer server;
        private final List<ReceivedSpan> spans = new ArrayList<>();

        Receiver() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/v1/traces", exchange -> {
                try (exchange) {
                    InputStream body = exchange.getRequestBody();
                    if ("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
                        body = new GZIPInputStream(body);
                    }
                    record(ExportTraceServiceRequest.parseFrom(body));

                    byte[] response = ExportTraceServiceResponse.getDefaultInstance().toByteArray();
                    exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
                    // A length of -1 means "no body", which an empty protobuf message is.
                    exchange.sendResponseHeaders(200, response.length == 0 ? -1 : response.length);
                    if (response.length > 0) {
                        exchange.getResponseBody().write(response);
                    }
                }
            });
            server.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private synchronized void record(ExportTraceServiceRequest request) {
            for (var rs : request.getResourceSpansList()) {
                for (var ss : rs.getScopeSpansList()) {
                    for (Span span : ss.getSpansList()) {
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
        public void close() {
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

    /**
     * Starts a receiver, runs the seeder against it, and returns everything
     * the receiver saw. SEED_SETTLE_WAIT and SEED_DURATION are forced near
     * zero: this receiver never samples, so there is nothing for the seeder's
     * production pacing and wait (against the real Collector's
     * adjustment_interval and decision_delay) to accomplish here.
     */
    private static List<ReceivedSpan> runSeeder(int count, Duration duration) throws IOException {
        try (var receiver = new Receiver()) {
            var output = new ByteArrayOutputStream();
            Map<String, String> env = Map.of(
                "OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf",
                "OTEL_EXPORTER_OTLP_ENDPOINT", receiver.endpoint(),
                "SEED_COUNT", Integer.toString(count),
                "SEED_SETTLE_WAIT", "10ms",
                "SEED_DURATION", GoDuration.format(duration));

            SeedCostSamplingService.run(env, new PrintStream(output, true, StandardCharsets.UTF_8));

            assertThat(output.toString(StandardCharsets.UTF_8))
                .as("seeder did not report success")
                .contains("seeded");
            return receiver.collected();
        }
    }

    // Four of the five Go tests share one full-size run (Go runs the seeder
    // again for each). One run is enough: they all inspect the same output.
    private static List<ReceivedSpan> fullRun;

    @BeforeAll
    static void seedOnce() throws IOException {
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
        assertThat(spans)
            .as("test seeds %d requests (%d spans) against a %d-span queue; nothing would be dropped "
                + "even without chunked flushing, so the delivery test proves nothing. Raise SEED_COUNT.",
                SEED_COUNT, spans, SeedCostSamplingService.MAX_QUEUE_SIZE)
            .isGreaterThan(SeedCostSamplingService.MAX_QUEUE_SIZE);
    }

    /**
     * Guards the pacing the per-route sampling demo depends on (see
     * Scenario's class comment). It measures the spread of span start times
     * rather than the run's wall time, so JVM startup or setup cost can't
     * hide a seeder that never sleeps.
     */
    @Test
    void seederPacesEmissionOverDuration() throws IOException {
        int count = 200;
        Duration duration = Duration.ofSeconds(2);
        List<ReceivedSpan> spans = runSeeder(count, duration);
        assertThat(spans).as("received spans").isNotEmpty();

        long first = spans.stream().mapToLong(ReceivedSpan::startNanos).min().orElseThrow();
        long last = spans.stream().mapToLong(ReceivedSpan::startNanos).max().orElseThrow();

        // The last request starts one spacing short of the full duration.
        long want = duration.toNanos() * (count - 1) / count;
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

            boolean e0 = ss.get(0).status() == Status.StatusCode.STATUS_CODE_ERROR;
            boolean e1 = ss.get(1).status() == Status.StatusCode.STATUS_CODE_ERROR;
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
