package ca.northline.merchants.domain;

import org.jspecify.annotations.Nullable;

/** What DNS says about a claimed domain right now: the ownership TXT record and where the name points. */
public record DnsFindings(Txt txt, Pointing pointing) {

    public enum Txt {
        /** A TXT value equals the claim's token. */
        FOUND,
        MISSING,
        /** TXT values exist, none is the token (another claim's, or a typo). */
        MISMATCH,
        /** The resolver failed or timed out. */
        UNKNOWN
    }

    public enum Pointing {
        /** The CNAME chain reaches {@code pages.<zone>}. */
        CNAME,
        /** Every A/AAAA address of the name is one of the edge's (ALIAS/ANAME/flattening, or A records). */
        ADDRESS,
        /** It resolves, but not (only) to us. */
        ELSEWHERE,
        /** No CNAME, A or AAAA record. */
        MISSING,
        UNKNOWN
    }

    /** Ownership and routing both proven. */
    public boolean ok() {
        return txt == Txt.FOUND && (pointing == Pointing.CNAME || pointing == Pointing.ADDRESS);
    }

    /**
     * Not proven, but only because DNS could not be asked: nothing definitive is wrong, so a proven domain keeps its
     * state and the check is simply retried.
     */
    public boolean inconclusive() {
        return !ok()
                && txt != Txt.MISSING
                && txt != Txt.MISMATCH
                && pointing != Pointing.ELSEWHERE
                && pointing != Pointing.MISSING;
    }

    /** The first thing to fix (the ownership record first), or null when {@link #ok()}. */
    public @Nullable DomainProblem problem() {
        return switch (txt) {
            case MISSING -> DomainProblem.TXT_MISSING;
            case MISMATCH -> DomainProblem.TXT_MISMATCH;
            case UNKNOWN, FOUND ->
                switch (pointing) {
                    case MISSING -> DomainProblem.NO_RECORD;
                    case ELSEWHERE -> DomainProblem.NOT_POINTING;
                    case UNKNOWN -> DomainProblem.DNS_ERROR;
                    case CNAME, ADDRESS -> txt == Txt.UNKNOWN ? DomainProblem.DNS_ERROR : null;
                };
        };
    }
}
