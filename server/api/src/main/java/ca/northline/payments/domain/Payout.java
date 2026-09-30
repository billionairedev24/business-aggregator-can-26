package ca.northline.payments.domain;

import ca.northline.payments.api.PayoutSent;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.Ids;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/** Money leaving a merchant's balance for their bank account (aggregate root). */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Payout {

    public enum Kind implements CodedEnum {
        SCHEDULED,
        INSTANT
    }

    /** Stripe payout states. */
    public enum State implements CodedEnum {
        PENDING,
        IN_TRANSIT,
        PAID,
        FAILED,
        CANCELED
    }

    @EqualsAndHashCode.Include
    @ToString.Include
    private final String id;

    private final String merchantId;
    private final @Nullable String stripePayout;
    private final long amountCents;
    private final Kind kind;
    private final long feeCents;

    @ToString.Include
    private State state;

    private final Instant arrivesAt;
    private final Instant createdAt;
    private final int itemCount;
    private final @Nullable String payoutAccountId;
    private final @Nullable String destination;
    private final @Nullable String requestedBy;
    private @Nullable String stripeFeeTransfer;
    private final @Nullable Integer version;

    /** What reaches the bank. */
    public long netCents() {
        return amountCents - feeCents;
    }

    /** A payout Stripe accepted ({@code stripePayout} = po_…). */
    public static Payout sent(
            Kind kind,
            String merchantId,
            long amountCents,
            long feeCents,
            String stripePayout,
            Instant arrivesAt,
            int itemCount,
            PayoutAccount account,
            @Nullable String requestedBy,
            Instant now) {
        return Payout.builder()
                .id(Ids.next())
                .merchantId(merchantId)
                .stripePayout(stripePayout)
                .amountCents(amountCents)
                .kind(kind)
                .feeCents(feeCents)
                .state(State.IN_TRANSIT)
                .arrivesAt(arrivesAt)
                .createdAt(now)
                .itemCount(itemCount)
                .payoutAccountId(account.getId())
                .destination(account.label())
                .requestedBy(requestedBy)
                .build();
    }

    public PayoutSent sentEvent() {
        return new PayoutSent(Ids.next(), createdAt, id, merchantId, kind.code(), amountCents, feeCents, arrivesAt);
    }

    /** Instant payouts: the fee was moved from the connected account to the platform ({@code tr_…}). */
    public void feeRecovered(String stripeTransfer) {
        stripeFeeTransfer = stripeTransfer;
    }

    /** Arrived at the bank (Stripe {@code payout.paid}; the fake gateway: at {@code arrivesAt}). */
    public boolean settle(Instant now) {
        if (state == State.IN_TRANSIT && !arrivesAt.isAfter(now)) {
            state = State.PAID;
            return true;
        }
        return false;
    }
}
