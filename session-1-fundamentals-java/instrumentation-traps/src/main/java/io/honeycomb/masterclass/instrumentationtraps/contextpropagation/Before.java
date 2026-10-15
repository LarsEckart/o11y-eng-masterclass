package io.honeycomb.masterclass.instrumentationtraps.contextpropagation;

import io.honeycomb.masterclass.instrumentationtraps.shared.DemoTrace;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;

/**
 * Trap: the helper has no {@link Context} parameter, so it starts a span
 * against {@link Context#current()} / {@code root} context and creates a
 * brand-new trace instead of joining the caller's.
 */
public class Before {

    public static void main(String[] args) throws Exception {
        try (AutoCloseable ignored = DemoTrace.setup("instrumentation-traps-context-propagation-before")) {
            Context rootCtx = Context.current().with(
                GlobalOpenTelemetry.getTracer("instrumentation-traps-context-propagation-before")
                    .spanBuilder("handle_request")
                    .startSpan());
            Span rootSpan = Span.fromContext(rootCtx);
            System.out.println("handle_request trace ID: " + rootSpan.getSpanContext().getTraceId());

            validateOrder();

            rootSpan.end();
            System.out.println("BUG: validateOrder has no Context parameter, so its span starts a new trace.");
        }
    }

    private static void validateOrder() {
        Context ctx = Context.current().with(
            GlobalOpenTelemetry.getTracer("instrumentation-traps-context-propagation-before")
                .spanBuilder("validate_order")
                .startSpan());
        Span span = Span.fromContext(ctx);
        try {
            System.out.println("validate_order trace ID: " + span.getSpanContext().getTraceId() + " (different!)");
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            span.end();
        }
    }
}
