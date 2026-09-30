package ca.northline.merchants.domain;

import static ca.northline.merchants.domain.RegistrySource.ALBERTA_CORPORATE_REGISTRY;
import static ca.northline.merchants.domain.RegistrySource.CALGARY_BUSINESS_LICENCES;
import static ca.northline.merchants.domain.RegistrySource.CORPORATIONS_CANADA;
import static ca.northline.merchants.domain.RegistrySource.MANUAL;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which registries verify what the owner entered in onboarding (docs/runbooks/registries.md):
 *
 * <ul>
 *   <li>the {@code registry} row: the business record per structure (legal-details.schema.json) — Alberta
 *       corporations, extra-provincial registrations, partnerships, trade names, co-operatives and societies in the
 *       Alberta Corporate Registry; federal corporations at Corporations Canada <em>and</em> their Alberta
 *       extra-provincial registration; plus, for kitchens, the City of Calgary business licence;
 *   <li>licence rows: Calgary's mobile vending permit in the Calgary dataset; every other regulator (AMVIC, AHS, AGLC,
 *       RECA, Safety Codes, …) has no API and goes to a Northline agent.
 * </ul>
 */
public final class RegistryPlan {
    private RegistryPlan() {}

    /** Licence registries answered by the City of Calgary dataset. */
    static final Set<String> CALGARY_LICENCES = Set.of("mobile permit", "calgary business licence");

    /** The lookups behind the {@code registry} row; empty = nothing to register (a sole proprietor without a trade name). */
    public static List<RegistryQuery> business(MerchantApplication a) {
        var d = a.getLegalDetails();
        var names = names(a);
        var out = new ArrayList<RegistryQuery>();
        var structure = a.getStructure();
        if (structure != null) {
            switch (structure) {
                case SOLE -> {
                    var tradeName = text(d, "trade_name");
                    if (tradeName != null) {
                        add(
                                out,
                                ALBERTA_CORPORATE_REGISTRY,
                                RegistrySubject.TRADE_NAME,
                                text(d, "trade_name_registration"),
                                withFirst(tradeName, names));
                    }
                }
                case PARTNERSHIP ->
                    add(
                            out,
                            ALBERTA_CORPORATE_REGISTRY,
                            RegistrySubject.PARTNERSHIP,
                            text(d, "partnership_registration"),
                            withFirst(text(d, "partnership_name"), names));
                case CORP_AB ->
                    add(
                            out,
                            ALBERTA_CORPORATE_REGISTRY,
                            RegistrySubject.CORPORATION,
                            text(d, "alberta_corporate_access_number"),
                            names);
                case CORP_FED -> {
                    add(
                            out,
                            CORPORATIONS_CANADA,
                            RegistrySubject.CORPORATION,
                            text(d, "corporations_canada_number"),
                            names);
                    add(
                            out,
                            ALBERTA_CORPORATE_REGISTRY,
                            RegistrySubject.EXTRA_PROVINCIAL,
                            text(d, "alberta_extra_provincial_registration"),
                            names);
                }
                case CORP_EX ->
                    add(
                            out,
                            ALBERTA_CORPORATE_REGISTRY,
                            RegistrySubject.EXTRA_PROVINCIAL,
                            text(d, "alberta_extra_provincial_registration"),
                            names);
                case COOP ->
                    add(
                            out,
                            ALBERTA_CORPORATE_REGISTRY,
                            RegistrySubject.COOPERATIVE,
                            text(d, "cooperative_registration"),
                            withFirst(text(d, "registered_name"), names));
                default -> // NONPROFIT
                    add(
                            out,
                            ALBERTA_CORPORATE_REGISTRY,
                            RegistrySubject.SOCIETY,
                            text(d, "society_registration"),
                            withFirst(text(d, "registered_name"), names));
            }
        }
        var cityLicence = a.getProfile().cityLicenceNumber();
        if (a.getType() == MerchantType.KITCHEN && cityLicence != null && !cityLicence.isBlank() && inCalgary(a)) {
            add(out, CALGARY_BUSINESS_LICENCES, RegistrySubject.MUNICIPAL_LICENCE, cityLicence, names);
        }
        return List.copyOf(out);
    }

    /** The lookup behind a licence row ({@code licence:<registry>}, {@code ahs_permit}, {@code aglc}). */
    public static RegistryQuery licence(MerchantApplication a, String registry, String number) {
        var source = CALGARY_LICENCES.contains(registry.toLowerCase(Locale.ROOT)) && inCalgary(a)
                ? CALGARY_BUSINESS_LICENCES
                : MANUAL;
        var subject = source == CALGARY_BUSINESS_LICENCES ? RegistrySubject.MUNICIPAL_LICENCE : RegistrySubject.LICENCE;
        return new RegistryQuery(source, subject, registry, number, names(a));
    }

    /** Legal name, operating name, display name — the names a record may carry. */
    static List<String> names(MerchantApplication a) {
        var d = a.getLegalDetails();
        var names = new ArrayList<String>();
        for (var n : new @Nullable String[] {
            text(d, "legal_corporate_name"),
            a.getLegalName(),
            text(d, "operating_name"),
            text(d, "trade_name"),
            a.getDisplayName()
        }) {
            if (n != null && !n.isBlank()) {
                names.add(n);
            }
        }
        return names;
    }

    /** No city yet counts as Calgary: Calgary is the launch city and the only municipal source. */
    static boolean inCalgary(MerchantApplication a) {
        return a.getCity() == null || a.getCity().equalsIgnoreCase("Calgary");
    }

    private static void add(
            List<RegistryQuery> out,
            RegistrySource source,
            RegistrySubject subject,
            @Nullable String number,
            List<String> names) {
        if (number != null && !number.isBlank()) {
            out.add(new RegistryQuery(source, subject, null, number, names));
        }
    }

    private static List<String> withFirst(@Nullable String first, List<String> names) {
        if (first == null) {
            return names;
        }
        var all = new ArrayList<String>();
        all.add(first);
        all.addAll(names);
        return all;
    }

    private static @Nullable String text(Map<String, Object> d, String key) {
        return d.get(key) instanceof String s && !s.isBlank() ? s.strip() : null;
    }
}
