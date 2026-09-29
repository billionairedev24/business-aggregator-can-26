package ca.northline.merchants.application;

import ca.northline.merchants.domain.TeamRules;
import ca.northline.shared.security.MerchantRole;

/**
 * Outbound port: delivers a team invitation link by email or SMS. {@code local}/{@code test} log it; until a
 * production email/SMS provider is chosen the owner copies the link from the invite dialog (docs/DECISIONS.md).
 */
public interface TeamInviteSender {

    void send(Invite invite);

    record Invite(TeamRules.Contact contact, String businessName, String inviterName, MerchantRole role, String link) {}
}
