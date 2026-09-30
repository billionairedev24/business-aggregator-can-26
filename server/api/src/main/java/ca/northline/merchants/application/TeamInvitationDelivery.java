package ca.northline.merchants.application;

import ca.northline.identity.api.NotificationContacts;
import ca.northline.merchants.domain.TeamInvitation;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Sends a team invitation after the invite committed (S-13): async, in its own transaction, retried from the event
 * registry when the provider is down — the owner's action never waits for or fails on email. An invitation withdrawn,
 * accepted or expired before the listener runs is not sent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TeamInvitationDelivery {

    private final TeamStore team;
    private final MerchantRepository merchants;
    private final NotificationContacts contacts;
    private final TeamInviteSender sender;
    private final StudioLinks links;
    private final Clock clock;

    @ApplicationModuleListener
    void on(TeamInvitationIssued event) {
        var invitation =
                team.invitation(event.merchantId(), event.invitationId()).orElse(null);
        if (invitation == null || invitation.state(clock.instant()) != TeamInvitation.State.PENDING) {
            log.info("Invitation {} is no longer pending; not sent", event.invitationId());
            return;
        }
        if (!sender.delivers(invitation.contact())) {
            log.info("Invitation {} goes to a mobile number: the owner shares the link (SMS: S-27)", invitation.id());
            return;
        }
        var merchant = merchants.findById(event.merchantId()).orElse(null);
        if (merchant == null) {
            return;
        }
        var inviter = contacts.contact(event.inviterId());
        sender.send(
                event.eventId(),
                new TeamInviteSender.Invite(
                        invitation.contact(),
                        merchant.getDisplayName().value(),
                        inviter.map(NotificationContacts.Contact::displayName).orElse(""),
                        invitation.role(),
                        links.invitation(event.token()),
                        invitation.expiresAt(),
                        inviter.map(NotificationContacts.Contact::locale).orElse(java.util.Locale.CANADA)));
    }
}
