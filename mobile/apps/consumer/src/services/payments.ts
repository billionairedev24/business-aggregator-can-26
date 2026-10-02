import type * as StripeSdk from '@stripe/stripe-react-native';

import { config } from '../config';
import type { Checkout } from '../api/services';

/**
 * The booking's card payment (the port), as on the consumer web (S-55): the api opens one manual-capture PaymentIntent
 * for the escrow hold and says who runs it (`provider`, from the server's `northline.payments` configuration):
 *   - `stripe` → {@link stripeBookingPayments}: Stripe's React Native SDK. A new card goes into Stripe's PaymentSheet
 *     (native, outside the app's JavaScript), a saved card is confirmed by its PaymentMethod id; Stripe shows 3-D Secure
 *     itself when the bank asks. Northline never sees a card number.
 *   - `fake` → nothing to collect: the api's stand-in authorized the intent already (local profile, the fixture backend).
 */
const SCHEME = config.redirectUri.split(':')[0]!;

export type PayWith = { kind: 'saved'; paymentMethodId: string } | { kind: 'new' };
export type PayResult = { status: 'paid' } | { status: 'cancelled' } | { status: 'failed'; message?: string };

export interface BookingPayments {
  /** Authorizes the checkout's PaymentIntent with the card. */
  pay(checkout: Checkout, method: PayWith): Promise<PayResult>;
}

export const stripeBookingPayments: BookingPayments = {
  async pay(checkout, method) {
    const key = checkout.publishableKey;
    const secret = checkout.clientSecret;
    if (!key || !secret) return { status: 'failed' };
    // loaded only when Stripe runs the payment: the native module stays off the fixture and local paths
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const stripe = require('@stripe/stripe-react-native') as typeof StripeSdk;
    // the app's scheme brings the person back from a bank's own page or app (3-D Secure redirects)
    await stripe.initStripe({ publishableKey: key, urlScheme: SCHEME, setReturnUrlSchemeOnAndroid: true });
    if (method.kind === 'saved') {
      const r = await stripe.confirmPayment(secret, { paymentMethodType: 'Card', paymentMethodData: { paymentMethodId: method.paymentMethodId } });
      if (!r.error) return { status: 'paid' };
      return r.error.code === 'Canceled' ? { status: 'cancelled' } : { status: 'failed', message: r.error.localizedMessage ?? r.error.message };
    }
    const init = await stripe.initPaymentSheet({
      paymentIntentClientSecret: secret,
      merchantDisplayName: 'Northline',
      returnURL: `${SCHEME}://stripe-redirect`,
      defaultBillingDetails: { address: { country: 'CA' } },
    });
    if (init.error) return { status: 'failed', message: init.error.localizedMessage ?? init.error.message };
    const shown = await stripe.presentPaymentSheet();
    if (!shown.error) return { status: 'paid' };
    return shown.error.code === stripe.PaymentSheetError.Canceled
      ? { status: 'cancelled' }
      : { status: 'failed', message: shown.error.localizedMessage ?? shown.error.message };
  },
};

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
