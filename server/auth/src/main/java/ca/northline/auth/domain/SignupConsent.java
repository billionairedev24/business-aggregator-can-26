package ca.northline.auth.domain;

import java.io.Serializable;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * S-157: a box the person ticked on a sign-up form — express consent to Northline's commercial messages on one channel
 * (CASL), kept with the account while it is pending and written to {@code messaging.consent_records} when the account
 * is created. Minimised like every consent: the IP address truncated, the user agent hashed.
 *
 * @param category {@code marketing_email} | {@code marketing_sms}
 * @param wordingVersion the wording the form showed (the shared catalogue's current one for the app)
 * @param language {@code en} | {@code fr}, the language the wording was shown in
 * @param at when the form was sent
 */
public record SignupConsent(
        String category,
        String wordingVersion,
        String language,
        Instant at,
        @Nullable String ipPrefix,
        @Nullable String userAgentHash)
        implements Serializable {}
