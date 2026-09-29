package ca.northline.auth.application;

import ca.northline.auth.domain.Factor;
import java.time.Instant;

/**
 * Outbound port: signs a step-up proof — a short-lived JWT the api accepts in {@code X-Step-Up} for payouts and bank
 * account changes ("Payouts always require a fresh authentication", design 02). Audience
 * {@code northline-api/step-up}, so it can never be used as an access token.
 */
public interface StepUpProofs {

    record Proof(String token, Instant expiresAt) {}

    Proof issue(String userId, Factor factor, Instant authTime);
}
