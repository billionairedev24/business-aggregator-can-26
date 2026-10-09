package ca.northline.promotions.application;

import ca.northline.payments.api.PointsReturned;
import ca.northline.trust.api.PointsWallet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A refund gave back points' share of an escrow: the points go back to the wallet at the rate they were spent at (the
 * redemption's own points per cent, not today's configuration), once per refund.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PointsReturnListener {

    private final PromotionStore store;
    private final PointsWallet wallet;

    @ApplicationModuleListener
    void on(PointsReturned e) {
        var redemption = store.byEscrow(e.escrowRefType(), e.escrowRefId()).orElse(null);
        if (redemption == null || redemption.pointsCents() == 0) {
            log.warn("points returned for {} {} without a redemption", e.escrowRefType(), e.escrowRefId());
            return;
        }
        var points = (redemption.points() * e.cents() + redemption.pointsCents() - 1) / redemption.pointsCents();
        if (wallet.giveBack(
                redemption.customerId(),
                Math.min(points, redemption.points()),
                "refund_return",
                e.aggregateId(),
                "Refund")) {
            store.pointsReturned(e.escrowRefType(), e.escrowRefId(), e.cents());
        }
    }
}
