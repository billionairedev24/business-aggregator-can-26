import { config } from '../config';
import type { Started } from '../api/shop';
import { stripeSdk } from './stripeSdk';

/**
 * Card payments (the port). The api opens one manual-capture PaymentIntent per order line plus one for the delivery fee
 * (S-11 escrow model, S-51) and says which provider runs them (`payment.provider`, from the server's
 * `northline.payments` configuration — the consumer web's switch):
 *   - `stripe` → {@link stripeCardPayments}: Stripe's React Native SDK. A new card is entered in Stripe's PaymentSheet
 *     (native, outside the app's JS; Northline sees a PaymentMethod id and the card's brand and last four), a saved
 *     card is its PaymentMethod id; the SDK shows 3-D Secure itself when the bank asks; the order's other
 *     PaymentIntents are confirmed with the same PaymentMethod — what the web's Payment Element + confirmCardPayment do.
 *   - `fake` → no card at all: the api's stand-in authorizes every payment, and the Pay screen shows the design's bank
 *     step before placing the order (local profile, the fixture backend).
 * The app never sees a card number.
 */
/** The app's own scheme (`ca.northline.app`, from the registered redirect). */
const SCHEME = config.redirectUri.split(':')[0]!;

export type PayMethod = { kind: 'saved'; paymentMethodId: string } | { kind: 'new' };
export type PayResult = { status: 'paid' } | { status: 'cancelled' } | { status: 'failed'; message?: string };

export interface CardPayments {
  /** Authorizes every PaymentIntent of the checkout that isn't yet. */
  pay(started: Started, method: PayMethod): Promise<PayResult>;
}

/** The PaymentIntents still to authorize, with their client secrets. */
export const pendingIntents = (started: Started) => started.intents.filter((i) => i.status !== 'authorized' && i.clientSecret);

export const stripeCardPayments: CardPayments = {
  async pay(started, method) {
    const key = started.payment.publishableKey;
    if (!key) return { status: 'failed' };
    const stripe = stripeSdk();
    if (!stripe) return { status: 'failed' };
    // the app's scheme brings the person back from a bank's own app or page (3-D Secure redirects)
    await stripe.initStripe({ publishableKey: key, urlScheme: SCHEME, setReturnUrlSchemeOnAndroid: true });
    const pending = pendingIntents(started);
    let paymentMethodId = method.kind === 'saved' ? method.paymentMethodId : undefined;
    if (!paymentMethodId) {
      const first = pending.shift();
      if (!first?.clientSecret) return { status: 'paid' };
      const init = await stripe.initPaymentSheet({
        paymentIntentClientSecret: first.clientSecret,
        merchantDisplayName: 'Northline',
        returnURL: `${SCHEME}://stripe-redirect`,
        defaultBillingDetails: { address: { country: 'CA' } },
      });
      if (init.error) return { status: 'failed', message: init.error.localizedMessage ?? init.error.message };
      const shown = await stripe.presentPaymentSheet();
      if (shown.error) {
        return shown.error.code === stripe.PaymentSheetError.Canceled
          ? { status: 'cancelled' }
          : { status: 'failed', message: shown.error.localizedMessage ?? shown.error.message };
      }
      const confirmed = await stripe.retrievePaymentIntent(first.clientSecret);
      paymentMethodId = confirmed.paymentIntent?.paymentMethod?.id ?? confirmed.paymentIntent?.paymentMethodId;
      if (pending.length > 0 && !paymentMethodId) return { status: 'failed' };
    }
    for (const intent of pending) {
      const r = await stripe.confirmPayment(intent.clientSecret!, { paymentMethodType: 'Card', paymentMethodData: { paymentMethodId: paymentMethodId! } });
      if (r.error) {
        return r.error.code === 'Canceled' ? { status: 'cancelled' } : { status: 'failed', message: r.error.localizedMessage ?? r.error.message };
      }
    }
    return { status: 'paid' };
  },
};

let override: CardPayments | null = null;

/** The adapter for the api's provider (`null` = the stand-in: nothing to collect). */
export function cardPaymentsFor(provider: 'stripe' | 'fake'): CardPayments | null {
  if (override) return override;
  return provider === 'stripe' ? stripeCardPayments : null;
}

/** Tests: use this adapter instead of Stripe's SDK. */
export function setCardPayments(p: CardPayments | null) {
  override = p;
}
