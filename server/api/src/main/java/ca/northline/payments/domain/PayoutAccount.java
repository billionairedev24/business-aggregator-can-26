package ca.northline.payments.domain;

import ca.northline.payments.api.PayoutAccountChanged;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * A bank account payouts go to (aggregate root). Changing it is a security event (design 02): it needs a fresh passkey,
 * payouts pause for 24 hours, and the owners are emailed and texted when it's requested and when it takes effect.
 * Lifecycle: draft (entered) → pending (confirmed, inside the hold) → active → replaced.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class PayoutAccount {

    public static final Duration CHANGE_HOLD = Duration.ofHours(24);

    public enum Method implements CodedEnum {
        /** Stripe Financial Connections: the owner signs in to their bank, no numbers typed. */
        INSTANT,
        MANUAL
    }

    public enum State implements CodedEnum {
        DRAFT,
        PENDING,
        ACTIVE,
        REPLACED,
        DISCARDED
    }

    @EqualsAndHashCode.Include
    @ToString.Include
    private final String id;

    private final String merchantId;
    private final Method method;
    private final String institutionName;
    private final @Nullable String institutionNumber;
    private final @Nullable String transitNumber;
    private final String last4;
    private final String holderName;
    private final String externalRef;
    /** Financial Connections account ({@code fca_…}) an instant link came from; null for typed details. */
    private final @Nullable String financialConnectionsAccount;

    @ToString.Include
    private State state;

    private final Instant createdAt;
    private final String createdBy;
    private @Nullable Instant confirmedAt;
    private @Nullable Instant effectiveAt;
    private @Nullable Instant replacedAt;
    /** Stripe says the bank connection ended (the owner revoked it at their bank, or it expired). */
    private @Nullable Instant disconnectedAt;

    private final @Nullable Integer version;

    public static PayoutAccount draft(
            String merchantId,
            Method method,
            String institutionName,
            @Nullable String institutionNumber,
            @Nullable String transitNumber,
            String last4,
            String holderName,
            String externalRef,
            @Nullable String financialConnectionsAccount,
            String createdBy,
            Instant now) {
        return PayoutAccount.builder()
                .id(Ids.next())
                .merchantId(merchantId)
                .method(method)
                .institutionName(institutionName)
                .institutionNumber(institutionNumber)
                .transitNumber(transitNumber)
                .last4(last4)
                .holderName(holderName)
                .externalRef(externalRef)
                .financialConnectionsAccount(financialConnectionsAccount)
                .state(State.DRAFT)
                .createdAt(now)
                .createdBy(createdBy)
                .build();
    }

    /** "TD ··3391" — what payout rows and receipts show. */
    public String label() {
        return shortName() + " ··" + last4;
    }

    private String shortName() {
        return institutionName.equals("TD Canada Trust") ? "TD" : institutionName;
    }

    /** The owner confirmed with a fresh passkey: the new account takes over after the 24 h hold. */
    public PayoutAccountChanged confirm(Instant now) {
        if (state != State.DRAFT) {
            throw new Conflict(
                    "payout_account_not_draft", "This bank account change was already confirmed or cancelled.");
        }
        state = State.PENDING;
        confirmedAt = now;
        effectiveAt = now.plus(CHANGE_HOLD);
        return new PayoutAccountChanged(Ids.next(), now, id, merchantId, "requested", effectiveAt);
    }

    public boolean due(Instant now) {
        return state == State.PENDING && effectiveAt != null && !effectiveAt.isAfter(now);
    }

    /** The hold is over: payouts go here from now on. */
    public PayoutAccountChanged activate(Instant now) {
        if (!due(now)) {
            throw new Conflict("payout_account_not_due", "This bank account isn't due to take over yet.");
        }
        state = State.ACTIVE;
        return new PayoutAccountChanged(Ids.next(), now, id, merchantId, "effective", now);
    }

    public void replace(Instant now) {
        state = State.REPLACED;
        replacedAt = now;
    }

    /**
     * The Financial Connections link ended ({@code financial_connections.account.disconnected}). The bank account stays
     * the connected account's external account, so payouts keep going to it; the Studio asks the owner to reconnect.
     * False when it was already recorded.
     */
    public boolean connectionEnded(Instant at) {
        if (disconnectedAt != null || financialConnectionsAccount == null) {
            return false;
        }
        disconnectedAt = at;
        return true;
    }

    public void discard() {
        if (state == State.DRAFT || state == State.PENDING) {
            state = State.DISCARDED;
        }
    }
}
