import type { BookingPayments } from './payments';

/**
 * The web build's stand-in for Stripe's React Native SDK (a native module that can't load in a browser; the web
 * preview runs on the fixture backend's stand-in payments). iOS and Android use `stripe.native.ts`.
 */
export const stripeBookingPayments: BookingPayments = {
  pay: async () => ({ status: 'failed' }),
};
