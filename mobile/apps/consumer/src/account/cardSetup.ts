import type { CardSetup } from '../api/account';
import { config } from '../config';
import { stripeSdk } from '../shop/stripeSdk';

/**
 * Saving a card without paying (S-59's SetupIntent), the port. The api opens the SetupIntent and says which provider
 * runs it (`provider`, the server's `northline.payments` configuration, as for paying — S-99):
 *   - `stripe` → Stripe's React Native SDK: the card is entered in PaymentSheet in setup mode (native, outside the
 *     app's JS — Northline sees the PaymentMethod's brand and last four), then the api keeps it
 *     (`POST /me/payment-methods {setupIntentId}`);
 *   - `fake` → the api's stand-in: nothing is collected, the api keeps a test card.
 */
export type SetupResult = { status: 'confirmed' } | { status: 'cancelled' } | { status: 'failed'; message?: string };

export interface CardSetupCollector {
  collect(setup: CardSetup): Promise<SetupResult>;
}

const SCHEME = config.redirectUri.split(':')[0]!;

export const stripeCardSetup: CardSetupCollector = {
  async collect(setup) {
    const stripe = stripeSdk();
    if (!stripe || !setup.publishableKey || !setup.clientSecret) return { status: 'failed' };
    await stripe.initStripe({ publishableKey: setup.publishableKey, urlScheme: SCHEME, setReturnUrlSchemeOnAndroid: true });
    const init = await stripe.initPaymentSheet({
      setupIntentClientSecret: setup.clientSecret,
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
    return { status: 'confirmed' };
  },
};

let override: CardSetupCollector | null = null;

/** The collector for the api's provider (`null` = the stand-in: nothing to collect). */
export function cardSetupFor(provider: 'stripe' | 'fake'): CardSetupCollector | null {
  if (provider !== 'stripe') return null;
  return override ?? stripeCardSetup;
}

/** Tests: use this collector instead of Stripe's SDK. */
export function setCardSetup(c: CardSetupCollector | null) {
  override = c;
}
