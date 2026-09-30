package ca.northline.merchants.application;

import ca.northline.merchants.domain.TeamRules;
import ca.northline.shared.security.MerchantRole;
import java.time.Instant;
import java.util.Locale;

/**
 * Outbound port: delivers a team invitation link — by email (S-13) or, for a mobile number, by SMS (S-27). The invite
 * dialog still shows the link, so the owner can share it another way too.
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
