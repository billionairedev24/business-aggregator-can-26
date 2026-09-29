package ca.northline.region.api;

/** Sales-tax rates from the region tax profile ({@code region.tax_profiles}), in basis points (GST 5 % = 500). */
public interface TaxRates {

    /** Combined rate charged on taxable quote lines for a province, e.g. {@code AB} → GST 5 % = 500 bps. */
    int bpsFor(String province);
}
