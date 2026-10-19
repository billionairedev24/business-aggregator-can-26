package ca.northline.merchants.domain;

/**
 * Region-neutral keys of {@code merchants.merchants.legal_details} (docs/spec/legal-details.schema.json) that code reads;
 * the province they belong to is the business's own ({@code merchants.merchants.province}).
 */
public final class LegalKeys {
    private LegalKeys() {}

    /** The corporation's number in its province's corporate registry. */
    public static final String PROVINCIAL_REGISTRY_NUMBER = "provincial_registry_number";

    /** The registration that lets a corporation from elsewhere carry on business in the province. */
    public static final String EXTRA_PROVINCIAL_REGISTRATION_NUMBER = "extra_provincial_registration_number";

    /** The attorney / agent for service ({@code name}, {@code address_in_province}). */
    public static final String ATTORNEY_FOR_SERVICE = "attorney_for_service";

    public static final String ADDRESS_IN_PROVINCE = "address_in_province";
}
