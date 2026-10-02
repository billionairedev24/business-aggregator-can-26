import * as Linking from 'expo-linking';
import { router } from 'expo-router';
import { useEffect } from 'react';

import { stripeSdk } from '../src/shop/stripeSdk';
import { Loading } from '../src/ui/states';

/**
 * `ca.northline.app://stripe-redirect…`: a bank's app or page sending the person back after 3-D Secure (the
 * `returnURL` of Stripe's PaymentSheet, S-99). The link reaches the router; Stripe's SDK finishes the authentication
 * with it, and the person is back on Payment.
 */
export default function StripeRedirect() {
  const url = Linking.useURL();
  useEffect(() => {
    if (!url) return;
    void stripeSdk()
      ?.handleURLCallback(url)
      .catch(() => false);
    if (router.canGoBack()) router.back();
    else router.replace('/cart');
  }, [url]);
  return <Loading />;
}
