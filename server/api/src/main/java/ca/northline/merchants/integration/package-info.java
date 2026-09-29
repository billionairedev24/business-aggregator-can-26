/**
 * Adapters for the external systems behind the onboarding checklist and the page builder (KYC, registries, bank
 * linking, DNS, document storage). Only local fakes exist so far; they are active under the {@code local} and
 * {@code test} profiles. Production adapters implement the same {@code merchants.application} ports.
 */
@NullMarked
package ca.northline.merchants.integration;

import org.jspecify.annotations.NullMarked;
