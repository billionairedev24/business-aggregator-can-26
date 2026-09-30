/**
 * Partner webhook delivery (S-33, docs/runbooks/webhooks.md): the {@code webhooks} consumer group maps domain events
 * to versioned public payloads and queues one delivery per subscribed endpoint ({@code developer.webhook_deliveries});
 * the dispatcher delivers them per endpoint — one in flight per endpoint, on virtual threads, so a slow or failing
 * endpoint never holds up the others or a Kafka partition — with Stripe-style HMAC-SHA256 signatures, exponential
 * retries over about three days, SSRF-safe HTTP and auto-disable with an email to the owners.
 */
@NullMarked
package ca.northline.worker.webhooks;

import org.jspecify.annotations.NullMarked;
