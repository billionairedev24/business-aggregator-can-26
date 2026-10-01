/**
 * Observability shared by every Spring app (S-111, docs/runbooks/observability.md): the defaults for tracing, metrics
 * and their export over OTLP ({@link ca.northline.platform.observability.ObservabilityDefaults}), one sampling rule for
 * the whole system ({@link ca.northline.platform.observability.ConsistentSampling}), trace context across
 * {@code @Async} / Modulith listeners, and no traces for Kubernetes probes.
 */
@NullMarked
package ca.northline.platform.observability;

import org.jspecify.annotations.NullMarked;
