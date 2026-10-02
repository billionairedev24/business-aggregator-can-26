package ca.northline.merchants.application;

import ca.northline.merchants.domain.MerchantType;

/** The category limit of a business type now ({@code merchants.category_limits}, S-94; else the type's default). */
public interface CategoryLimitLookup {

    int limit(MerchantType type);
}
