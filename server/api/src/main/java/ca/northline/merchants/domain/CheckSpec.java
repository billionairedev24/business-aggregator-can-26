package ca.northline.merchants.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** One required verification check: its kind, row key ({@code licence:AMVIC}) and registry. */
public record CheckSpec(
        CheckKind kind, String key, @Nullable String registry) {

    /** Food regulators already covered by the fixed kitchen checks (AHS permit, AGLC). */
    private static final Set<String> FOOD_COVERED = Set.of("AHS", "AHS home-based", "AGLC");

    static CheckSpec of(CheckKind kind) {
        return new CheckSpec(kind, kind.key(), kind.registry());
    }

    static CheckSpec licence(String registry) {
        return new CheckSpec(CheckKind.LICENCE, CheckKind.LICENCE_PREFIX + registry, registry);
    }

    /**
     * The checklist for a business type and its selected categories, in display order (design 02 {@code checkDefs}):
     * the generic list gets one licence row per regulator of the chosen service categories; sellers declare
     * product-category permits in one row; kitchens clear the full AHS list.
     */
    public static List<CheckSpec> checklist(MerchantType type, List<SelectedCategory> categories) {
        var licences = (type == MerchantType.KITCHEN
                        ? regulators(categories, CategoryRoot.FOOD).stream().filter(r -> !FOOD_COVERED.contains(r))
                        : regulators(categories, CategoryRoot.SERVICE).stream())
                .map(CheckSpec::licence)
                .toList();
        var out = new ArrayList<CheckSpec>();
        var tail = switch (type) {
            case PROVIDER -> {
                add(out, CheckKind.KYC, CheckKind.REGISTRY);
                out.addAll(licences);
                yield List.of(CheckKind.INSURANCE, CheckKind.BANK, CheckKind.MFA);
            }
            case SELLER -> {
                add(out, CheckKind.KYC, CheckKind.REGISTRY);
                yield List.of(
                        CheckKind.GST,
                        CheckKind.CATEGORY_PERMITS,
                        CheckKind.PRODUCT_SAFETY,
                        CheckKind.RETURNS_POLICY,
                        CheckKind.BANK,
                        CheckKind.MFA);
            }
            case BOTH -> {
                add(out, CheckKind.KYC, CheckKind.REGISTRY);
                out.addAll(licences);
                yield List.of(
                        CheckKind.INSURANCE,
                        CheckKind.CATEGORY_PERMITS,
                        CheckKind.PRODUCT_SAFETY,
                        CheckKind.BANK,
                        CheckKind.MFA);
            }
            case KITCHEN -> {
                add(out, CheckKind.KYC, CheckKind.REGISTRY, CheckKind.AHS_PERMIT);
                out.addAll(licences);
                yield List.of(
                        CheckKind.FOOD_CERT,
                        CheckKind.INSPECTION,
                        CheckKind.INSURANCE,
                        CheckKind.ALLERGEN_ATTESTATION,
                        CheckKind.AGLC,
                        CheckKind.GST,
                        CheckKind.BANK,
                        CheckKind.MFA,
                        CheckKind.SITE_VISIT);
            }
        };
        tail.forEach(k -> out.add(of(k)));
        return List.copyOf(out);
    }

    private static void add(List<CheckSpec> out, CheckKind... kinds) {
        for (var k : kinds) {
            out.add(of(k));
        }
    }

    private static Set<String> regulators(List<SelectedCategory> categories, CategoryRoot root) {
        var out = new LinkedHashSet<String>();
        categories.stream()
                .filter(c -> c.root() == root && c.regulated())
                .forEach(c -> out.add(java.util.Objects.requireNonNull(c.regulator())));
        return out;
    }
}
