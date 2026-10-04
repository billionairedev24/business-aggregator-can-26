package ca.northline.catalogue.domain;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The automated checks a submitted listing goes through before it can go live: banned categories, price outlier
 * (±60 % of the category median), missing licence for a regulated category, duplicate images (perceptual hash) and the
 * main-image-on-white standard, and (S-93) the restricted keywords of the trust &amp; safety rules. No flags → approved; any flag → manual review in the console vetting queue.
 */
public final class AutomatedVetting {

    /** Allowed deviation from the category median price. */
    public static final double PRICE_BAND = 0.60;

    private AutomatedVetting() {}

    /**
     * What the checks look at.
     *
     * @param licenceOk the merchant holds a verified, unexpired licence for the category's registry (or none is needed)
     * @param duplicateImage one of the seller's own images matches another merchant's image
     * @param mainOnWhite the main image (own images only) has a white background; true when catalogue images are used
     * @param restrictedTerm S-93: a restricted keyword (trust &amp; safety rules) found in the listing's words, or null
     * @param text the listing's own words (name, description), for the age-restricted words check; null to skip it
     */
    public record Subject(
            ListingKind kind,
            @Nullable CategoryProfile category,
            @Nullable Long priceCents,
            boolean licenceOk,
            boolean duplicateImage,
            boolean mainOnWhite,
            @Nullable String restrictedTerm,
            @Nullable String text) {}

    public static List<VettingFlag> check(Subject s) {
        var flags = new ArrayList<VettingFlag>();
        var category = s.category();
        if (category != null) {
            var wrongRoot = s.kind() == ListingKind.PRODUCT ? !category.isShop() : !category.isService();
            if (category.banned() || wrongRoot) {
                flags.add(VettingFlag.BANNED_CATEGORY);
            }
            var median = category.medianPriceCents();
            var price = s.priceCents();
            if (median != null && price != null && isOutlier(price, median)) {
                flags.add(VettingFlag.PRICE_OUTLIER);
            }
            if ((category.regulatedRegistry() != null || category.ageClass() != null) && !s.licenceOk()) {
                flags.add(VettingFlag.MISSING_LICENCE);
            }
            if (category.ageClass() != null) {
                flags.add(VettingFlag.AGE_RESTRICTED);
            } else if (s.kind() == ListingKind.PRODUCT
                    && s.text() != null
                    && AgeRestrictedWords.find(s.text()) != null) {
                flags.add(VettingFlag.AGE_CLASS_MISMATCH);
            }
        }
        if (s.duplicateImage()) {
            flags.add(VettingFlag.DUPLICATE_IMAGE);
        }
        if (!s.mainOnWhite()) {
            flags.add(VettingFlag.MAIN_NOT_ON_WHITE);
        }
        if (s.restrictedTerm() != null) {
            flags.add(VettingFlag.RESTRICTED_KEYWORD);
        }
        return List.copyOf(flags);
    }

    public static boolean isOutlier(long priceCents, long medianCents) {
        return medianCents > 0 && Math.abs(priceCents - medianCents) > PRICE_BAND * medianCents;
    }

    /** Signed deviation from the median in whole percent (−92 = 92 % below). */
    public static long deviationPercent(long priceCents, long medianCents) {
        return Math.round((priceCents - medianCents) * 100.0 / medianCents);
    }
}
