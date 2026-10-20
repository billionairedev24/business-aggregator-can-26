package ca.northline.catalogue.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link MemberPrices} over the shop's own offers ({@link MemberPriceStore}). */
@Service
@RequiredArgsConstructor
@Transactional
class MemberPriceService implements MemberPrices {

    private final MemberPriceStore store;
    private final AuditTrail audit;

    @Override
    @Transactional(readOnly = true)
    public Optional<Integer> of(String merchantId, String offerId) {
        return store.find(merchantId, offerId).orElseThrow(() -> new NotFound("listing", offerId));
    }

    @Override
    public Optional<Integer> set(
            String merchantId, String offerId, @Nullable Integer pct, String actorId, String role) {
        if (pct != null && (pct < 1 || pct > 50)) {
            throw RuleViolation.of("memberDiscountPct", "range", RANGE);
        }
        var before = store.find(merchantId, offerId).orElseThrow(() -> new NotFound("listing", offerId));
        store.set(merchantId, offerId, pct);
        if (!before.equals(Optional.ofNullable(pct))) {
            var b = new HashMap<String, Object>();
            b.put("memberDiscountPct", before.map(String::valueOf).orElse("none"));
            var a = new HashMap<String, Object>();
            a.put("memberDiscountPct", pct == null ? "none" : String.valueOf(pct));
            audit.record(new AuditTrail.Entry(
                    merchantId, actorId, role, "listing.member_price_changed", "listing", offerId, b, a));
        }
        return Optional.ofNullable(pct);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Integer> live(Collection<String> offerIds) {
        return offerIds.isEmpty() ? Map.of() : store.live(offerIds);
    }
}
