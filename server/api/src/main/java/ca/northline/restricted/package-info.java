/**
 * Age-restricted purchases (owner decision 2026-10-04: "age should be based on items customer is purchasing"): the
 * customer's one-time age check through the identity provider port (Stripe Identity document + selfie; a fake under
 * {@code local}/{@code test}), kept only as "verified over N, on date, by method"; the ID checks at handoff (courier at
 * the door, business at the counter) and their audit trail; the trust &amp; safety report. Owns schema
 * {@code restricted}. The ages and windows are the region model's ({@code region.api.AgeRules}); the classes are the
 * taxonomy's; licences are the merchants module's. Runbook: docs/runbooks/age-restricted.md.
 */
@ApplicationModule(displayName = "restricted")
@NullMarked
package ca.northline.restricted;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
