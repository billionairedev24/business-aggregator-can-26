package ca.northline.merchants.application;

import ca.northline.merchants.domain.MerchantType;
import ca.northline.merchants.domain.Province;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * S-120: the business's side of a pilot invite — the Studio previews the link (business type and market pre-filled)
 * and the Account step accepts it when it creates the business, which marks the business as a pilot participant.
 */
public interface PilotInviteLinks {

    String TYPE_MISMATCH = "This invite is for another kind of business.";
    String PROVINCE_MISMATCH = "This invite is for a business in another province.";

    /**
     * @param state {@code pending|expired|accepted|revoked}
     * @param city the pilot market's city
     */
    record PilotInvitePreview(
            String businessType,
            String label,
            String marketId,
            String city,
            String province,
            Instant expiresAt,
            String state) {}

    PilotInvitePreview preview(String token);

    /** Called inside the Account step's transaction once the business exists; refuses an unusable invite. */
    void accept(String token, String merchantId, String userId, MerchantType type, Province province);

    /** Checks the invite before the business is created (the same refusals as {@link #accept}). */
    void check(@Nullable String token, MerchantType type, Province province);
}
