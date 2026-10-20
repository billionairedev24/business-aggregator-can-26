package ca.northline.catalogue.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.catalogue.application.MemberPrices;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-152 Northline Plus member prices.
 *
 * <pre>
 * GET /api/v1/merchants/{merchantId}/products/{listingId}/member-price   {memberDiscountPct|null} (VIEW)
 * PUT /api/v1/merchants/{merchantId}/products/{listingId}/member-price   {memberDiscountPct: 1–50 | null} (EDIT)
 * GET /api/v1/public/plus-prices?offers=a,b,c                             {"<offerId>": pct} for live offers, 60 s cache
 * </pre>
 */
@RestController
@RequiredArgsConstructor
class MemberPriceController {

    private final MemberPrices prices;

    record MemberPrice(@Nullable Integer memberDiscountPct) {}

    @GetMapping("/api/v1/merchants/{merchantId}/products/{listingId}/member-price")
    @RequiresMerchant(VIEW)
    MemberPrice get(@PathVariable String merchantId, @PathVariable String listingId) {
        return new MemberPrice(prices.of(merchantId, listingId).orElse(null));
    }

    @PutMapping("/api/v1/merchants/{merchantId}/products/{listingId}/member-price")
    @RequiresMerchant(EDIT)
    MemberPrice put(
            @PathVariable String merchantId,
            @PathVariable String listingId,
            @RequestBody MemberPrice body,
            CurrentMember member) {
        return new MemberPrice(prices.set(
                        merchantId,
                        listingId,
                        body.memberDiscountPct(),
                        member.userId(),
                        member.role().code())
                .orElse(null));
    }

    @GetMapping("/api/v1/public/plus-prices")
    ResponseEntity<Map<String, Integer>> live(@RequestParam(defaultValue = "") String offers) {
        var ids = Arrays.stream(offers.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .limit(200)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(prices.live(ids));
    }
}
