package io.honeycomb.masterclass.instrumentationtraps.contextpropagation;

import io.honeycomb.masterclass.instrumentationtraps.shared.DemoTrace;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

/**
 * Fix: the helper accepts the caller's {@link Context} and uses it as the
 * parent for its own span, so both spans share one trace.
 */
public class After {

    public static void main(String[] args) throws Exception {
        try (AutoCloseable ignored = DemoTrace.setup("instrumentation-traps-context-propagation-after")) {
            Context rootCtx = Context.current().with(
                GlobalOpenTelemetry.getTracer("instrumentation-traps-context-propagation-after")
                    .spanBuilder("handle_request")
                    .startSpan());
            Span rootSpan = Span.fromContext(rootCtx);
            System.out.println("handle_request trace ID: " + rootSpan.getSpanContext().getTraceId());

            validateOrder(rootCtx);

            rootSpan.end();
            System.out.println("FIXED: validateOrder takes Context and joins the same trace.");
        }
    }

    private static void validateOrder(Context parent) {
        Context ctx = parent.with(
            GlobalOpenTelemetry.getTracer("instrumentation-traps-context-propagation-after")
                .spanBuilder("validate_order")
                .setParent(parent)
                .startSpan());
        Span span = Span.fromContext(ctx);
        try {
            System.out.println("validate_order trace ID: " + span.getSpanContext().getTraceId() + " (same)");
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            span.end();
        }
    }
}
