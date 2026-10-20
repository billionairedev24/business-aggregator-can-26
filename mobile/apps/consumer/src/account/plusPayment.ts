import { config } from '../config';
import { stripeSdk } from '../shop/stripeSdk';

/**
 * Confirms a Plus subscription's first payment with the bank (S-152): Stripe Billing's first invoice can need 3-D
 * Secure, and the api then answers with the invoice PaymentIntent's client secret. Stripe's SDK confirms it with the
 * card already on the customer (the web's `confirmCardPayment(secret)`) and shows the bank's step itself. A port so
 * tests can stand in for the native SDK; never run against real Stripe in CI.
 */
export interface PlusPayment {
  confirm(clientSecret: string, publishableKey: string): Promise<{ ok: true } | { ok: false; message?: string }>;
}

const SCHEME = config.redirectUri.split(':')[0]!;

export const stripePlusPayment: PlusPayment = {
  async confirm(clientSecret, publishableKey) {
    const stripe = stripeSdk();
    if (!stripe) return { ok: false };
    await stripe.initStripe({ publishableKey, urlScheme: SCHEME, setReturnUrlSchemeOnAndroid: true });
    const r = await stripe.handleNextAction(clientSecret, `${SCHEME}://stripe-redirect`);
    return r.error ? { ok: false, message: r.error.localizedMessage ?? r.error.message } : { ok: true };
  },
};

let current: PlusPayment = stripePlusPayment;
export const plusPayment = () => current;
/** Tests: a stand-in (null puts Stripe's back). */
export function setPlusPayment(p: PlusPayment | null) {
  current = p ?? stripePlusPayment;
}
