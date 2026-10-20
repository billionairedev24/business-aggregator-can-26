package ca.northline.orders.application;

import ca.northline.orders.application.FoodCheckout.Tracking;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Group food orders (S-159, design 06's group order on S-57's food checkout): a host starts one at a kitchen and shares
 * its code or link; signed-in guests add their dishes to the shared cart until the host locks it; then either the host
 * pays for everything (S-57's checkout, unchanged) or each person pays their own share (one card payment and escrow
 * hold each). Either way the kitchen gets one ticket and the order one delivery. Use cases, commands and read models.
 */
public final class GroupOrders {
    private GroupOrders() {}

    public interface StartGroupOrder {
        GroupView start(String hostId, Start start);
    }

    public interface JoinGroupOrder {
        /** The join page before joining: the kitchen and the host's first name only. 404 for an unknown code. */
        Preview preview(String code);

        /** Idempotent for a member; 409 {@code group_full} / {@code group_locked} / {@code group_closed}. */
        GroupView join(String userId, String code);
    }

    public interface GroupCart {
        GroupView add(String userId, String groupId, Item item);

        /** One's own dish (the host may take out anyone's). */
        GroupView remove(String userId, String groupId, String itemId);

        /** A guest leaves with their dishes (the host cancels instead). */
        GroupView leave(String userId, String groupId);
    }

    public interface HostGroupOrder {
        /** Closes the cart. Split payment: prices every person's share (the host's carries {@code tip}). */
        GroupView lock(String hostId, String groupId, FoodCheckout.Tip tip);

        /** Opens the cart again while nobody has paid. */
        GroupView unlock(String hostId, String groupId);

        /** Before the kitchen has it: every authorized share is released to its card. 409 once placed. */
        GroupView cancel(String hostId, String groupId);

        /** Split payment, every share authorized: the order goes to the kitchen (one ticket, {@code order.placed}). */
        GroupView place(String hostId, String groupId);
    }

    public interface PayGroupOrder {
        /** The host pays for everyone: S-57's food checkout with every person's dishes (same rules and answer). */
        FoodCheckout.Started checkout(
                String hostId,
                boolean mfa,
                @Nullable String stepUpProof,
                String groupId,
                FoodCheckout.@Nullable Tip tip,
                String clientKey,
                Locale locale);

        /** The host's card is authorized: the order is placed (S-57's confirm). */
        GroupView confirm(String hostId, String groupId);

        /** Split: opens the card payment of {@code forUserId}'s share — one's own, or the host paying for a guest. */
        ShareStarted payShare(
                String payerId,
                boolean mfa,
                @Nullable String stepUpProof,
                String groupId,
                String forUserId,
                String clientKey);

        /** Split: the share's card is authorized — its escrow hold is recorded. */
        GroupView confirmShare(String payerId, String groupId, String forUserId);
    }

    public interface ViewGroupOrder {
        /** A member's view (someone else gets 404). */
        GroupView view(String userId, String groupId);

        /** The caller's groups still waiting (open, locked, paying), newest first. */
        List<GroupView> active(String userId);
    }

    /** {@code payment}: {@code host} | {@code split}; delivery as S-57's checkout ({@code null} for pickup). */
    public record Start(
            String merchantId,
            String mode,
            @Nullable Instant scheduledFor,
            FoodCheckout.@Nullable Delivery delivery,
            String payment) {}

    /** A dish ({@code itemId} + {@code optionIds}) or a combo ({@code comboId} + {@code itemIds}). */
    public record Item(
            @Nullable String itemId,
            @Nullable String comboId,
            int qty,
            List<String> optionIds,
            List<String> itemIds,
            @Nullable String note) {
        public Item {
            optionIds = List.copyOf(optionIds);
            itemIds = List.copyOf(itemIds);
        }
    }

    public record Preview(
            String code,
            String kitchen,
            @Nullable String kitchenSlug,
            String hostFirstName,
            String state,
            int people,
            int groupMax) {}

    /**
     * @param area where it goes: the delivery address's zone or city (guests don't see the host's street), null for
     *     pickup
     * @param you the caller's user id; {@code host} = the caller is the host
     * @param subtotalCents every person's dishes as added (the order is priced again when it is paid)
     * @param canPlace split payment: every share is authorized and the host may send it to the kitchen
     * @param tracking once placed: S-57's tracking of the one order
     * @param expiresAt an unfinished group closes then
     */
    public record GroupView(
            String id,
            String code,
            String state,
            String payment,
            String merchantId,
            String kitchen,
            @Nullable String kitchenSlug,
            String mode,
            @Nullable Instant scheduledFor,
            @Nullable String area,
            String hostId,
            String you,
            boolean host,
            List<Member> members,
            long subtotalCents,
            int groupMax,
            FoodCheckout.Tip tip,
            @Nullable String orderId,
            @Nullable String ref,
            boolean canPlace,
            @Nullable Tracking tracking,
            Instant createdAt,
            Instant expiresAt) {
        public GroupView {
            members = List.copyOf(members);
        }
    }

    /** One person in the group ({@code name} as "Amara O."), their dishes and, with split payment, their share. */
    public record Member(
            String userId,
            String name,
            boolean host,
            List<ItemView> items,
            long subtotalCents,
            @Nullable Share share) {
        public Member {
            items = List.copyOf(items);
        }
    }

    public record ItemView(
            String id,
            @Nullable String itemId,
            @Nullable String comboId,
            String title,
            int qty,
            long unitCents,
            long totalCents,
            List<String> choices,
            @Nullable String note) {
        public ItemView {
            choices = List.copyOf(choices);
        }
    }

    /**
     * A split bill's share. {@code state}: due · paying (the card payment is open) · authorized (paid: held until the
     * hand-off) · voided (cancelled). {@code paidBy}: the payer's name when it isn't the person.
     */
    public record Share(
            long subtotalCents,
            long deliveryFeeCents,
            long serviceFeeCents,
            long feeTaxCents,
            long taxCents,
            long tipCents,
            long totalCents,
            String state,
            @Nullable String paidBy) {}

    /** The share's card payment to confirm with Stripe.js / the native sheet (S-57's {@code Started} shape). */
    public record ShareStarted(
            String shareId,
            long totalCents,
            String paymentIntent,
            @Nullable String clientSecret,
            String status,
            String mode,
            @Nullable String publishableKey) {}
}
