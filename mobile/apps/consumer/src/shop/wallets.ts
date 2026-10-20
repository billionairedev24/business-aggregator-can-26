import { walletBuild } from '../config';
import { shop } from './common';

/**
 * S-157: Apple Pay and Google Pay in Stripe's PaymentSheet, for every payment the app makes with it (shop, bookings,
 * quote deposits, tips). Offered only when the api allows them for the environment (`GET /public/payments/options`:
 * Stripe configured and the wallet switched on, docs/runbooks/stripe.md § 13) **and** this build carries what the
 * wallet needs (the Apple Pay merchant id, the Google Pay flag). A wallet is a card to Stripe: the same manual-capture
 * PaymentIntents, the same step-up before them; the PaymentMethod it leaves confirms the order's other intents.
 */
export interface Wallets { applePay: boolean; googlePay: boolean }
export const NO_WALLETS: Wallets = { applePay: false, googlePay: false };

let cached: Promise<Wallets> | null = null;

/** The api's answer, asked once per app run; any failure means cards only (asked again next time). */
export function walletsAllowed(): Promise<Wallets> {
  cached ??= shop()
    .paymentOptions()
    .then((o) => ({ applePay: o?.applePay === true, googlePay: o?.googlePay === true }))
    .catch(() => {
      cached = null;
      return NO_WALLETS;
    });
  return cached;
}

/** Tests: the api's answer to use (null = ask the api again). */
export function setWalletsForTests(w: Wallets | null) {
  cached = w ? Promise.resolve(w) : null;
}

/** `initStripe`'s Apple Pay merchant id, when this build has one. */
export const merchantIdentifier = () => walletBuild().applePayMerchantId ?? undefined;

/** PaymentSheet's wallet options: only what both the api and the build allow (Canada, CAD). */
export function sheetWallets(allowed: Wallets) {
  const build = walletBuild();
  return {
    ...(allowed.applePay && build.applePayMerchantId ? { applePay: { merchantCountryCode: 'CA' } } : {}),
    ...(allowed.googlePay && build.googlePay
      ? { googlePay: { merchantCountryCode: 'CA', currencyCode: 'CAD', testEnv: build.googlePayTest } }
      : {}),
  };
}
