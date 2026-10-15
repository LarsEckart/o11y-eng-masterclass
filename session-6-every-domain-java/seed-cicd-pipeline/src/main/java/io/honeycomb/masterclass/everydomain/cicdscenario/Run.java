package io.honeycomb.masterclass.everydomain.cicdscenario;

import java.time.Instant;
import java.util.List;

/**
 * One pipeline run (one trace), fully resolved.
 *
 * @param prNumber  the {@code vcs.change.id} this run was triggered by. A run
 *                  on main still carries one: it is the PR that merged.
 * @param branch    the {@code vcs.ref.head.name} this run built:
 *                  {@link Scenario#MAIN_BRANCH_NAME}, or a per-PR branch
 *                  derived from {@code prNumber}.
 * @param regressed true if this run falls after the regression start, so its
 *                  build task uses the regressed duration. Exposed so tests can
 *                  assert on the population rather than re-deriving the rule.
 */
public record Run(
        Instant start,
        String runId,
        int prNumber,
        String branch,
        boolean regressed,
        List<Task> tasks,
        TaskResult result) {
}
