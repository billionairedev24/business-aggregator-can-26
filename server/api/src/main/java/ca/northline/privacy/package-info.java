/**
 * Privacy rights (S-105): people's access, correction and erasure requests under the privacy law of their province
 * (PIPEDA, Alberta PIPA, BC PIPA, Québec Law 25 — region configuration, {@code region.api.PrivacyRegimes}). Owns
 * schema {@code privacy}: the requests with their state, verification and SLA clock, and the erasure pipeline's steps.
 * Every module that keeps personal data takes part through {@link ca.northline.shared.privacy.PersonalDataContributor};
 * this module never reads another module's tables. Runbook: docs/runbooks/privacy-requests.md.
 */
@ApplicationModule(displayName = "privacy")
@NullMarked
package ca.northline.privacy;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
