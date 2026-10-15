package io.honeycomb.masterclass.everydomain.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs a seeder's {@code main} in its own JVM, the way a user would.
 *
 * <p>Go's delivery tests run {@code go run .}. The Java equivalent is the same
 * main class the Gradle {@code run} task starts, in a fresh JVM with the same
 * classpath. The classpath comes from the build file, which passes it in as the
 * {@code seeder.classpath} system property.</p>
 */
public final class SeederProcess {

    private SeederProcess() {
    }

    /** What a seeder run left behind: its exit code and everything it printed. */
    public record Result(int exitCode, String output) {
    }

    /**
     * Runs {@code mainClass} with {@code OTEL_EXPORTER_OTLP_ENDPOINT} pointing
     * at {@code collector} and {@code SEED_COUNT} set, waits for it to finish,
     * and returns everything the collector received.
     *
     * <p>Fails if the seeder exits non-zero or does not print "seeded".</p>
     */
    public static java.util.List<ReceivedSpan> run(Class<?> mainClass, FakeCollector collector, int seedCount)
            throws IOException, InterruptedException {
        Result result = launch(mainClass, collector, seedCount);
        assertThat(result.exitCode()).as("seeder exit code:\n%s", result.output()).isZero();
        assertThat(result.output()).as("seeder did not report success").contains("seeded");
        return collector.collected();
    }

    /**
     * Like {@link #run}, but makes no claim about the outcome. Use it when the
     * test expects the seeder to fail, for example against
     * {@link FakeCollector#startRejecting()}.
     */
    public static Result runAllowingFailure(Class<?> mainClass, FakeCollector collector, int seedCount)
            throws IOException, InterruptedException {
        return launch(mainClass, collector, seedCount);
    }

    private static Result launch(Class<?> mainClass, FakeCollector collector, int seedCount)
            throws IOException, InterruptedException {
        // Output goes to a file, not a pipe. Reading a pipe to the end would
        // block until the seeder exits and make the timeout below useless.
        Path log = Files.createTempFile("port-6-seeder-", ".log");
        try {
            var builder = new ProcessBuilder(
                    System.getProperty("java.home") + "/bin/java",
                    "-cp", System.getProperty("seeder.classpath"),
                    mainClass.getName())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            builder.environment().put("OTEL_EXPORTER_OTLP_ENDPOINT", collector.address());
            builder.environment().put("SEED_COUNT", Integer.toString(seedCount));

            var process = builder.start();
            boolean finished = process.waitFor(3, TimeUnit.MINUTES);
            String output = Files.readString(log);
            if (!finished) {
                process.destroyForcibly();
                throw new AssertionError("seeder did not finish in 3 minutes:\n" + output);
            }
            return new Result(process.exitValue(), output);
        } finally {
            Files.deleteIfExists(log);
        }
    }
}
