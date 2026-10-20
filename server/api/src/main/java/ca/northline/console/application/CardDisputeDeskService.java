package ca.northline.console.application;

import ca.northline.merchants.api.BusinessNames;
import ca.northline.payments.api.CardDisputes;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.shared.MerchantScope;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link CardDisputeDesk} composed from payments, merchants and region (their {@code api} only). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CardDisputeDeskService implements CardDisputeDesk {

    /** Closed card disputes stay listed this long. */
    static final Duration CLOSED_SHOWN = Duration.ofDays(30);

    private final CardDisputes disputes;
    private final BusinessNames names;
    private final MerchantPlaces places;
    private final Clock clock;

    @Override
    public List<Item> list(MerchantScope scope) {
        return disputes.list(scope, clock.instant().minus(CLOSED_SHOWN), DisputeDeskService.LIMIT).stream()
                .map(this::item)
                .toList();
    }

    @Override
    public Optional<Detail> detail(String disputeId) {
        return disputes.detail(disputeId).map(d -> new Detail(item(d.row()), d));
    }

    @Override
    @Transactional
    public Detail save(String disputeId, CardDisputes.Draft draft, String staffId, String role) {
        var d = disputes.save(disputeId, draft, staffId, role);
        return new Detail(item(d.row()), d);
    }

    @Override
    @Transactional(noRollbackFor = ca.northline.shared.Conflict.class)
    public Detail submit(String disputeId, String staffId, String role) {
        var d = disputes.submit(disputeId, staffId, role);
        return new Detail(item(d.row()), d);
    }

    private Item item(CardDisputes.Row row) {
        var place = places.of(row.merchantId());
        return new Item(
                row,
                names.displayName(row.merchantId()).orElse(row.merchantId()),
                place.ownProvince() ? place.province() : null);
    }
}
