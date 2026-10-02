import { router } from 'expo-router';
import { useCallback } from 'react';

import { useDeliveryLocation } from '../location/DeliveryLocation';

/** Where a sign-in lands: Home when the delivery address is already set on this phone, else the Location screen (A5). */
export function useAfterSignIn() {
  const { location } = useDeliveryLocation();
  return useCallback(() => router.replace(location.status === 'saved' ? '/home' : '/location'), [location.status]);
}
