package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.RuleViolation;
import java.net.IDN;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The owner's own domain for the storefront (lower-case ASCII; an internationalised name is stored as its punycode).
 * It serves once ownership is proven (S-31, {@link DomainClaim}): a TXT record {@link #verificationName()} holding
 * the claim's token, and a CNAME to {@code pages.<zone>} (an apex: ALIAS/ANAME or A records to the edge).
 */
public record CustomDomain(String value) {
    public static final String FIELD = "customDomain";
    public static final String CNAME_TARGET = "pages.northline.ca";
    public static final String VERIFY_LABEL = "_northline-verify";
    public static final String FORMAT = "Enter a domain like book.yourbusiness.ca.";
    public static final String TAKEN = "That domain is already connected to another page.";
    public static final String BLOCKED = "That domain can't be connected to a Northline page.";
    public static final String UNVERIFIED = "Point the CNAME at pages.northline.ca and verify it before publishing.";

    private static final Pattern HOST = Pattern.compile(
            "^(?=.{4,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:[a-z]{2,63}|xn--[a-z0-9-]{1,59})$");

    /**
     * Second-level public suffixes under which a three-label name is still a registrable (apex) domain: the Canadian
     * provincial and federal ones (the merchants' market) and the common foreign ones. Anything else with three or
     * more labels is treated as a subdomain.
     */
    private static final Set<String> SECOND_LEVEL = Set.of(
            "ab.ca", "bc.ca", "mb.ca", "nb.ca", "nf.ca", "nl.ca", "ns.ca", "nt.ca", "nu.ca", "on.ca", "pe.ca", "qc.ca",
            "sk.ca", "yk.ca", "gc.ca", "co.uk", "org.uk", "com.au", "co.nz", "com.mx", "com.br", "co.jp");

    /** {@code merchants.storefronts.custom_domain_status}. */
    public enum Status implements CodedEnum {
        /** Waiting for the TXT and CNAME records. */
        PENDING,
        /** Ownership and routing proven; not on the edge yet (page unpublished, business not active, queued). */
        VERIFIED,
        /** On the edge, the certificate is being issued. */
        ISSUING,
        /** Serving over TLS. */
        LIVE,
        /** The certificate could not be issued; re-checked every few hours a few times, then on request. */
        FAILED,
        /** Never verified within the verification window; re-checked on request only. */
        EXPIRED;

        /** DNS proved ownership and routing (the spec's "CNAME verification" before publishing). */
        public boolean proven() {
            return this == VERIFIED || this == ISSUING || this == LIVE;
        }
    }

    public CustomDomain {
        var host = value.strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("^https?://", "")
                .replaceAll("[/?#].*$", "")
                .replaceAll("\\.$", "");
        try {
            host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException _) {
            throw RuleViolation.of(FIELD, "format", FORMAT);
        }
        if (!HOST.matcher(host).matches() || within(host, "northline.ca")) {
            throw RuleViolation.of(FIELD, "format", FORMAT);
        }
        value = host;
    }

    /** Where the ownership TXT record goes: {@code _northline-verify.<domain>}. */
    public String verificationName() {
        return VERIFY_LABEL + "." + value;
    }

    /** A registrable domain itself ({@code example.ca}, {@code example.on.ca}): no CNAME possible at the apex. */
    public boolean isApex() {
        var labels = value.split("\\.");
        return labels.length == 2 || (labels.length == 3 && SECOND_LEVEL.contains(labels[1] + "." + labels[2]));
    }

    /** {@code host} is {@code zone} or a name under it. */
    public static boolean within(String host, String zone) {
        return host.equals(zone) || host.endsWith("." + zone);
    }
}
