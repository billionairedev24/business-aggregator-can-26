package ca.northline.merchants.integration;

import ca.northline.email.EmailAddress;
import ca.northline.email.EmailContent;
import ca.northline.email.Mailer;
import ca.northline.merchants.application.PilotInviteSender;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link PilotInviteSender} over the S-13 email library ({@code pilot-invitation} template; Mailpit locally): one email
 * per delivery id, in the language the staff member chose.
 */
@Component
@RequiredArgsConstructor
class PilotInviteEmails implements PilotInviteSender {

    private final Mailer mailer;

    @Override
    public void send(String deliveryId, Invite invite) {
        mailer.send(new Mailer.Delivery(
                deliveryId,
                EmailAddress.of(invite.email()),
                new EmailContent.PilotInvitation(
                        invite.label(),
                        invite.inviterName(),
                        invite.city(),
                        invite.businessType(),
                        URI.create(invite.link()),
                        invite.expiresAt()),
                invite.locale(),
                null));
    }
}
