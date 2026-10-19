package ca.northline.merchants.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.onboarding.four-eyes.*} (2026-10-18, docs/runbooks/README.md § Staff roles): two-person review of
 * merchant applications. On by default in every profile; {@code ONBOARDING_FOUR_EYES_ENABLED=false} lets one staff
 * member decide without a review (a single-staff demo), and the api refuses to start that way under {@code prod}.
 */
@ConfigurationProperties("northline.onboarding.four-eyes")
public record FourEyesProperties(@DefaultValue("true") boolean enabled) {}
