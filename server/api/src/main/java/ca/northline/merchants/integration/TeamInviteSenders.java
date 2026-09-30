package ca.northline.merchants.integration;

import ca.northline.email.EmailAddress;
import ca.northline.email.EmailContent;
import ca.northline.email.Mailer;
import ca.northline.merchants.application.TeamInviteSender;
import ca.northline.merchants.domain.TeamRules;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link TeamInviteSender} over the shared email library (S-13): the {@code team-invitation} template in the inviter's
 * language, through whichever provider {@code EMAIL_PROVIDER} selects (Mailpit locally). Mobile-number invitations are
 * not delivered (no SMS from the api; S-27) — the invite dialog shows the link to share.
 */
@Component
@RequiredArgsConstructor
class TeamInviteSenders implements TeamInviteSender {

    private final Mailer mailer;

    @Override
    public boolean delivers(TeamRules.Contact contact) {
        return contact.email() != null;
    }

    @Override
    public void send(String deliveryId, Invite invite) {
        var email = invite.contact().email();
        if (email == null) {
            throw new IllegalArgumentException("Only email invitations are delivered");
        }
        mailer.send(new Mailer.Delivery(
                deliveryId,
                EmailAddress.of(email),
                new EmailContent.TeamInvitation(
                        invite.businessName(),
                        invite.inviterName(),
                        invite.role().code(),
                        URI.create(invite.link()),
                        invite.expiresAt()),
                invite.locale(),
                null));
    }
}
