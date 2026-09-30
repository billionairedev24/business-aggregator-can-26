package ca.northline.merchants.integration;

import ca.northline.email.EmailAddress;
import ca.northline.email.EmailContent;
import ca.northline.email.Mailer;
import ca.northline.merchants.application.IdentityLinkSender;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link IdentityLinkSender} over the S-13 email library ({@code identity-verification} template, Mailpit locally):
 * one email per delivery id, in the requesting owner's language.
 */
@Component
@RequiredArgsConstructor
class IdentityLinkEmails implements IdentityLinkSender {

    private final Mailer mailer;

    @Override
    public void send(String deliveryId, Link link) {
        mailer.send(new Mailer.Delivery(
                deliveryId,
                EmailAddress.of(link.email()),
                new EmailContent.IdentityVerificationLink(
                        link.businessName(), link.requesterName(), link.ownerName(), URI.create(link.url())),
                link.locale(),
                null));
    }
}
