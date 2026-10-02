package ca.northline.worker.push;

/**
 * An installation that can get a push: notifications allowed and a token ({@code messaging.push_devices}).
 *
 * @param platform {@code ios} (APNs) | {@code android} (FCM)
 * @param language {@code en} | {@code fr}: the app's language on that phone
 */
public record PushDevice(String id, String userId, String app, String platform, String token, String language) {

    /** The token's last characters, for logs (never the token). */
    public String hint() {
        return "…" + token.substring(Math.max(0, token.length() - 6));
    }
}
