package io.honeycomb.masterclass.everydomain.seedllmevalpipeline;

import io.honeycomb.masterclass.everydomain.llmscenario.Config;
import io.honeycomb.masterclass.everydomain.llmscenario.Conversation;
import io.honeycomb.masterclass.everydomain.llmscenario.Scenario;
import io.honeycomb.masterclass.everydomain.otlpexport.FailureTrackingExporter;
import io.honeycomb.masterclass.everydomain.otlpexport.OtlpExport;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ServiceAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Backfills the Masterclass 6 LLM-eval supplement: a batch of scored
 * conversations standing in for the output of an eval pipeline. See
 * {@link Scenario} for why this dataset is small and narrow. The live LLM demo
 * runs on real Claude Code OTel telemetry (see the session README) for
 * everything except quality scoring, which real telemetry has no signal for at
 * all.
 *
 * <p>Run this against the Collector from {@code session-1-fundamentals/collector},
 * same as every other session.</p>
 */
public final class SeedLlmEvalPipeline {

    static final String SERVICE_NAME = "llm-eval-pipeline";

    /**
     * The BatchSpanProcessor queue depth. Named rather than inlined because the
     * delivery test needs it, same rationale as every other seeder in this repo.
     */
    static final int MAX_QUEUE_SIZE = 8192;

    /**
     * Spans sent per export call. Also named for the delivery test: one batch
     * can be in flight while the queue behind it is full, so the queue plus one
     * batch is the most the SDK can hold before it starts dropping.
     */
    static final int MAX_EXPORT_BATCH_SIZE = 1024;

    /**
     * The nominal time the (real, out-of-band) eval pipeline takes to score one
     * conversation. Not load-bearing for the demo query, but a real span
     * shouldn't read as zero-duration when someone clicks into it.
     */
    private static final Duration EVAL_DURATION_BASE = Duration.ofMillis(200);

    private SeedLlmEvalPipeline() {
    }

    public static void main(String[] args) {
        var exports = new FailureTrackingExporter(OtlpExport.create(System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")));
        SdkTracerProvider provider = setupTracing(exports);
        // Go keeps the tracer in a package variable. Java passes it along
        // explicitly, which keeps emit easy to call from a test.
        Tracer tracer = provider.get(SERVICE_NAME);

        Config cfg = Config.defaults(Instant.now());
        Integer count = parseInt(System.getenv("SEED_COUNT"));
        if (count != null) {
            cfg = cfg.withConversationCount(count);
        }

        // Go seeds from the clock; new Random() does the same.
        var rng = new Random();
        List<Conversation> convos = Scenario.generate(cfg, rng);

        final int chunk = 1000;

        int regressed = 0;
        for (int i = 0; i < convos.size(); i++) {
            Conversation c = convos.get(i);
            // The same rng that generated the conversations also picks each
            // span's duration, as in Go.
            emit(tracer, c, rng);
            if (c.regressed()) {
                regressed++;
            }
            if ((i + 1) % chunk == 0) {
                flush(provider, exports, "flushing spans after " + (i + 1) + " conversations");
            }
        }

        flush(provider, exports, "final flush");
        if (!provider.shutdown().join(30, TimeUnit.SECONDS).isSuccess()) {
            fatal("shutting down tracer provider failed");
        }

        // Locale.ROOT so the percentage prints the same on every machine.
        System.out.print(String.format(Locale.ROOT, """

                seeded %d scored conversations into service "%s"
                  quality-regressed  %d (%.0f%%) \u2014 every one still reads as a normal, successful conversation otherwise

                See ../session-6-every-domain/honeycomb-setup for the saved SLI query (MC4's own formula:
                count(llm.response.quality_score > 0.7) / count(*)), reused unmodified here.
                """,
                convos.size(), SERVICE_NAME,
                regressed, 100 * (double) regressed / convos.size()));
    }

    /**
     * Blocks until the exporter has shipped everything queued so far, so the
     * caller can safely generate the next chunk.
     */
    private static void flush(SdkTracerProvider provider, FailureTrackingExporter exports, String what) {
        CompletableResultCode result = provider.forceFlush().join(30, TimeUnit.SECONDS);
        // forceFlush alone is not enough in Java; see FailureTrackingExporter.
        if (!result.isSuccess() || exports.failures() > 0) {
            fatal(what + ": flush failed or timed out "
                    + "(is the Collector up? see ../session-1-fundamentals/collector)");
        }
    }

    private static SdkTracerProvider setupTracing(FailureTrackingExporter exporter) {
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, SERVICE_NAME)));

        return SdkTracerProvider.builder()
                // A queue well above the 2048 default, so a chunk never comes
                // close to overflowing it even if an export is briefly slow.
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setMaxQueueSize(MAX_QUEUE_SIZE)
                        .setMaxExportBatchSize(MAX_EXPORT_BATCH_SIZE)
                        .build())
                .setResource(resource)
                .build();
    }

    /**
     * Turns one scored conversation into a single root span at an explicit
     * historical timestamp. This dataset is eval-pipeline output, not a live
     * trace, so there is nothing a child span would add.
     */
    static void emit(Tracer tracer, Conversation c, Random rng) {
        Instant end = c.start().plus(EVAL_DURATION_BASE).plusMillis(rng.nextInt(300));
        Span span = tracer.spanBuilder("scored conversation")
                .setStartTimestamp(c.start())
                .startSpan();
        // A double on purpose. The delivery test checks that it arrives as a
        // number, because MC4's SLI formula compares it with > 0.7.
        span.setAttribute("llm.response.quality_score", c.qualityScore());
        span.end(end);
    }

    /** Go's {@code strconv.Atoi} with the error swallowed: null means "not a number". */
    private static Integer parseInt(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Go's {@code log.Fatalf}: print the reason and exit non-zero. */
    private static void fatal(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
