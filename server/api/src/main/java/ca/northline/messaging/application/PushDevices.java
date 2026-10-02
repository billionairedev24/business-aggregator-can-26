package ca.northline.messaging.application;

import ca.northline.messaging.domain.PushDeviceRules.App;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The push device registry (S-102): the consumer and courier apps register, refresh and remove their installation's
 * APNs / FCM token. Inbound port and the outbound store.
 */
public final class PushDevices {
    private PushDevices() {}

    /**
     * One installation's state, as the app reports it at every start and whenever the token or the permission changes.
     *
     * @param token null while notifications aren't allowed (iOS gives no token before the person agrees)
     * @param locale {@code en-CA} | {@code fr-CA}: the app's language ("same as app" notifications use it)
     */
    public record Registration(
            String userId,
            App app,
            String installationId,
            String platform,
            @Nullable String token,
            String locale,
            String appVersion,
            String permission) {}

    /** What the registry holds for the caller's installation (never the token: the app has it). */
    public record Device(
            String installationId, App app, String platform, String locale, String permission, Instant refreshedAt) {}

    public interface ManagePushDevices {

        /** Registers or refreshes; a token another person's installation held moves to this one. */
        Device register(Registration registration);

        /** Sign-out: the installation stops getting this person's notifications. False when it wasn't theirs. */
        boolean remove(String userId, App app, String installationId);
    }

    /** Outbound port: {@code messaging.push_devices} (V245). */
    public interface PushDeviceStore {

        /** Deletes any other row holding this token (another person or installation), then upserts this one. */
        Device save(Registration registration, String newId, Instant now);

        boolean delete(String userId, App app, String installationId);
    }
}
