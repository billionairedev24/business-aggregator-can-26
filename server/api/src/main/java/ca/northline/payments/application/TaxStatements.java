package ca.northline.payments.application;

import ca.northline.shared.Bytes;
import java.util.Locale;

/**
 * S-41: the Studio's tax documents as PDF statements, in English or French — "{year} GST/HST collected summary" and
 * "{year} annual statement", with the same figures as their CSVs. The business's province (and its sales-tax rate)
 * comes from where the business is, never from code.
 */
public interface TaxStatements {

    Bytes gstSummaryPdf(String merchantId, int year, Locale locale);

    Bytes annualStatementPdf(String merchantId, int year, Locale locale);
}
