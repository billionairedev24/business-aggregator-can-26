import { router } from 'expo-router';
import { useCallback } from 'react';

import { defaultAddress, useDeliveryLocation } from '../location/DeliveryLocation';

/**
 * Where a sign-in lands: Home when this phone already knows where to deliver (a saved place, or the account's default
 * address), else the location sheet (A5) — the only screen that may ask for the phone's position, and only on a press.
 */
export function useAfterSignIn() {
  const { location } = useDeliveryLocation();
  return useCallback(() => {
    if (location.status === 'saved' || location.status === 'address') return router.replace('/home');
    void defaultAddress().then((a) => router.replace(a ? '/home' : '/location'));
  }, [location.status]);
}
