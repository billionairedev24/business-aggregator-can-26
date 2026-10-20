package ca.northline.loyalty.application;

import ca.northline.loyalty.domain.Earning;
import ca.northline.payments.api.EscrowReleased;
import ca.northline.payments.api.RefundIssued;
import ca.northline.payments.api.RewardCharges;
import ca.northline.payments.api.SaleFacts;
import ca.northline.region.api.LoyaltyRules;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.trust.api.PointsLedger;
import ca.northline.trust.api.RewardCredits;
import java.time.Clock;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-152 earning. A sale earns when its money is released ({@link EscrowReleased}: after the escrow window, so a sale
 * that is cancelled or refunded in full before then never earns): points per whole dollar of what the customer paid
 * the business, by the rule of the business's market or province, × Plus's multiplier for members; a running
 * provider-funded reward adds its extra points, which payments charges to the business. A refund charged to the
 * business afterwards ({@link RefundIssued}) takes back its share of both (a goodwill credit from Northline doesn't).
 * Everything for one escrow happens in the listener's transaction and once (the earning row is its key).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class EarningService {

    private final SaleFacts sales;
    private final MerchantPlaces places;
    private final LoyaltyRules rules;
    private final PointsLedger ledger;
    private final RewardCredits rewards;
    private final RewardCharges charges;
    private final MembershipService plus;
    private final ReferralService referrals;
    private final LoyaltyStore store;
    private final Clock clock;

    @ApplicationModuleListener
    void on(EscrowReleased e) {
        earn(e.aggregateId());
    }

    @ApplicationModuleListener
    void on(RefundIssued e) {
        var escrow = e.escrowId();
        if (escrow != null && "merchant".equals(e.chargedTo())) {
            reverse(escrow, e.aggregateId(), e.amountCents());
        }
    }

    /** Credits what the released escrow earns, once. */
    @Transactional
    public void earn(String escrowId) {
        var fact = sales.of(escrowId).orElse(null);
        if (fact == null || fact.customerId() == null) {
            return;
        }
        var customer = Objects.requireNonNull(fact.customerId());
        var now = clock.instant();
        var place = places.of(fact.merchantId());
        var rule = rules.of(place.province(), place.marketId());
        var kind = fact.kind().code();
        var amount = Math.max(0, fact.amountCents() - fact.refundedCents());
        var base = rule.earns(kind) ? Earning.base(amount, rule.pointsPerDollar()) : 0;
        var benefits = plus.of(customer);
        var multiplier = benefits.member() ? benefits.pointsMultiplierPct() : 100;
        var points = Earning.withPlus(base, multiplier);
        var expires = Earning.expiresAt(now, rule.expiryMonths());
        var inserted = store.insert(new LoyaltyStore.EarningRow(
                escrowId,
                customer,
                fact.merchantId(),
                kind,
                fact.refType(),
                fact.refId(),
                amount,
                rule.id(),
                rule.pointsPerDollar(),
                multiplier,
                points,
                0,
                0,
                0,
                expires,
                now));
        if (!inserted) {
            return; // a retried listener: this escrow already earned
        }
        if (points > 0) {
            ledger.credit(customer, points, PointsLedger.EARN, escrowId, expires, null, note(fact));
        }
        rewards.credit(
                        new RewardCredits.Sale(
                                fact.merchantId(), escrowId, customer, fact.heldAt(), fact.listingId(), base),
                        expires)
                .ifPresent(granted -> {
                    store.rewardPoints(escrowId, granted.points());
                    charges.charge(granted.creditId(), fact.merchantId(), escrowId, granted.cents());
                });
        referrals.completedSale(customer, escrowId, amount, rule, now);
    }

    private static String note(SaleFacts.Fact fact) {
        var label = fact.label().isBlank() ? null : fact.label();
        return label != null
                ? label
                : switch (fact.kind()) {
                    case SERVICE -> "Booking";
                    case FOOD -> "Food order";
                    case GOODS -> "Order";
                };
    }

    /** Takes back a refund's share of what the escrow earned, once per refund. */
    @Transactional
    public void reverse(String escrowId, String refundId, long refundedCents) {
        var earning = store.lockEarning(escrowId).orElse(null);
        if (earning == null) {
            return; // refunded before release: the release earns on what is left
        }
        var left = earning.points() - earning.reversedPoints();
        var points = Math.min(left, Earning.reversal(earning.points(), refundedCents, earning.amountCents()));
        if (points > 0
                && !ledger.takeBack(earning.customerId(), points, PointsLedger.EARN_REVERSAL, refundId, "Refund")) {
            return; // this refund was already taken back
        }
        store.reversed(escrowId, refundedCents, points);
        rewards.takeBack(escrowId, refundId, refundedCents, earning.amountCents())
                .ifPresent(t -> charges.refund(t.creditId() + ":" + refundId, t.merchantId(), escrowId, t.cents()));
    }
}
