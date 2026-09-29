package ca.northline.booking.domain;

import ca.northline.shared.CodedEnum;

/** Coded enums of {@code booking.quotes} / {@code booking.quote_lines}. */
public final class QuoteEnums {
    private QuoteEnums() {}

    /** {@code quote_lines.kind}. */
    public enum LineKind implements CodedEnum {
        LABOUR,
        PART,
        FEE,
        TRAVEL,
        DISCOUNT
    }

    /** {@code quotes.warranty}. */
    public enum Warranty implements CodedEnum {
        NONE,
        LABOUR_90D,
        PARTS_LABOUR_12M,
        MANUFACTURER
    }

    /** {@code quotes.deposit_kind}: none (full amount to escrow on accept), parts cost up front, or a percentage. */
    public enum DepositKind implements CodedEnum {
        NONE,
        PARTS_UPFRONT,
        PCT
    }

    /** {@code quotes.state}. Revisions create a new row and mark the prior one {@code superseded}. */
    public enum QuoteState implements CodedEnum {
        DRAFT,
        SENT,
        VIEWED,
        ACCEPTED,
        DECLINED,
        EXPIRED,
        SUPERSEDED;

        /** Sent and still in front of the customer. */
        public boolean isOpen() {
            return this == SENT || this == VIEWED;
        }
    }
}
