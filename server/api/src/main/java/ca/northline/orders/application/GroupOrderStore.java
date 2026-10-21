package ca.northline.orders.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.With;
import org.jspecify.annotations.Nullable;

/** Outbound port (S-159): {@code orders.group_orders}, {@code group_order_items}, {@code group_order_shares}. */
public interface GroupOrderStore {

    /** @return false when the code is taken (the caller draws another) */
    boolean insert(GroupRow row);

    Optional<GroupRow> find(String id);

    /** The row locked for this transaction ({@code for update}): every change to a group goes through it. */
    Optional<GroupRow> lock(String id);

    Optional<GroupRow> byCode(String code);

    /** Open, locked or paying groups the person is in, newest first. */
    List<GroupRow> active(String userId);

    /** Ids of active groups created before {@code before} (the expiry job). */
    List<String> staleSince(Instant before);

    void update(GroupRow row);

    void addMember(String groupId, String userId);

    void removeMember(String groupId, String userId);

    /** Sets {@code orders.orders.group_order_id} of the placed order (the kitchen display counts the group). */
    void linkOrder(String groupId, String orderId);

    void insertItem(ItemRow row);

    List<ItemRow> items(String groupId);

    boolean deleteItem(String groupId, String itemId);

    void deleteItemsOf(String groupId, String userId);

    void insertShare(ShareRow row);

    List<ShareRow> shares(String groupId);

    void updateShare(ShareRow row);

    void deleteShares(String groupId);

    /** The split shares of a placed order other than the host's (whose id is the order's). */
    List<ShareRow> guestSharesOfOrder(String orderId);

    /**
     * @param members every member, the host first
     * @param delivery the host's address snapshot as JSON (FoodCheckout.Delivery), null for pickup
     */
    @With
    record GroupRow(
            String id,
            String code,
            String hostId,
            String merchantId,
            String kitchenName,
            @Nullable String kitchenSlug,
            int groupMax,
            List<String> members,
            String state,
            String payment,
            String mode,
            @Nullable Instant scheduledFor,
            @Nullable String delivery,
            String tipKind,
            long tipValue,
            @Nullable String orderId,
            @Nullable String orderRef,
            Instant createdAt,
            @Nullable Instant locksAt,
            @Nullable Instant placedAt,
            @Nullable Instant closedAt) {
        public GroupRow {
            members = List.copyOf(members);
        }

        public boolean member(String userId) {
            return members.contains(userId);
        }
    }

    /** @param choices the chosen options' names as JSON (a display snapshot) */
    record ItemRow(
            String id,
            String groupId,
            String userId,
            @Nullable String menuItemId,
            @Nullable String comboId,
            int qty,
            List<String> optionIds,
            List<String> comboItemIds,
            @Nullable String note,
            String title,
            long unitCents,
            long totalCents,
            String choices,
            @Nullable String ageClass,
            Instant addedAt) {
        public ItemRow {
            optionIds = List.copyOf(optionIds);
            comboItemIds = List.copyOf(comboItemIds);
        }
    }

    @With
    record ShareRow(
            String id,
            String groupId,
            String userId,
            @Nullable String paidBy,
            String state,
            long subtotalCents,
            long deliveryFeeCents,
            long serviceFeeCents,
            long feeTaxCents,
            long taxCents,
            long tipCents,
            @Nullable String taxCalculationId,
            @Nullable String paymentIntent,
            @Nullable Instant authorizedAt) {

        public long totalCents() {
            return subtotalCents + deliveryFeeCents + serviceFeeCents + feeTaxCents + taxCents + tipCents;
        }

        public long platformCents() {
            return deliveryFeeCents + serviceFeeCents + feeTaxCents + tipCents;
        }
    }
}
