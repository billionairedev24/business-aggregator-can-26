import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';

/**
 * S-152 loyalty for the consumer site:
 *   GET    /api/v1/me/points?limit=        balance, value, next expiry, the rules, activity
 *   GET    /api/v1/me/referral             the person's invite code, link and how it went
 *   POST   /api/v1/me/referral/claim       {code} — a new neighbour uses a code (422 with the reason)
 *   GET    /api/v1/me/plus                 the household's membership, prices and benefits
 *   POST   /api/v1/me/plus                 {plan} (Idempotency-Key) → clientSecret when 3-D Secure is needed
 *   PATCH  /api/v1/me/plus                 {plan} (Idempotency-Key) — S-162: from the next renewal (nextPlan)
 *   DELETE /api/v1/me/plus · POST /api/v1/me/plus/resume
 *   PUT    /api/v1/me/plus/card            {paymentMethodId|null} — S-162: the saved card Plus renews on
 *   POST   /api/v1/me/plus/confirm         S-162: after Stripe.js confirmed the waiting payment with the bank
 *   GET    /api/v1/public/rewards?city=&lat=&lng=   provider-funded rewards near the place
 *   GET    /api/v1/public/plus-prices?offers=       shops' Plus member prices (% off) of the offers on screen
 */

export const Activity = z.object({
  id: z.string(), type: z.string(), points: z.number().int(), note: z.string().nullish(), at: z.string(),
  expiresAt: z.string().nullish(), fundedBy: z.string().nullish(),
});
export type Activity = z.infer<typeof Activity>;
export const MyPoints = z.object({
  balance: z.number().int(), valueCents: z.number().int(), nextExpiryAt: z.string().nullish(), nextExpiryPoints: z.number().int(),
  pointsPerDollarSpent: z.number().int(), redeemPointsPerDollar: z.number().int(), expiryMonths: z.number().int().nullish(),
  plusMultiplierPct: z.number().int(), activity: z.array(Activity),
});
export type MyPoints = z.infer<typeof MyPoints>;
export const pointsQuery = queryOptions({
  queryKey: ['me', 'points'],
  queryFn: () => http('/api/v1/me/points?limit=50', {}, MyPoints),
  staleTime: 30_000,
});

export const Referral = z.object({
  code: z.string(), shareUrl: z.string(), pointsEach: z.number().int(), minOrderCents: z.number().int(),
  pending: z.number().int(), rewarded: z.number().int(), pointsEarned: z.number().int(),
  claimed: z.object({ code: z.string(), state: z.enum(['pending', 'rewarded', 'refused']), claimedAt: z.string(), reason: z.string().nullish() }).nullish(),
  canClaim: z.boolean(),
});
export type Referral = z.infer<typeof Referral>;
export const referralQuery = queryOptions({ queryKey: ['me', 'referral'], queryFn: () => http('/api/v1/me/referral', {}, Referral) });

export function useClaimReferral() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => http('/api/v1/me/referral/claim', { method: 'POST', body: { code } }, Referral),
    onSuccess: data => { qc.setQueryData(referralQuery.queryKey, data); forgetInvite(); },
  });
}

export const Benefits = z.object({ member: z.boolean(), serviceFeeWaived: z.boolean(), memberPrices: z.boolean(), pointsMultiplierPct: z.number().int() });
export const Plus = z.object({
  status: z.enum(['none', 'incomplete', 'trialing', 'active', 'past_due', 'canceled']),
  plan: z.enum(['monthly', 'annual']).nullish(), provider: z.enum(['local', 'stripe']).catch('local'),
  since: z.string().nullish(), currentPeriodEnd: z.string().nullish(), cancelAtPeriodEnd: z.boolean(), payer: z.boolean(),
  members: z.number().int(), monthlyPriceCents: z.number().int(), annualPriceCents: z.number().int(), trialDays: z.number().int(),
  trialAvailable: z.boolean(), benefits: Benefits, clientSecret: z.string().nullish(), publishableKey: z.string().nullish(),
  // S-162: Northline bills Plus itself — what the payer sees of it
  nextPlan: z.enum(['monthly', 'annual']).nullish(), nextChargeAt: z.string().nullish(), nextChargeCents: z.number().int().catch(0),
  card: z.object({ id: z.string(), brand: z.string(), last4: z.string(), chosen: z.boolean() }).nullish(),
  payment: z.object({
    periodId: z.string(), reason: z.string(), totalCents: z.number().int(), retryAt: z.string().nullish(), lapsesAt: z.string(),
  }).nullish(),
  history: z.array(z.object({
    id: z.string(), startsAt: z.string(), endsAt: z.string(), plan: z.enum(['monthly', 'annual']), state: z.string(),
    amountCents: z.number().int(), taxCents: z.number().int(), paidAt: z.string().nullish(),
  })).catch([]),
});
export type Plus = z.infer<typeof Plus>;
export const plusQuery = queryOptions({ queryKey: ['me', 'plus'], queryFn: () => http('/api/v1/me/plus', {}, Plus) });

function usePlusMutation<A>(fn: (a: A) => Promise<Plus>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: async data => {
      qc.setQueryData(plusQuery.queryKey, data);
      await Promise.all([['me', 'household'], ['me', 'wallet'], ['me', 'account-summary'], ['me', 'points'], ['cart']]
        .map(queryKey => qc.invalidateQueries({ queryKey })));
    },
  });
}

/** One key per attempt: a retry of the same click replays, a new click is a new request. */
const newKey = () => (globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`);

export const useJoinPlus = () => usePlusMutation((plan: 'monthly' | 'annual') =>
  http('/api/v1/me/plus', { method: 'POST', body: { plan }, idempotencyKey: newKey() }, Plus));
export const useChangePlus = () => usePlusMutation((plan: 'monthly' | 'annual') =>
  http('/api/v1/me/plus', { method: 'PATCH', body: { plan }, idempotencyKey: newKey() }, Plus));
export const useCancelPlus = () => usePlusMutation((_: void) => http('/api/v1/me/plus', { method: 'DELETE' }, Plus));
export const useResumePlus = () => usePlusMutation((_: void) => http('/api/v1/me/plus/resume', { method: 'POST' }, Plus));
export const usePlusCard = () => usePlusMutation((paymentMethodId: string | null) =>
  http('/api/v1/me/plus/card', { method: 'PUT', body: { paymentMethodId } }, Plus));
export const useConfirmPlus = () => usePlusMutation((_: void) => http('/api/v1/me/plus/confirm', { method: 'POST' }, Plus));

export const NearbyReward = z.object({
  merchantId: z.string(), businessName: z.string(), type: z.string(), slug: z.string().nullish(), multiplier: z.number().int(),
  label: z.string().nullish(), endsOn: z.string(), distanceKm: z.number().nullish(),
});
export type NearbyReward = z.infer<typeof NearbyReward>;
export const rewardsNearQuery = (place: { city?: string | null; lat?: number | null; lng?: number | null }) => queryOptions({
  queryKey: ['public', 'rewards', place.city ?? '', place.lat ?? '', place.lng ?? ''],
  queryFn: () => {
    const q = new URLSearchParams({ limit: '6' });
    if (place.city) q.set('city', place.city);
    if (place.lat != null && place.lng != null) { q.set('lat', String(place.lat)); q.set('lng', String(place.lng)); }
    return http(`/api/v1/public/rewards?${q.toString()}`, {}, z.object({ items: z.array(NearbyReward) }));
  },
  staleTime: 60_000,
});

/** A member price: the shop's price less `pct` %, rounded to the cent (the server's rule). */
export const memberPrice = (cents: number, pct: number) => cents - Math.round((cents * pct) / 100);

// The cards on a page ask for their offers' member prices together: one request per tick.
const pending = new Map<string, ((pct: number | null) => void)[]>();
let timer: ReturnType<typeof setTimeout> | null = null;
function flush() {
  timer = null;
  const batch = new Map(pending);
  pending.clear();
  const ids = [...batch.keys()];
  http(`/api/v1/public/plus-prices?offers=${ids.map(encodeURIComponent).join(',')}`, {}, z.record(z.string(), z.number().int()))
    .then(found => batch.forEach((resolvers, id) => resolvers.forEach(r => r(found[id] ?? null))))
    .catch(() => batch.forEach(resolvers => resolvers.forEach(r => r(null))));
}
function loadPct(offerId: string): Promise<number | null> {
  return new Promise(resolve => {
    pending.set(offerId, [...(pending.get(offerId) ?? []), resolve]);
    timer ??= setTimeout(flush, 0);
  });
}

/** The shop's Plus member discount (% off) on `offerId`, null when it offers none. */
export function usePlusPct(offerId: string | null | undefined) {
  return useQuery({
    queryKey: ['public', 'plus-price', offerId ?? ''],
    queryFn: () => loadPct(offerId!),
    enabled: !!offerId,
    staleTime: 60_000,
  }).data ?? null;
}

// ── an invite link (?invite=CODE) is remembered until the person uses it in their account ─────────────────────────
const INVITE = 'nl.invite';
export function rememberInvite(search: string) {
  try {
    const code = new URLSearchParams(search).get('invite');
    if (code && /^[A-Za-z0-9-]{4,16}$/.test(code)) window.localStorage.setItem(INVITE, code.toUpperCase());
  } catch { /* storage unavailable: the person types the code */ }
}
export function rememberedInvite(): string {
  try { return window.localStorage.getItem(INVITE) ?? ''; } catch { return ''; }
}
export function forgetInvite() {
  try { window.localStorage.removeItem(INVITE); } catch { /* nothing to forget */ }
}
