/**
 * S-34: the event JSON Schemas as a checked contract — valid and within the worker's validator, one per published
 * event, matching what the records serialise to, and never broken without a version bump. See
 * {@link ca.northline.contracts.EventContractCheck} and docs/runbooks/events.md § Schema checks.
 */
@NullMarked
package ca.northline.contracts;

import org.jspecify.annotations.NullMarked;
