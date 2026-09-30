package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;

/** What currently keeps a custom domain from the next state ({@code merchants.storefronts.custom_domain_problem}). */
public enum DomainProblem implements CodedEnum {
    /** No TXT record at {@code _northline-verify.<domain>}. */
    TXT_MISSING,
    /** TXT records exist there, none holds this claim's token. */
    TXT_MISMATCH,
    /** The domain has no CNAME, A or AAAA record. */
    NO_RECORD,
    /** The domain resolves somewhere other than {@code pages.<zone>} (or a proxy in front hides it). */
    NOT_POINTING,
    /** DNS could not be asked (resolver error or timeout); retried. */
    DNS_ERROR,
    /** The certificate authority refused to issue (HTTP-01 challenge failed). */
    CERTIFICATE,
    /** Every custom-domain slot of the edge is taken ({@code DOMAINS_MAX}). */
    CAPACITY,
    /** Waiting for a certificate slot (hourly issuing limit, or this page asked for one within the hour). */
    RATE_LIMITED
}
