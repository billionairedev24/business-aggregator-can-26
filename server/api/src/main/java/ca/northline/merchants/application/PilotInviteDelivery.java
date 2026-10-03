package ca.northline.merchants.application;

import ca.northline.identity.api.NotificationContacts;
import ca.northline.merchants.domain.PilotInvite;
import ca.northline.region.api.MarketProfile;
import ca.northline.region.api.Regions;
import java.time.Clock;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Sends a pilot invite after the invite committed (S-120): async, in its own transaction, retried from the event
 * registry while email is down — the staff member's action never waits for it, and the console shows the link too. An
 * invite withdrawn, used or expired before the listener runs is not sent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PilotInviteDelivery {

    private final PilotStore pilots;
    private final PilotInviteSender sender;
    private final NotificationContacts contacts;
    private final StudioLinks links;
    private final Regions regions;
    private final Clock clock;

    @ApplicationModuleListener
    void on(PilotInviteIssued event) {
        var invite = pilots.invites(event.pilotId()).stream()
                .filter(i -> i.id().equals(event.inviteId()))
                .findFirst()
                .orElse(null);
        if (invite == null || invite.state(clock.instant()) != PilotInvite.State.PENDING) {
            log.info("Pilot invite {} is no longer pending; not sent", event.inviteId());
            return;
        }
        var pilot = pilots.pilot(event.pilotId()).orElse(null);
        if (pilot == null) {
            return;
        }
        var city = regions.marketById(pilot.marketId()).map(MarketProfile::city).orElse("");
        var inviter = contacts.contact(event.inviterId())
                .map(NotificationContacts.Contact::displayName)
                .orElse("");
        sender.send(
                event.eventId(),
                new PilotInviteSender.Invite(
                        invite.email(),
                        pilot.label(),
                        city,
                        pilot.businessType(),
                        inviter,
                        links.pilotInvite(event.token()),
                        invite.expiresAt(),
                        "fr".equals(event.language()) ? Locale.CANADA_FRENCH : Locale.CANADA));
    }
}
