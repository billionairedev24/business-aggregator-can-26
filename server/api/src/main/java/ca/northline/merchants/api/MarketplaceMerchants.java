package ca.northline.merchants.api;

import ca.northline.shared.Backlog;
import ca.northline.shared.MerchantScope;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Platform-wide business figures for the console (S-91) — and the set of businesses a province or market covers,
 * which other modules' figures are filtered by ({@link MerchantScope}).
 */
public interface MarketplaceMerchants {

    /**
     * The businesses operating in {@code province} (two-letter code) and, when given, trading in {@code city}
     * (case-insensitive). Both null = every business.
     */
    List<String> idsIn(@Nullable String province, @Nullable String city);

    /** Approved businesses (status {@code active}). */
    long active(MerchantScope scope);

    /** Applications waiting for a decision (status {@code pending}), oldest by submission. */
    Backlog applications(MerchantScope scope);
}
