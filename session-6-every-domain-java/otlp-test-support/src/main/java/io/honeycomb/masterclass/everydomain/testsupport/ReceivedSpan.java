package io.honeycomb.masterclass.everydomain.testsupport;

import java.util.Map;

/**
 * One span as the fake Collector saw it on the wire.
 *
 * @param traceId  lowercase hex
 * @param parentId lowercase hex; empty for a root span
 * @param attrs    attribute values rendered to strings, so tests can compare
 *                 them without caring about the OTLP value type
 * @param status   the OTLP status code number: 0 unset, 1 ok, 2 error
 */
public record ReceivedSpan(
        String traceId,
        String name,
        String parentId,
        Map<String, String> attrs,
        int status,
        long startNanos,
        long endNanos) {

    /** OTLP {@code STATUS_CODE_ERROR}. */
    public static final int STATUS_CODE_ERROR = 2;

    public boolean isRoot() {
        return parentId.isEmpty();
    }
}
