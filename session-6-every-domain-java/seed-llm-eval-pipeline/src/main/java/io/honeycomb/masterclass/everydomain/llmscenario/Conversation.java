package io.honeycomb.masterclass.everydomain.llmscenario;

import java.time.Instant;

/**
 * One generated, evaluated conversation.
 *
 * @param qualityScore the {@code llm.response.quality_score}
 * @param regressed    exposed so tests can assert on the population rather
 *                     than re-deriving the threshold
 */
public record Conversation(Instant start, double qualityScore, boolean regressed) {
}
