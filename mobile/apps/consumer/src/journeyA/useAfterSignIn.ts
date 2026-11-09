import { router } from 'expo-router';
import { useCallback } from 'react';


/**
 * Where a sign-in lands: Home (S-190 "Location is never required": there is no forced location step; the place bar
 * offers the location sheet, A5 — the only screen that may ask for the phone's position, and only on a press).
 */
export function useAfterSignIn() {
  return useCallback(() => router.replace('/home'), []);
}
