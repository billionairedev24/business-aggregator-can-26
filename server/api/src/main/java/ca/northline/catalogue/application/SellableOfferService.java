package ca.northline.catalogue.application;

import ca.northline.merchants.api.RestrictedLicences;
import ca.northline.orders.api.SellableOffers;
import ca.northline.region.api.AgeClass;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SellableOffers} for the orders module's cart and checkout (S-51): approved, live offers only; images only
 * when approved (S-123). Taking stock joins the caller's transaction, so a checkout that fails puts nothing aside.
 */
@Service
@RequiredArgsConstructor
class SellableOfferService implements SellableOffers {

    private final StockStore stock;
    private final MediaRepository media;
    private final RestrictedLicences licences;

    /** Defence in depth: a restricted offer is sellable only while its business holds the class's licence. */
    private boolean licensed(String merchantId, String ageClass) {
        return AgeClass.of(ageClass).map(c -> licences.licensed(merchantId, c)).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Sellable> find(Collection<Item> items, String lang) {
        if (items.isEmpty()) {
            return List.of();
        }
        var rows = stock.rows(items, lang);
        var images = rows.stream()
                .map(StockStore.Row::imageId)
                .filter(Objects::nonNull)
                .toList();
        var approved = images.isEmpty() ? Set.<String>of() : media.approved(images);
        return rows.stream()
                .map(r -> new Sellable(
                        r.offerId(),
                        r.variantId(),
                        r.productId(),
                        r.merchantId(),
                        r.name(),
                        r.option(),
                        r.unit(),
                        r.hasVariants(),
                        r.unitCents(),
                        r.stock(),
                        r.handlingDays(),
                        r.imageId() != null && approved.contains(r.imageId())
                                ? ShopBrowsingService.MEDIA_URL + r.imageId()
                                : null,
                        r.ageClass()))
                .filter(s -> s.ageClass() == null || licensed(s.merchantId(), s.ageClass()))
                .toList();
    }

    @Override
    @Transactional
    public List<Take> take(List<Take> takes) {
        return takes.stream().filter(t -> !stock.take(t)).toList();
    }

    @Override
    @Transactional
    public void giveBack(List<Take> takes) {
        takes.forEach(stock::giveBack);
    }
}
