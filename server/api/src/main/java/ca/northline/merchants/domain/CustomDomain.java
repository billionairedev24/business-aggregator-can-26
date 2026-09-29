package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.RuleViolation;
import java.util.Locale;
import java.util.regex.Pattern;

/** The owner's own domain for the storefront; needs a CNAME to {@value #CNAME_TARGET} verified before it serves. */
public record CustomDomain(String value) {
    public static final String FIELD = "customDomain";
    public static final String CNAME_TARGET = "pages.northline.ca";
    public static final String FORMAT = "Enter a domain like book.yourbusiness.ca.";
    public static final String TAKEN = "That domain is already connected to another page.";
    public static final String UNVERIFIED = "Point the CNAME at pages.northline.ca and verify it before publishing.";

    private static final Pattern HOST =
            Pattern.compile("^(?=.{4,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

    /** {@code merchants.storefronts.custom_domain_status}. */
    public enum Status implements CodedEnum {
        PENDING,
        VERIFIED,
        FAILED
    }

    public CustomDomain {
        value = value.strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("^https?://", "")
                .replaceAll("/+$", "");
        if (!HOST.matcher(value).matches() || value.equals("northline.ca") || value.endsWith(".northline.ca")) {
            throw RuleViolation.of(FIELD, "format", FORMAT);
        }
    }
}
