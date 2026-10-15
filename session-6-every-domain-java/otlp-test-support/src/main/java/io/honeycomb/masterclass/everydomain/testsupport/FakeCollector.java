package io.honeycomb.masterclass.everydomain.testsupport;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A fake OTLP/gRPC Collector that remembers every span it is sent. The Java
 * version of the {@code collector} type that each Go {@code delivery_test.go}
 * defines.
 *
 * <p>It is a real gRPC server, not the JDK's {@code HttpServer}: the seeders
 * ship spans with OTLP over gRPC, which needs HTTP/2, and {@code HttpServer}
 * only speaks HTTP/1.1.</p>
 */
public final class FakeCollector implements AutoCloseable {

    private final Server server;
    private final Sink sink;

    private FakeCollector(Server server, Sink sink) {
        this.server = server;
        this.sink = sink;
    }

    /** Starts a receiver on a free loopback port that answers every export at once. */
    public static FakeCollector start() throws IOException {
        return start(Duration.ZERO);
    }

    /**
     * Starts a receiver that takes {@code perExportDelay} to answer each export
     * request.
     *
     * <p>A real Collector is slower than a local test double. A delivery test
     * that must catch dropped spans wants a slow receiver: the SDK's queue only
     * overflows if the seeder produces spans faster than the receiver drains
     * them, and an instant receiver can keep up by accident on a fast machine.
     * See the delivery tests for the delay they use and why.</p>
     */
    public static FakeCollector start(Duration perExportDelay) throws IOException {
        var sink = new Sink(perExportDelay);
        Server server = NettyServerBuilder
                .forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .addService(sink)
                .build()
                .start();
        return new FakeCollector(server, sink);
    }

    /** The {@code host:port} to put in {@code OTEL_EXPORTER_OTLP_ENDPOINT}. */
    public String address() {
        return "127.0.0.1:" + server.getPort();
    }

    /** Everything received so far. */
    public List<ReceivedSpan> collected() {
        return sink.collected();
    }

    @Override
    public void close() throws InterruptedException {
        server.shutdownNow();
        server.awaitTermination(10, TimeUnit.SECONDS);
    }

    private static final class Sink extends TraceServiceGrpc.TraceServiceImplBase {
        // gRPC calls export on several threads, so access is synchronised (Go's
        // version guards its slice with a mutex).
        private final List<ReceivedSpan> spans = Collections.synchronizedList(new ArrayList<>());
        private final Duration delay;

        Sink(Duration delay) {
            this.delay = delay;
        }

        @Override
        public void export(ExportTraceServiceRequest request, StreamObserver<ExportTraceServiceResponse> responseObserver) {
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            for (var rs : request.getResourceSpansList()) {
                for (var ss : rs.getScopeSpansList()) {
                    for (var span : ss.getSpansList()) {
                        var attrs = new HashMap<String, String>();
                        for (KeyValue kv : span.getAttributesList()) {
                            attrs.put(kv.getKey(), renderValue(kv.getValue()));
                        }
                        spans.add(new ReceivedSpan(
                                hex(span.getTraceId().toByteArray()),
                                span.getName(),
                                hex(span.getParentSpanId().toByteArray()),
                                attrs,
                                span.getStatus().getCodeValue(),
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
            // Go's %v on a float64: shortest form that round-trips, which is
            // also what Double.toString gives.
            case DOUBLE_VALUE -> Double.toString(v.getDoubleValue());
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
}
