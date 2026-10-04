import { Redirect } from 'expo-router';

/**
 * `ca.northline.app:/age-verified` (2026-10-04): where the identity provider sends the customer after the ID check.
 * The in-app browser's auth session usually takes it (src/shop/AgeCheck.tsx re-reads the checkout); Android may also
 * hand it to the router as a link, and this route goes back to Checkout.
 */
export default function AgeVerified() {
  return <Redirect href="/checkout" />;
}
