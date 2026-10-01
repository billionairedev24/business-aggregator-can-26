/**
 * Centralised logging (S-112, docs/runbooks/logging.md): structured JSON on the console in every deployed
 * environment, the same lines over OTLP to the Collector, and one {@link ca.northline.platform.logging.Redactor} in
 * front of both, so emails, phone numbers, tokens, card-like numbers, postal codes and one-time codes never leave a pod.
 */
@NullMarked
package ca.northline.platform.logging;

import org.jspecify.annotations.NullMarked;
