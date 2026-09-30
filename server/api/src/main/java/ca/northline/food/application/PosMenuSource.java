package ca.northline.food.application;

import ca.northline.food.domain.PosProvider;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-36): a kitchen's menu in its point of sale — Square (Catalog API, the same Square app as the S-35
 * catalogue sync), Clover (REST v3) or Toast (menus API v2, partner access). One adapter per POS, chosen by
 * {@code northline.pos.provider} ({@code local} fakes with a fixture menu, {@code oauth} the real APIs). Adapters
 * normalise the POS's shapes into a {@link PosMenu}: categories → sections, items with prices, modifier lists →
 * groups. Allergens are never read: POS data can't be trusted for them (the kitchen confirms each item).
 */
public interface PosMenuSource {

    PosProvider provider();

    /** Whether the app / partner credentials are configured (otherwise the Studio shows "Not available yet"). */
    boolean available();

    /** OAuth POSes: the consent page. */
    URI authorizationUrl(String state, URI redirectUri);

    /** OAuth POSes: exchanges the callback's code (Clover also sends {@code merchant_id}). */
    Grant exchange(Map<String, String> params, URI redirectUri);

    /**
     * Toast: checks that Northline's partner credentials can read this restaurant (the kitchen enabled the Northline
     * integration in Toast) and returns its name. Throws {@link Unverified} when it can't.
     */
    Grant link(String restaurantId);

    /** A fresh access token when the current one is about to expire; the same credentials otherwise. */
    Credentials refresh(Credentials credentials);

    PosMenu menu(Credentials credentials);

    /** Revokes the grant where the POS allows it. */
    void revoke(Credentials credentials);

    // ── values ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Sealed at rest. {@code account}: Square / Clover merchant id, Toast restaurant GUID (no token for Toast). */
    record Credentials(
            String accessToken,
            @Nullable String refreshToken,
            @Nullable Instant expiresAt,
            String account) {}

    record Grant(Credentials credentials, String accountId, String accountLabel) {}

    /** The POS menu, normalised. Sections keep the POS's order; groups are shared by the items that list them. */
    record PosMenu(List<PosSection> sections, List<PosGroup> groups) {
        public PosMenu {
            sections = List.copyOf(sections);
            groups = List.copyOf(groups);
        }
    }

    record PosSection(String externalId, String name, List<PosItem> items) {
        public PosSection {
            items = List.copyOf(items);
        }
    }

    /**
     * @param priceCents null when the POS prices it at the counter (variable / open price) — can't be imported
     * @param groupIds the item's modifier groups ({@link PosGroup#externalId()}), in order
     */
    record PosItem(
            String externalId,
            String name,
            @Nullable String description,
            @Nullable Long priceCents,
            List<String> groupIds) {
        public PosItem {
            groupIds = List.copyOf(groupIds);
        }
    }

    /** {@code max} null = no upper limit. Option prices are what the option adds to the item (cents). */
    record PosGroup(
            String externalId,
            String name,
            int min,
            @Nullable Integer max,
            List<PosOption> options) {
        public PosGroup {
            options = List.copyOf(options);
        }
    }

    record PosOption(String externalId, String name, long priceDeltaCents) {}

    /** The grant was revoked or refused: the kitchen must reconnect. */
    final class GrantRevoked extends RuntimeException {
        public GrantRevoked(String message) {
            super(message);
        }
    }

    /** The POS refused (Toast restaurant not enabled for Northline, a bad callback). */
    final class Unverified extends RuntimeException {
        public Unverified(String message) {
            super(message);
        }
    }
}
