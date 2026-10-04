package ca.northline.booking.api;

import java.util.Collection;
import java.util.Map;

/**
 * The money held for a merchant's jobs, as the payments ledger has it (engineering follow-ups, S-117 finding). The
 * payments module implements it over {@code payments.escrows}; it is declared here because payments already depends
 * on booking (escrow release on completion and sign-off), so booking calling payments.api would be a module cycle.
 */
public interface JobEscrows {

    /** The escrows of the given bookings of one merchant, by booking id; bookings without one are left out. */
    Map<String, Held> of(String merchantId, Collection<String> bookingIds);

    /**
     * What the customer paid for the job and what the merchant will get from it.
     *
     * @param state {@code held | released | refunded | disputed}
     * @param heldCents what the customer was charged for the job and is held: the price plus its GST/HST
     * @param taxCents the GST/HST in {@code heldCents}, which Northline remits
     * @param feeCents Northline's fee at the merchant's take rate
     * @param netCents what the merchant receives: {@code heldCents − taxCents − feeCents}
     */
    record Held(String state, long heldCents, long taxCents, long feeCents, long netCents) {}
}
