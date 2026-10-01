package ca.northline.catalogue.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;

import ca.northline.catalogue.application.DraftListingCopy;
import ca.northline.catalogue.domain.ListingKind;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-131: {@code POST /api/v1/merchants/{merchantId}/listing-copy} — an English and French draft of a listing's title,
 * description and bullets from what the editor holds. Nothing is saved; the editor shows it as an AI-assisted draft.
 */
@RestController
@RequiredArgsConstructor
class ListingCopyController {

    private final DraftListingCopy drafts;

    record Body(
            @NotNull(message = "Choose service or product.") ListingKind kind,
            @Nullable @Size(max = 200) String name,
            @Nullable String categoryId,
            @Nullable @Size(max = 120) String brand,
            @Nullable @Size(max = 30) Map<String, String> attributes,
            @Nullable @Size(max = 2000) String included,
            @Nullable Integer durationMin,
            @Nullable @Size(max = 1000, message = "Keep notes under 1,000 characters.") String notes) {}

    @PostMapping("/api/v1/merchants/{merchantId}/listing-copy")
    @RequiresMerchant(EDIT)
    DraftListingCopy.Draft draft(@PathVariable String merchantId, @Valid @RequestBody Body body, CurrentMember member) {
        return drafts.draft(
                merchantId,
                member.userId(),
                new DraftListingCopy.Facts(
                        body.kind(),
                        body.name(),
                        body.categoryId(),
                        body.brand(),
                        body.attributes() == null ? Map.of() : body.attributes(),
                        body.included(),
                        body.durationMin(),
                        body.notes()));
    }
}
