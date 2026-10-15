/**
 * Generates the Masterclass 4 SLI/SLO dataset. Java port of the Go package
 * {@code internal/scenario}.
 *
 * <h2>The scenario</h2>
 *
 * <p>A single business endpoint, {@code /api/orders}, plus a {@code /healthz}
 * check that gets polled far more often than anyone calls the real endpoint.
 * Health checks always succeed. That imbalance is the point: Chapter 11's
 * HTTP-API SLI is
 *
 * <pre>
 *   count(status_code &lt; 400) / count(*) where route != '/healthz'
 * </pre>
 *
 * <p>and this dataset exists to make the {@code where route != '/healthz'}
 * clause matter. Leave it out and the healthy healthz traffic dilutes a real
 * incident into invisibility; see
 * {@code ScenarioTest.healthzExclusionMasksTheDrop}.
 *
 * <p>Most traffic runs a healthy build. A minority is still on an older build
 * that carries a latent defect: for a recent, ongoing window, requests from
 * that build for enterprise customers fail at an elevated rate. Everywhere
 * else (other users, other times, /healthz) the error rate is the ordinary
 * background rate. That gives BubbleUp two real dimensions to rank
 * ({@code service.version} and {@code user.type}), and gives the burn-rate demo
 * something genuinely happening in the trailing hour rather than a flat line.
 *
 * <h2>Why these proportions</h2>
 *
 * <p>The incident has to survive two competing pressures: visible enough that a
 * 1-hour AVG() query and a BubbleUp box both find it cleanly, subtle enough
 * that it doesn't dominate the whole dataset (a burn-rate alert on a SLI
 * that's obviously broken all the time isn't a burn-rate demo, it's just an
 * outage). The incident population is the product of four independent
 * filters (in the window, not a health check, enterprise, buggy build), and
 * four compounding fractions shrink fast. So the window is deliberately short
 * (6h, "this seed covers a recent stretch of traffic", not a full day) and
 * the enterprise and buggy-build shares are deliberately generous. With the
 * defaults in {@link io.honeycomb.masterclass.slisslos.scenario.Config#defaults}
 * and 15,000 requests:
 *
 * <pre>
 *   requests in the incident window     15000 * (60/360)          = 2500
 *   ... on /api/orders (not healthz)    * 0.43                    = 1075
 *   ... enterprise                      * 0.33                    =  355
 *   ... on the buggy build              * 0.43                    =  153
 * </pre>
 *
 * <p>~150 affected requests, ~95% of them erroring, is what it actually takes
 * to reliably drop the trailing-1-hour AVG() below the trigger's
 * {@code burn_threshold} (0.90, see
 * {@code ../session-4-slis-slos/honeycomb-setup/terraform/variables.tf}). The
 * expected-value arithmetic above is necessary but not sufficient: every
 * request is sampled at random, so the affected population varies run to run,
 * and the generator is reseeded on every real invocation (see the seeder).
 * These proportions are chosen so the trailing-window ratio clears
 * {@code burn_threshold} with margin across that variance rather than only in
 * expectation, verified by simulating the generator across many random seeds
 * and requiring the trigger to fire in all of them, not just most.
 */
package io.honeycomb.masterclass.slisslos.scenario;
