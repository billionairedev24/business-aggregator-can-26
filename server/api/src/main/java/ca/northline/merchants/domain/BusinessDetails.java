package ca.northline.merchants.domain;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Everything the Business step captures, already validated. {@code legalDetails} is the JSON object of
 * legal-details.schema.json for {@code structure} (snake_case keys, {@code structure} included).
 */
public record BusinessDetails(
        DisplayName displayName,
        String legalName,
        BusinessStructure structure,
        @Nullable GstNumber gstNumber,
        Map<String, Object> legalDetails,
        List<Principal> principals,
        List<SelectedCategory> categories,
        BusinessProfile profile) {

    public BusinessDetails {
        legalName = legalName.strip();
        legalDetails = Map.copyOf(legalDetails);
        principals = List.copyOf(principals);
        categories = List.copyOf(categories);
    }

    /** CRA business number from the legal details (9 digits), if the structure has one. */
    public @Nullable String businessNumber() {
        return legalDetails.get("business_number") instanceof String bn ? bn : null;
    }

    /** Registry reference: Alberta corporate access #, corporation #, registration #. */
    public @Nullable String registryRef() {
        for (var key : List.of(
                "alberta_corporate_access_number",
                "corporations_canada_number",
                "home_registration_number",
                "partnership_registration",
                "cooperative_registration",
                "society_registration",
                "trade_name_registration")) {
            if (legalDetails.get(key) instanceof String ref && !ref.isBlank()) {
                return ref;
            }
        }
        return null;
    }

    public String registryJurisdiction() {
        return switch (structure) {
            case CORP_FED -> "CA";
            case CORP_EX -> String.valueOf(legalDetails.getOrDefault("home_jurisdiction", "AB"));
            default -> "AB";
        };
    }

    /** Addresses from the legal details (home, registered office, attorney for service). */
    List<String> addresses() {
        return legalDetails.entrySet().stream()
                .filter(e -> e.getKey().equals("address") || e.getKey().equals("registered_office"))
                .map(e -> String.valueOf(e.getValue()))
                .toList();
    }
}
