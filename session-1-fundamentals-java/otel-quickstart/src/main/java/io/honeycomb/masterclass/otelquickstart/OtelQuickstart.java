package io.honeycomb.masterclass.otelquickstart;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.NetworkAttributes;
import io.opentelemetry.semconv.ServerAttributes;
import io.opentelemetry.semconv.ServiceAttributes;
import io.opentelemetry.semconv.UrlAttributes;
import io.opentelemetry.semconv.UserAgentAttributes;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;

/**
 * Java version of the Masterclass 1 live demo.
 *
 * <p>Go ships with net/http middleware ({@code otelhttp}) that auto-instruments
 * a server. In plain Java the closest thing without adding a web framework is
 * the JDK's built-in {@link HttpServer} plus a small SDK-based handler wrapper
 * that extracts W3C traceparents, starts a server span, and records the usual
 * HTTP attributes.</p>
 *
 * <p>The demo still proceeds in the same three beats as the Go original:</p>
 * <ol>
 *   <li>Auto-style HTTP instrumentation alone.</li>
 *   <li>Add {@code order.id} as an attribute on the request span.</li>
 *   <li>Add a {@code process_order} child span for the processing duration.</li>
 * </ol>
 */
public class OtelQuickstart {

    static final String SERVICE_NAME = "otel-quickstart";

    public static void main(String[] args) throws Exception {
        OpenTelemetrySdk sdk = bootstrapTracing();
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/healthz", new TracingHandler(sdk, "/healthz", new HealthzHandler()));
            server.createContext("/api/checkout", new TracingHandler(sdk, "/api/checkout", new CheckoutHandler()));
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            System.out.println(SERVICE_NAME + " listening on :8080");
            Thread.currentThread().join();
        } finally {
            sdk.shutdown();
        }
    }

    private static OpenTelemetrySdk bootstrapTracing() {
        String endpoint = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");

        OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(endpoint)
            .setTimeout(Duration.ofSeconds(10))
            .build();

        SpanProcessor processor = BatchSpanProcessor.builder(exporter).build();

        Resource resource = Resource.getDefault()
            .merge(Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, SERVICE_NAME)));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(processor)
            .setResource(resource)
            .build();

        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .buildAndRegisterGlobal();
    }

    /**
     * Puts the current server span into a no-op tracer so the class compiles
     * before the global SDK is registered. The real tracer is fetched from the
     * SDK when it is available.
     */
    private static Tracer tracer() {
        return io.opentelemetry.api.GlobalOpenTelemetry.getTracer(SERVICE_NAME);
    }

    static void setRoute(Context context, String route) {
        Span.fromContext(context).setAttribute(HttpAttributes.HTTP_ROUTE, route);
    }

    static void enrich(Context context, Attributes attributes) {
        Span.fromContext(context).setAllAttributes(attributes);
    }

    static class HealthzHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Context ctx = (Context) exchange.getAttribute("otel.context");
            setRoute(ctx, "/healthz");
            sendResponse(exchange, 200, "{\"status\":\"ok\"}");
        }
    }

    static class CheckoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Context ctx = (Context) exchange.getAttribute("otel.context");
            setRoute(ctx, "/api/checkout");
            String orderId = generateOrderId();

            // --- BEAT 2: one more attribute ---
            // enrich(ctx, Attributes.of(AttributeKey.stringKey("order.id"), orderId));
            // --- END BEAT 2 ---

            processOrder(ctx, orderId);

            sendResponse(exchange, 201, "{\"order_id\":\"" + orderId + "\"}");
        }
    }

    static void processOrder(Context context, String orderId) {
        // --- BEAT 3: one more span ---
        // Span span = tracer().spanBuilder("process_order").setParent(context).startSpan();
        // Context spanCtx = context.with(span);
        // try {
        //     span.setAttribute(AttributeKey.stringKey("order.id"), orderId);
        //     simulateProcessing();
        // } finally {
        //     span.end();
        // }
        // --- END BEAT 3 ---
    }

    static void simulateProcessing() {
        try {
            Thread.sleep(Duration.ofMillis(20 + new Random().nextInt(80)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String generateOrderId() {
        return "ord_" + Long.toUnsignedString(new Random().nextLong(), 16);
    }

    static void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().put("Content-Type", List.of("application/json"));
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Minimal SDK-based HTTP instrumentation wrapper. Extracts an inbound
     * W3C traceparent, starts a server span, records common HTTP attributes,
     * stores the {@link Context} on the exchange, and ends the span when the
     * application handler returns.
     */
    static class TracingHandler implements HttpHandler {
        private final OpenTelemetry telemetry;
        private final String route;
        private final HttpHandler next;

        TracingHandler(OpenTelemetry telemetry, String route, HttpHandler next) {
            this.telemetry = telemetry;
            this.route = route;
            this.next = next;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Context parent = telemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), exchange, EXCHANGE_GETTER);

            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String spanName = method + " " + route;
            Span span = telemetry.getTracer(SERVICE_NAME)
                .spanBuilder(spanName)
                .setParent(parent)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(HttpAttributes.HTTP_REQUEST_METHOD, method)
                .setAttribute(HttpAttributes.HTTP_ROUTE, route)
                .setAttribute(UrlAttributes.URL_PATH, exchange.getRequestURI().getPath())
                .setAttribute(UrlAttributes.URL_SCHEME, exchange.getRequestURI().getScheme() == null ? "http" : exchange.getRequestURI().getScheme())
                .setAttribute(ServerAttributes.SERVER_ADDRESS, exchange.getRequestHeaders().getFirst("Host"))
                .setAttribute(ServerAttributes.SERVER_PORT, (long) exchange.getLocalAddress().getPort())
                .setAttribute(NetworkAttributes.NETWORK_PEER_ADDRESS, exchange.getRemoteAddress().getAddress().getHostAddress())
                .setAttribute(NetworkAttributes.NETWORK_PEER_PORT, (long) exchange.getRemoteAddress().getPort())
                .setAttribute(NetworkAttributes.NETWORK_PROTOCOL_VERSION, "1.1")
                .startSpan();

            Context current = parent.with(span);
            exchange.setAttribute("otel.context", current);

            try {
                next.handle(exchange);
            } catch (IOException | RuntimeException e) {
                span.setStatus(StatusCode.ERROR);
                span.recordException(e);
                throw e;
            } finally {
                int status = exchange.getResponseCode();
                if (status > 0) {
                    span.setAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, (long) status);
                }
                String ua = exchange.getRequestHeaders().getFirst("User-Agent");
                if (ua != null) {
                    span.setAttribute(UserAgentAttributes.USER_AGENT_ORIGINAL, ua);
                }
                span.end();
            }
        }
    }

    private static final TextMapGetter<HttpExchange> EXCHANGE_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpExchange carrier) {
            return carrier.getRequestHeaders().keySet();
        }

        @Override
        public String get(HttpExchange carrier, String key) {
            if (carrier == null || key == null) {
                return null;
            }
            return carrier.getRequestHeaders().getFirst(key);
        }
    };
}
