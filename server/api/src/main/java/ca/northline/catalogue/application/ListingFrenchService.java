package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.ListingMessages;
import ca.northline.region.api.FrenchListings;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.region.api.Regions;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.util.ArrayList;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-116: French listing text. The rule is the region configuration of the business's place ({@link MerchantPlaces}:
 * its market, else its province) — {@code french_listings} off / warn / require — so a French-first market or province
 * gets it by data and no place is named here.
 */
@Service
@RequiredArgsConstructor
@Transactional
class ListingFrenchService implements ListingFrench {

    static final String FRENCH = "fr";

    private final ListingRepository listings;
    private final ListingTexts texts;
    private final MerchantPlaces places;
    private final Regions regions;

    @Override
    @Transactional(readOnly = true)
    public FrenchText view(String merchantId, String listingId) {
        requireListing(merchantId, listingId);
        return text(merchantId, listingId);
    }

    @Override
    public FrenchText save(
            String merchantId, String listingId, String title, @Nullable String description, String actorId) {
        requireListing(merchantId, listingId);
        var text = new ListingTexts.Text(title, description);
        var problems = new ArrayList<Violation>();
        if (text.title().isEmpty()) {
            problems.add(new Violation("title", "required", ListingMessages.FRENCH_TITLE_REQUIRED));
        } else if (text.title().length() > ListingMessages.TITLE_MAX) {
            problems.add(new Violation("title", "length", ListingMessages.TITLE_TOO_LONG));
        }
        if (text.description() != null && text.description().length() > ListingMessages.DESCRIPTION_MAX) {
            problems.add(new Violation("description", "length", ListingMessages.DESCRIPTION_TOO_LONG));
        }
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        texts.save(merchantId, listingId, FRENCH, text, actorId);
        return text(merchantId, listingId);
    }

    @Override
    @Transactional(readOnly = true)
    public void checkBeforeLive(String merchantId, String listingId) {
        var text = text(merchantId, listingId);
        if (text.rule().required() && text.missing()) {
            var place = places.of(merchantId);
            throw RuleViolation.of(
                    "french",
                    "required",
                    ListingMessages.FRENCH_REQUIRED.replace("{province}", place.provinceName(Locale.ENGLISH)));
        }
    }

    private FrenchText text(String merchantId, String listingId) {
        var rule = rule(merchantId);
        var stored = texts.find(listingId, FRENCH);
        return new FrenchText(
                rule,
                stored.map(ListingTexts.Text::title).orElse(""),
                stored.map(ListingTexts.Text::description).orElse(null),
                stored.map(t -> !t.complete()).orElse(true));
    }

    /** The business's market's rule, else its province's (region configuration). */
    FrenchListings rule(String merchantId) {
        var place = places.of(merchantId);
        var market = place.marketId() != null ? place.marketId() : place.city();
        return regions.languageRules(place.province(), market).frenchListings();
    }

    private void requireListing(String merchantId, String listingId) {
        if (listings.find(merchantId, listingId).isEmpty()) {
            throw new NotFound("listing", listingId);
        }
    }
}
