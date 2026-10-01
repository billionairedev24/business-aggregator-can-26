package ca.northline.shared;

import java.util.Collection;
import java.util.Set;

/**
 * Which businesses a platform-wide figure covers (S-91, the console overview): every one, or a set — a province's or
 * a market's, which the console resolves through {@code merchants.api.MarketplaceMerchants}. Modules filter their own
 * rows by {@code merchant_id} with it; nobody reads another module's tables.
 *
 * <p>In SQL: {@code (:everyone or t.merchant_id = any(:merchants))} with {@link #everyone()} and {@link #ids()}.
 */
public sealed interface MerchantScope {

    /** Every business on the platform. */
    record Everyone() implements MerchantScope {}

    /** Only these businesses (empty = none). */
    record Only(Set<String> merchantIds) implements MerchantScope {
        public Only {
            merchantIds = Set.copyOf(merchantIds);
        }
    }

    static MerchantScope everyBusiness() {
        return new Everyone();
    }

    static MerchantScope only(Collection<String> merchantIds) {
        return new Only(Set.copyOf(merchantIds));
    }

    default boolean everyone() {
        return this instanceof Everyone;
    }

    /** The ids for an {@code = any(:merchants)} parameter; empty for {@link Everyone} (the flag decides then). */
    default String[] ids() {
        return switch (this) {
            case Everyone _ -> new String[0];
            case Only(var ids) -> ids.stream().sorted().toArray(String[]::new);
        };
    }
}
