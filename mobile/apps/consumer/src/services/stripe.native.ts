import type * as StripeSdk from '@stripe/stripe-react-native';

import { config } from '../config';
import type { BookingPayments } from './payments';

/**
 * Stripe's React Native SDK behind the booking payment port (iOS and Android only: Metro picks this file for the
 * native platforms; `stripe.ts` stands in on the web build, where the native module can't load).
 */
const SCHEME = config.redirectUri.split(':')[0]!;

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
