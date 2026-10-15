package io.honeycomb.masterclass.otelquickstart;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the trace shape produced by the quickstart server.
 *
 * <p>They mirror the Go tests in spirit but use a live JDK HttpServer for each
 * case rather than {@code httptest.ResponseRecorder}. That keeps the
 * instrumenting wrapper exercised end-to-end.</p>
 */
class OtelQuickstartTest {

    private InMemorySpanExporter exporter;
    private OpenTelemetrySdk sdk;
    private com.sun.net.httpserver.HttpServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        exporter = InMemorySpanExporter.create();
        sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build())
            .setPropagators(ContextPropagators.create(io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance()))
            .buildAndRegisterGlobal();

        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/healthz", new OtelQuickstart.TracingHandler(sdk, "/healthz", new OtelQuickstart.HealthzHandler()));
        server.createContext("/api/checkout", new OtelQuickstart.TracingHandler(sdk, "/api/checkout", new OtelQuickstart.CheckoutHandler()));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        exporter.reset();
        sdk.shutdown();
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void checkoutCreatesExactlyOneRootSpan() throws Exception {
        HttpResponse<String> response = post("/api/checkout");

        assertThat(response.statusCode()).isEqualTo(201);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).isNotEmpty();

        List<SpanData> roots = spans.stream().filter(s -> !s.getParentSpanContext().isValid()).toList();
        assertThat(roots).hasSize(1);

        SpanData root = roots.get(0);
        assertThat(root.getName()).isEqualTo("POST /api/checkout");
        assertThat(attribute(root, "http.route")).isEqualTo("/api/checkout");
    }

    @Test
    void inboundTraceparentIsJoined() throws Exception {
        String upstreamTrace = "4bf92f3577b34da6a3ce929d0e0e4736";
        String upstreamSpan = "00f067aa0ba902b7";

        HttpResponse<String> response = send(HttpRequest.newBuilder(baseUri("/api/checkout"))
            .header("traceparent", "00-" + upstreamTrace + "-" + upstreamSpan + "-01")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build());

        assertThat(response.statusCode()).isEqualTo(201);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData requestSpan = spans.get(0);
        assertThat(requestSpan.getName()).isEqualTo("POST /api/checkout");
        assertThat(requestSpan.getTraceId()).isEqualTo(upstreamTrace);
        assertThat(requestSpan.getParentSpanContext().getSpanId()).isEqualTo(upstreamSpan);
        assertThat(requestSpan.getParentSpanContext().isRemote()).isTrue();
    }

    private HttpResponse<String> post(String path) throws Exception {
        return send(HttpRequest.newBuilder(baseUri(path))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private java.net.URI baseUri(String path) {
        return URI.create("http://localhost:" + server.getAddress().getPort() + path);
    }

    private static String attribute(SpanData span, String key) {
        Object value = span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(key));
        return value == null ? null : value.toString();
    }
}
