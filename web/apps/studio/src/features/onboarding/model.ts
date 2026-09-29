import type { MerchantType } from '../shell/api';

/** Wizard steps (design 02 `obKeys`); the URL segment of `/onboarding/$step`. */
export const STEPS = ['account', 'business', 'verification', 'review', 'page', 'listings'] as const;
export type Step = (typeof STEPS)[number];
export const isStep = (s: string): s is Step => (STEPS as readonly string[]).includes(s);

/** Server `onboarding_step` → how far the rail is clickable. */
export type ServerStep = Step | 'done';
export const stepIndex = (s: ServerStep) => (s === 'done' ? STEPS.length - 1 : STEPS.indexOf(s));

/** Type picker order (design 02 `bizTypes`: Sell products · Offer services · Both · Food · kitchen). */
export const PICKER_TYPES: readonly MerchantType[] = ['seller', 'provider', 'both', 'kitchen'];
export const isMerchantType = (s: unknown): s is MerchantType => s === 'provider' || s === 'seller' || s === 'kitchen' || s === 'both';

export interface OnboardingSearch { type?: MerchantType; m?: string; new?: boolean }

export function validateOnboardingSearch(search: Record<string, unknown>): OnboardingSearch {
  return {
    type: isMerchantType(search.type) ? search.type : undefined,
    m: typeof search.m === 'string' && /^[0-9A-HJKMNP-TV-Z]{26}$/.test(search.m) ? search.m : undefined,
    new: search.new === true || search.new === '1' || search.new === 1 ? true : undefined,
  };
}

/** Studio home after "Go live" (kitchens land on Live orders). */
export const studioHome = (merchantId: string, type: MerchantType) => (type === 'kitchen' ? `/b/${merchantId}/kitchen/live` : `/b/${merchantId}`);

/** Next business days at 10:00 and 14:00 in Calgary, starting tomorrow, for the kitchen-visit booking. */
export function visitSlots(now: Date, count = 6): Date[] {
  const out: Date[] = [];
  const day = new Date(now);
  while (out.length < count) {
    day.setUTCDate(day.getUTCDate() + 1);
    const dow = day.getUTCDay();
    if (dow === 0 || dow === 6) continue;
    for (const hour of [16, 20]) { // 10:00 and 14:00 MDT (UTC−6); good enough for a slot list, the server re-checks
      const slot = new Date(Date.UTC(day.getUTCFullYear(), day.getUTCMonth(), day.getUTCDate(), hour));
      if (out.length < count) out.push(slot);
    }
  }
  return out;
}

/** Dollars typed by a person ("$89", "1,200.50") → cents; undefined when not a price. */
export function parseDollars(text: string): number | undefined {
  const clean = text.replace(/[$\s,]/g, '').replace(/^(\d+),(\d{1,2})$/, '$1.$2');
  if (!/^\d+(\.\d{1,2})?$/.test(clean)) return undefined;
  return Math.round(Number.parseFloat(clean) * 100);
}
