import type * as StripeSdk from '@stripe/stripe-react-native';

/**
 * Stripe's React Native SDK, required only when a payment needs it (Stripe configured): the native module stays off
 * the paths of the fixture and local builds. The web build (the smoke test) has its own file: the SDK is native-only.
 */
export function stripeSdk(): typeof StripeSdk | null {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  return require('@stripe/stripe-react-native') as typeof StripeSdk;
}
