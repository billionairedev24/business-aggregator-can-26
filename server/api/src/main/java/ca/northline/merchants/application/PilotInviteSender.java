package ca.northline.merchants.application;

import java.time.Instant;
import java.util.Locale;

/** Outbound port (S-120): emails a pilot invite link (the {@code pilot-invitation} template). */
public interface PilotInviteSender {

    /** Sends once per {@code deliveryId}. */
    void send(String deliveryId, Invite invite);

    /** @param businessType {@code provider|seller|kitchen|both} */
    record Invite(
            String email,
            String label,
            String city,
            String businessType,
            String inviterName,
            String link,
            Instant expiresAt,
            Locale locale) {}
}
