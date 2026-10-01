package ca.northline.merchants.domain;

import static ca.northline.merchants.domain.RegistrySource.CORPORATIONS_CANADA;
import static ca.northline.merchants.domain.RegistrySource.MANUAL;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Which registries verify what the owner entered in onboarding (docs/runbooks/registries.md), with the adapters the
 * business's province and city have in the region model ({@link RegistryRoutes}, S-134):
 *
 * <ul>
 *   <li>the {@code registry} row: the business record per structure (legal-details.schema.json) — provincial
 *       corporations, extra-provincial registrations, partnerships, trade names, co-operatives and societies in the
 *       province's corporate registry; federal corporations at Corporations Canada <em>and</em> their
 *       extra-provincial registration in the province; plus, for kitchens, the city's business licence where the city
 *       has a licence source;
 *   <li>licence rows: the licences the city's source answers (a mobile vending permit); every other regulator has no
 *       API and goes to a Northline agent.
 * </ul>
 *
 * A province without a registry adapter sends its records to an agent; a city without a licence source has no
 * municipal lookup. Nothing is assumed about where a business is.
 */
public final class RegistryPlan {
    private RegistryPlan() {}

    /** The lookups behind the {@code registry} row; empty = nothing to register (a sole proprietor without a trade name). */
    public static List<RegistryQuery> business(MerchantApplication a, RegistryRoutes routes) {
        var provincial = routes.provincialOrManual();
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
                                provincial,
                                RegistrySubject.TRADE_NAME,
                                text(d, "trade_name_registration"),
                                withFirst(tradeName, names));
                    }
                }
                case PARTNERSHIP ->
                    add(
                            out,
                            provincial,
                            RegistrySubject.PARTNERSHIP,
                            text(d, "partnership_registration"),
                            withFirst(text(d, "partnership_name"), names));
                case CORP_AB ->
                    add(
                            out,
                            provincial,
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
                            provincial,
                            RegistrySubject.EXTRA_PROVINCIAL,
                            text(d, "alberta_extra_provincial_registration"),
                            names);
                }
                case CORP_EX ->
                    add(
                            out,
                            provincial,
                            RegistrySubject.EXTRA_PROVINCIAL,
                            text(d, "alberta_extra_provincial_registration"),
                            names);
                case COOP ->
                    add(
                            out,
                            provincial,
                            RegistrySubject.COOPERATIVE,
                            text(d, "cooperative_registration"),
                            withFirst(text(d, "registered_name"), names));
                default -> // NONPROFIT
                    add(
                            out,
                            provincial,
                            RegistrySubject.SOCIETY,
                            text(d, "society_registration"),
                            withFirst(text(d, "registered_name"), names));
            }
        }
        var cityLicence = a.getProfile().cityLicenceNumber();
        var municipal = routes.municipal();
        if (a.getType() == MerchantType.KITCHEN && cityLicence != null && !cityLicence.isBlank() && municipal != null) {
            add(out, municipal, RegistrySubject.MUNICIPAL_LICENCE, cityLicence, names);
        }
        return List.copyOf(out);
    }

    /** The lookup behind a licence row ({@code licence:<registry>}, {@code ahs_permit}, {@code aglc}). */
    public static RegistryQuery licence(MerchantApplication a, RegistryRoutes routes, String registry, String number) {
        var municipal = routes.municipalAnswers(registry) ? routes.municipal() : null;
        var source = municipal != null ? municipal : MANUAL;
        var subject = municipal != null ? RegistrySubject.MUNICIPAL_LICENCE : RegistrySubject.LICENCE;
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
