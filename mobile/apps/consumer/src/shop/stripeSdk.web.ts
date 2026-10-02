import type * as StripeSdk from '@stripe/stripe-react-native';

/** The web build (fixture smoke test only): Stripe's React Native SDK is native-only, so there is none. */
export function stripeSdk(): typeof StripeSdk | null {
  return null;
}
