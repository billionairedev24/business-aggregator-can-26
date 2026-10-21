package ca.northline.privacy.application;

import java.time.Instant;
import java.util.Locale;

/**
 * Outbound port (S-159): the privacy emails — a request's verification code for an account without a verified mobile,
 * and the account deletion's confirmations. Each is sent at most once per {@code key} (the email library's
 * de-duplication), so a retried transaction or pipeline run never sends it twice.
 */
public interface PrivacyEmails {

    void code(String key, String email, String firstName, String code, long validMinutes, Locale locale);

    /** The deletion is confirmed and starts at {@code startsAt}; the person can cancel it until then. */
    void deletionRequested(String key, String email, String firstName, Instant startsAt, Locale locale);

    /** The erasure finished (sent to the address the account had when the request was made). */
    void deletionCompleted(String key, String email, Locale locale);
}
