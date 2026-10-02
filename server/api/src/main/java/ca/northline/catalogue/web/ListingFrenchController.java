package ca.northline.catalogue.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.catalogue.application.ListingFrench;
import ca.northline.catalogue.application.ListingFrench.FrenchText;
import ca.northline.region.api.FrenchListings;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-116 (Loi 96 readiness): a listing's French name and description in the Studio editor. {@code rule} is what the
 * business's place asks (region configuration): {@code off}, {@code warn} (show {@code missing}) or {@code require}
 * (submit and publish answer 422 {@code french} while {@code missing}).
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/listings/{listingId}/french")
@RequiredArgsConstructor
class ListingFrenchController {

    private final ListingFrench french;

    record FrenchTextRequest(
            @Nullable String title, @Nullable String description) {}

    record FrenchTextResponse(
            FrenchListings rule, String title, @Nullable String description, boolean missing) {

        static FrenchTextResponse of(FrenchText t) {
            return new FrenchTextResponse(t.rule(), t.title(), t.description(), t.missing());
        }
    }

    @GetMapping
    @RequiresMerchant(VIEW)
    FrenchTextResponse get(@PathVariable String merchantId, @PathVariable String listingId) {
        return FrenchTextResponse.of(french.view(merchantId, listingId));
    }

    @PutMapping
    @RequiresMerchant(EDIT)
    FrenchTextResponse put(
            @PathVariable String merchantId,
            @PathVariable String listingId,
            @RequestBody FrenchTextRequest body,
            CurrentMember member) {
        return FrenchTextResponse.of(french.save(
                merchantId,
                listingId,
                Objects.requireNonNullElse(body.title(), ""),
                body.description(),
                member.userId()));
    }
}
