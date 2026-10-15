package io.honeycomb.masterclass.everydomain.otlpexport;

import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * Builds the OTLP gRPC trace exporter the seeders share. (Go:
 * {@code internal/otlpexport}.)
 *
 * <p>{@code OTEL_EXPORTER_OTLP_ENDPOINT} is a URL ({@code http://localhost:4317})
 * in the OTel spec and for Claude Code's own export, which session 6 points at
 * the same shell, but a bare {@code host:port} ({@code localhost:4317}) is what
 * the seeders documented first. Both forms are accepted.</p>
 *
 * <p>The two SDKs fail differently on the unsupported form. Go's gRPC would
 * read a URL as a hostname and fail with "too many colons in address". The Java
 * exporter is the other way round: it wants a URL, and a bare {@code host:port}
 * throws or is misread because it has no scheme. So this class does the
 * opposite job from the Go one: it adds a scheme to the bare form.</p>
 */
public final class OtlpExport {

    static final String DEFAULT_ENDPOINT = "localhost:4317";

    private OtlpExport() {
    }

    /**
     * Returns an exporter for {@code endpoint}, which may be a URL or a
     * {@code host:port}, and defaults to the local Collector when null or empty.
     * A bare {@code host:port} is plaintext, since the seeders only ever talk to
     * the local Collector that way.
     *
     * @throws IllegalArgumentException if the endpoint is not a valid URI
     */
    public static SpanExporter create(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            endpoint = DEFAULT_ENDPOINT;
        }
        // In a URL the scheme picks plaintext (http) or TLS (https), as Go's
        // WithEndpointURL does. A bare host:port gets "http://": plaintext is
        // right because this always talks to the local Collector, never
        // straight to Honeycomb; the Collector holds the API key.
        if (!endpoint.contains("://")) {
            endpoint = "http://" + endpoint;
        }

        return OtlpGrpcSpanExporter.builder()
                .setEndpoint(endpoint)
                .build();
    }
}
