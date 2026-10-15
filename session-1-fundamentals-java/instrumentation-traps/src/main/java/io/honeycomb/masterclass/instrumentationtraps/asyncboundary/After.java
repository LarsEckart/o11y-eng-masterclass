package io.honeycomb.masterclass.instrumentationtraps.asyncboundary;

import io.honeycomb.masterclass.instrumentationtraps.shared.DemoTrace;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Fix: the producer injects the active trace context into a carrier map that
 * rides alongside the payload. The consumer extracts it before starting its
 * span, so the consumer joins the producer's trace.
 */
public class After {

    record Message(String orderId, Map<String, String> carrier) {
    }

    public static void main(String[] args) throws Exception {
        try (AutoCloseable ignored = DemoTrace.setup("instrumentation-traps-async-boundary-after")) {
            BlockingQueue<Message> queue = new ArrayBlockingQueue<>(1);

            Thread consumer = new Thread(() -> consume(queue));
            consumer.start();

            Context produceCtx = Context.current().with(
                GlobalOpenTelemetry.getTracer("instrumentation-traps-async-boundary-after")
                    .spanBuilder("produce_order_message")
                    .startSpan());
            Span produceSpan = Span.fromContext(produceCtx);
            System.out.println("producer trace ID: " + produceSpan.getSpanContext().getTraceId());

            Map<String, String> carrier = new HashMap<>();
            GlobalOpenTelemetry.getPropagators().getTextMapPropagator().inject(produceCtx, carrier, MapCarrierSetter.INSTANCE);
            queue.put(new Message("ord_42", carrier));
            produceSpan.end();

            consumer.join();
            System.out.println("FIXED: the carrier crossed the queue boundary, so the consumer joins the producer's trace.");
        }
    }

    private static void consume(BlockingQueue<Message> queue) {
        try {
            Message msg = queue.take();
            Context parent = GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), msg.carrier(), MapCarrierGetter.INSTANCE);

            Context consumeCtx = parent.with(
                GlobalOpenTelemetry.getTracer("instrumentation-traps-async-boundary-after")
                    .spanBuilder("consume_order_message")
                    .setParent(parent)
                    .startSpan());
            Span span = Span.fromContext(consumeCtx);
            try {
                System.out.println("consumer trace ID:  " + span.getSpanContext().getTraceId() + " (same) order=" + msg.orderId());
                Thread.sleep(5);
            } finally {
                span.end();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    enum MapCarrierSetter implements TextMapSetter<Map<String, String>> {
        INSTANCE;

        @Override
        public void set(Map<String, String> carrier, String key, String value) {
            if (carrier != null && key != null && value != null) {
                carrier.put(key, value);
            }
        }
    }
}
