/**
 * Go-live (S-118): per market, the go-live checklist (every gate the platform can check by itself, and the manual ones
 * staff record with their evidence), the two-person switch from {@code pilot} to {@code live} (one admin requests, a
 * second approves; an audited emergency override), the rollback to {@code pilot}, and the 14-day hypercare rota on top
 * of the on-call rota. Owns schema {@code golive}; the stage itself is the region model's ({@code region.api}).
 * Runbook: docs/runbooks/go-live.md.
 */
@ApplicationModule(displayName = "golive")
@NullMarked
package ca.northline.golive;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
