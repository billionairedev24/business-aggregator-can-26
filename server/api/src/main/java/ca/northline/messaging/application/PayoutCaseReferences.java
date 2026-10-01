package ca.northline.messaging.application;

import ca.northline.merchants.api.MerchantDirectory;
import ca.northline.messaging.api.CaseReferences;
import ca.northline.payments.api.RecentPayouts;
import ca.northline.region.api.Regions;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Finance's "Related to" contribution (S-66): the business's latest payouts, "Payout · $1,234.56 · Sep 26" /
 * « Versement · 1 234,56 $ · 26 sept. », dated in the business's time zone (region model). Payments owns the payouts
 * ({@link RecentPayouts}); messaging formats them here because payments can't implement {@link CaseReferences} itself
 * — messaging already depends on payments (it listens to its events), so that would be a module cycle.
 */
@Component
@RequiredArgsConstructor
class PayoutCaseReferences implements CaseReferences {

    static final int LIMIT = 5;

    private final RecentPayouts payouts;
    private final MerchantDirectory merchants;
    private final Regions regions;

    @Override
    public List<Reference> recent(String merchantId, Locale locale) {
        var list = payouts.recent(merchantId, LIMIT);
        if (list.isEmpty()) {
            return List.of();
        }
        var zone = zone(merchantId);
        boolean fr = "fr".equals(locale.getLanguage());
        var money = NumberFormat.getCurrencyInstance(fr ? Locale.CANADA_FRENCH : Locale.CANADA);
        var day = DateTimeFormatter.ofPattern(fr ? "d MMM" : "MMM d", fr ? Locale.CANADA_FRENCH : Locale.CANADA)
                .withZone(zone);
        return list.stream()
                .map(p -> new Reference(
                        "payout",
                        p.id(),
                        "%s · %s · %s"
                                .formatted(
                                        name(p.kind(), fr),
                                        money.format(p.amountCents() / 100.0),
                                        day.format(p.arrivesAt()))))
                .toList();
    }

    private static String name(String kind, boolean fr) {
        if ("instant".equals(kind)) {
            return fr ? "Versement instantané" : "Instant payout";
        }
        return fr ? "Versement" : "Payout";
    }

    private ZoneId zone(String merchantId) {
        return merchants
                .profile(merchantId)
                .map(p -> regions.zone(p.province(), p.city()))
                .orElseGet(regions::platformZone);
    }
}
