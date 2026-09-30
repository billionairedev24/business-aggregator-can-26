package ca.northline.merchants.application;

import ca.northline.merchants.domain.TeamRules;
import ca.northline.shared.security.MerchantRole;
import java.time.Instant;
import java.util.Locale;

/**
 * Outbound port: delivers a team invitation link. Email invitations are sent (S-13); SMS invitations are not yet — the
 * SMS port lives in northline-auth (S-27) — so the owner shares the link from the invite dialog, which always shows it.
 */
public interface TeamInviteSender {

    /** Whether an invitation to this contact is delivered automatically. */
    boolean delivers(TeamRules.Contact contact);

    /**
     * Sends once per {@code deliveryId} (a retried listener doesn't send twice).
     *
     * @throws RuntimeException when the provider can't take it now (the listener is retried later)
     */
    void send(String deliveryId, Invite invite);

    /** @param locale the inviter's language (the invitee may have no account yet) */
    record Invite(
            TeamRules.Contact contact,
            String businessName,
            String inviterName,
            MerchantRole role,
            String link,
            Instant expiresAt,
            Locale locale) {}
}
