package ca.northline.merchants.integration;

import ca.northline.email.EmailAddress;
import ca.northline.email.EmailContent;
import ca.northline.email.EmailFormat;
import ca.northline.email.Mailer;
import ca.northline.merchants.application.TeamInviteSender;
import ca.northline.merchants.domain.TeamRules;
import ca.northline.shared.security.MerchantRole;
import ca.northline.sms.PhoneNumbers;
import ca.northline.sms.SmsDeliveryFailed;
import ca.northline.sms.SmsTransport;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TeamInviteSender} over the shared libraries: email invitations with the {@code team-invitation} template
 * (S-13, {@code server/email}), mobile invitations by SMS (S-27, {@code server/sms}) — both in the inviter's language,
 * through whichever providers {@code EMAIL_PROVIDER} / {@code SMS_PROVIDER} select (Mailpit / the log locally).
 *
 * <p>The api sends invitation SMS itself, not the worker: the link's token exists only in the api (its hash is stored)
 * and must never travel through Kafka (docs/DECISIONS.md § S-27). Each SMS is claimed once per delivery in
 * {@code events.processed_events} ({@code sms}, the event id) before it is sent, like the Mailer does for email: an
 * unreachable number keeps the claim (not retried), an unavailable provider releases it and throws so the listener is
 * resubmitted later.
 */
@Slf4j
@Component
class TeamInviteSenders implements TeamInviteSender {

    private static final String SMS_CLAIMS = "sms";

    private final Mailer mailer;
    private final SmsTransport sms;
    private final JdbcClient jdbc;
    private final TransactionTemplate separately;

    TeamInviteSenders(Mailer mailer, SmsTransport sms, JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.mailer = mailer;
        this.sms = sms;
        this.jdbc = jdbc;
        this.separately = new TransactionTemplate(transactions);
        this.separately.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean delivers(TeamRules.Contact contact) {
        return contact.email() != null || contact.phone() != null;
    }

    @Override
    public void send(String deliveryId, Invite invite) {
        var email = invite.contact().email();
        if (email != null) {
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
            return;
        }
        var phone = invite.contact().phone();
        if (phone == null || !PhoneNumbers.isE164(phone)) {
            throw new IllegalArgumentException("An invitation needs an email address or an E.164 mobile number");
        }
        if (!claim(deliveryId)) {
            log.debug("Invitation SMS {} already sent", deliveryId);
            return;
        }
        try {
            sms.sendText(phone, text(invite));
            log.info("Invitation SMS sent to {} ({})", PhoneNumbers.masked(phone), deliveryId);
        } catch (SmsDeliveryFailed e) {
            if (e.getKind() == SmsDeliveryFailed.Kind.UNDELIVERABLE_NUMBER) {
                log.warn(
                        "Invitation SMS to {} refused for good ({}): {}",
                        PhoneNumbers.masked(phone),
                        deliveryId,
                        e.getMessage());
                return; // the owner still has the link to share
            }
            release(deliveryId);
            throw e;
        } catch (RuntimeException e) {
            release(deliveryId);
            throw e;
        }
    }

    /** One text in the inviter's language; the link is the whole point, so it comes last and unbroken. */
    static String text(Invite invite) {
        var french = "fr".equals(invite.locale().getLanguage());
        var format = EmailFormat.of(invite.locale());
        var role = (french ? ROLES_FR : ROLES_EN)
                .getOrDefault(invite.role(), invite.role().code());
        var inviter = invite.inviterName().isBlank() ? (french ? "Une équipe" : "A team") : invite.inviterName();
        return french
                ? "Northline : %s vous invite à rejoindre %s comme %s. Acceptez d’ici le %s : %s"
                        .formatted(
                                inviter,
                                invite.businessName(),
                                role.toLowerCase(Locale.CANADA_FRENCH),
                                format.date(invite.expiresAt()),
                                invite.link())
                : "Northline: %s invited you to join %s as %s. Accept by %s: %s"
                        .formatted(
                                inviter,
                                invite.businessName(),
                                role.toLowerCase(Locale.CANADA),
                                format.date(invite.expiresAt()),
                                invite.link());
    }

    private static final Map<MerchantRole, String> ROLES_EN = Map.of(
            MerchantRole.OWNER, "Owner",
            MerchantRole.TECHNICIAN, "Technician",
            MerchantRole.COOK, "Cook",
            MerchantRole.BOOKKEEPER, "Bookkeeper");
    private static final Map<MerchantRole, String> ROLES_FR = Map.of(
            MerchantRole.OWNER, "Propriétaire",
            MerchantRole.TECHNICIAN, "Technicien",
            MerchantRole.COOK, "Cuisinier",
            MerchantRole.BOOKKEEPER, "Comptable");

    private boolean claim(String key) {
        return Boolean.TRUE.equals(separately.execute(
                _ -> jdbc.sql("""
                                insert into events.processed_events (consumer, event_id) values (:c, :k)
                                on conflict do nothing""").param("c", SMS_CLAIMS).param("k", key).update() == 1));
    }

    private void release(String key) {
        separately.executeWithoutResult(
                _ -> jdbc.sql("delete from events.processed_events where consumer = :c and event_id = :k")
                        .param("c", SMS_CLAIMS)
                        .param("k", key)
                        .update());
    }
}
