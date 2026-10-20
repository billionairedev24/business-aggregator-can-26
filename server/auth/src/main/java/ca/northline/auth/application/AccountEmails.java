package ca.northline.auth.application;

import ca.northline.auth.domain.App;

/**
 * Outbound port (S-154): the account emails northline-auth sends — the code + link that confirm a new address, the
 * code + link that reset a password, and "you already have an account" (instead of a code, so the form never reveals
 * that an address is registered). Sent after the transaction commits and off the request thread, so the answer's
 * timing doesn't depend on whether an email went out.
 */
public interface AccountEmails {

    /**
     * @param to the address
     * @param locale the person's language ({@code en-CA} | {@code fr-CA})
     * @param key de-duplication key (one email per key)
     */
    record Recipient(String to, String firstName, String locale, App app, String key) {}

    void verification(Recipient to, String code, String token, long validMinutes);

    void passwordReset(Recipient to, String code, String token, long validMinutes);

    void accountExists(Recipient to);
}
