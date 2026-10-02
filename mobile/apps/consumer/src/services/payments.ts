import type { Checkout } from '../api/services';
import { stripeBookingPayments } from './stripe';

/**
 * The booking's card payment (the port), as on the consumer web (S-55): the api opens one manual-capture PaymentIntent
 * for the escrow hold and says who runs it (`provider`, from the server's `northline.payments` configuration):
 *   - `stripe` → `stripeBookingPayments` (`stripe.native.ts`): Stripe's React Native SDK. A new card goes into
 *     Stripe's PaymentSheet (native, outside the app's JavaScript), a saved card is confirmed by its PaymentMethod id;
 *     Stripe shows 3-D Secure itself when the bank asks. Northline never sees a card number.
 *   - `fake` → nothing to collect: the api's stand-in authorized the intent already (local profile, the fixture backend).
 */
export type PayWith = { kind: 'saved'; paymentMethodId: string } | { kind: 'new' };
export type PayResult = { status: 'paid' } | { status: 'cancelled' } | { status: 'failed'; message?: string };

export interface BookingPayments {
  /** Authorizes the checkout's PaymentIntent with the card. */
  pay(checkout: Checkout, method: PayWith): Promise<PayResult>;
}

let override: BookingPayments | null = null;

/** The adapter for the api's provider; `null` for the stand-in (nothing to collect). */
export function bookingPaymentsFor(provider: 'stripe' | 'fake'): BookingPayments | null {
  if (override) return override;
  return provider === 'stripe' ? stripeBookingPayments : null;
}

/** Tests: use this adapter instead of Stripe's SDK. */
export function setBookingPayments(p: BookingPayments | null) {
  override = p;
}
