import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';

/**
 * Age-restricted items (owner decision 2026-10-04): checkout's age step (`age` on the checkout set-up and quote, shop
 * and food) and the customer's one-time ID check (`/api/v1/me/age-verification`, the identity provider's hosted flow:
 * government photo ID + selfie). Only "verified over N, on date, by method" is kept.
 */
export const CheckoutAge = z.object({
  required: z.boolean(), minimumAge: z.number().int(), classes: z.array(z.string()),
  state: z.enum(['verified', 'none', 'pending', 'failed', 'under_age']),
});
export type CheckoutAge = z.infer<typeof CheckoutAge>;
export const NO_AGE: CheckoutAge = { required: false, minimumAge: 0, classes: [], state: 'verified' };

export const AgeStatus = z.object({
  state: z.enum(['none', 'pending', 'verified', 'failed']), overAge: z.number().int().nullish(), verifiedOn: z.string().nullish(),
  ageFloor: z.number().int(), method: z.string().nullish(), lastError: z.string().nullish(),
});
export type AgeStatus = z.infer<typeof AgeStatus>;
const AgeStarted = z.object({ url: z.string(), status: AgeStatus });

export const ageStatusQuery = queryOptions({
  queryKey: ['me', 'age-verification'],
  queryFn: () => http('/api/v1/me/age-verification', {}, AgeStatus),
  staleTime: 5_000,
});

/**
 * Opens a session; the caller sends the browser to `url` (single use, the provider's page).
 * @param returnTo where the provider sends the customer back: the shop checkout (`web`) or the food checkout
 */
export const startAgeCheck = (returnTo: 'web' | 'web_food' = 'web') =>
  http('/api/v1/me/age-verification', { method: 'POST', body: { returnTo } }, AgeStarted);

/** Leaves for the provider's hosted flow (a seam for tests: jsdom can't navigate). */
export const leave = { to: (url: string) => window.location.assign(url) };

/** The checkout may go on: nothing restricted, or the customer is verified old enough. */
export const ageCleared = (age: CheckoutAge | undefined) => !age || !age.required || age.state === 'verified';
