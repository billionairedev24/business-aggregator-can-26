package ca.northline.merchants.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.merchants.api.TeamRoster;
import ca.northline.merchants.application.RenameMerchant;
import ca.northline.merchants.application.ViewMerchant;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.region.api.Regions;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Studio header: {@code GET/PATCH /api/v1/merchants/{merchantId}}. */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}")
@RequiredArgsConstructor
class MerchantController {

    private final ViewMerchant viewMerchant;
    private final RenameMerchant renameMerchant;
    private final TeamRoster team;
    private final MerchantWebMapper mapper;
    private final MerchantPlaces places;
    private final Regions regions;

    @GetMapping
    @RequiresMerchant(VIEW)
    MerchantResponse get(@PathVariable String merchantId, CurrentMember member) {
        return mapper.toResponse(viewMerchant.view(merchantId))
                .forMember(member.role().code(), team.members(merchantId).size(), region(merchantId));
    }

    @PatchMapping
    @RequiresMerchant(MANAGE)
    MerchantResponse update(
            @PathVariable String merchantId, @Valid @RequestBody UpdateMerchantRequest body, CurrentMember member) {
        var command = new RenameMerchant.Command(merchantId, body.displayName(), member.userId());
        return mapper.toResponse(renameMerchant.rename(command))
                .forMember(member.role().code(), team.members(merchantId).size(), region(merchantId));
    }

    private MerchantResponse.Region region(String merchantId) {
        var place = places.of(merchantId);
        var profile = place.profile();
        var language =
                regions.languageRules(place.province(), place.marketId() != null ? place.marketId() : place.city());
        return new MerchantResponse.Region(
                place.province(),
                new MerchantResponse.Names(place.provinceNameEn(), place.provinceNameFr()),
                profile == null
                        ? new MerchantResponse.Names("", "")
                        : new MerchantResponse.Names(
                                profile.nameIn(Locale.ENGLISH), profile.nameIn(Locale.CANADA_FRENCH)),
                profile == null
                        ? new MerchantResponse.Names("", "")
                        : new MerchantResponse.Names(
                                profile.nameOf(Locale.ENGLISH), profile.nameOf(Locale.CANADA_FRENCH)),
                place.zone().getId(),
                regions.province(place.province())
                        .map(p -> p.privacyLaw().code())
                        .orElse(null),
                language.frenchFirst(),
                language.frenchListings());
    }
}
