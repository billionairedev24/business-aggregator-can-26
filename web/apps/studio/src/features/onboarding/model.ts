import { addDays, isoWeekday, timeZone, zonedInstant } from '@northline/ui';
import type { MerchantType } from '../shell/api';

/**
 * Wizard steps (redesign § 3.10, AfroTransact BO:113 → BO:235): account → business → categories → verification →
 * review, where Review is the approval gate. The public page and the first listings come after approval, in the
 * normal Studio. `categories` is the second half of the server's Business step (one `PUT …/onboarding/business`).
 */
export const STEPS = ['account', 'business', 'categories', 'verification', 'review'] as const;
export type Step = (typeof STEPS)[number];
export const isStep = (s: string): s is Step => (STEPS as readonly string[]).includes(s);
/** The old wizard's last two steps: their links open the Studio screens (approved) or the gate. */
export const LEGACY_STEPS = ['page', 'listings'] as const;
export type LegacyStep = (typeof LEGACY_STEPS)[number];
export const isLegacyStep = (s: string): s is LegacyStep => (LEGACY_STEPS as readonly string[]).includes(s);

/** Server `onboarding_step` (unchanged: page, listings and done stay in the api) → the wizard step it resumes on. */
export type ServerStep = 'account' | 'business' | 'verification' | 'review' | 'page' | 'listings' | 'done';
export const resumeStep = (s: ServerStep): Step => (s === 'account' || s === 'business' || s === 'verification' ? s : 'review');
/** How far the rail is clickable: the Business step's server state also opens Categories. */
export const stepIndex = (s: ServerStep) => (s === 'business' ? STEPS.indexOf('categories') : STEPS.indexOf(resumeStep(s)));

/** Type picker order (design 02 `bizTypes`: Sell products · Offer services · Both · Food · kitchen). */
export const PICKER_TYPES: readonly MerchantType[] = ['seller', 'provider', 'both', 'kitchen'];
export const isMerchantType = (s: unknown): s is MerchantType => s === 'provider' || s === 'seller' || s === 'kitchen' || s === 'both';

/** `pilot`: the token of a pilot invite link (S-120) — the Account step pre-fills the type and province from it. */
export interface OnboardingSearch { type?: MerchantType; m?: string; new?: boolean; identity?: 'returned'; pilot?: string }

export function validateOnboardingSearch(search: Record<string, unknown>): OnboardingSearch {
  return {
    type: isMerchantType(search.type) ? search.type : undefined,
    m: typeof search.m === 'string' && /^[0-9A-HJKMNP-TV-Z]{26}$/.test(search.m) ? search.m : undefined,
    new: search.new === true || search.new === '1' || search.new === 1 ? true : undefined,
    identity: search.identity === 'returned' ? 'returned' : undefined,
    pilot: typeof search.pilot === 'string' && /^[A-Za-z0-9_-]{20,100}$/.test(search.pilot) ? search.pilot : undefined,
  };
}

/** Studio home after "Open your Studio" (kitchens land on Live orders). */
export const studioHome = (merchantId: string, type: MerchantType) => (type === 'kitchen' ? `/b/${merchantId}/kitchen/live` : `/b/${merchantId}`);

/**
 * Next business days at 10:00 and 14:00 in the business's time zone (the merchant's, set for the Studio from the
 * region model — never a zone in code), starting tomorrow, for the kitchen-visit booking. The server re-checks.
 */
export function visitSlots(now: Date, count = 6, zone: string = timeZone()): Date[] {
  const out: Date[] = [];
  let day = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
  while (out.length < count) {
    day = addDays(day, 1);
    if (isoWeekday(day) >= 6) continue;
    for (const time of ['10:00', '14:00']) if (out.length < count) out.push(new Date(zonedInstant(day, time, zone)));
  }
  return out;
}

/** Dollars typed by a person ("$89", "1,200.50") → cents; undefined when not a price. */
export function parseDollars(text: string): number | undefined {
  const clean = text.replace(/[$\s,]/g, '').replace(/^(\d+),(\d{1,2})$/, '$1.$2');
  if (!/^\d+(\.\d{1,2})?$/.test(clean)) return undefined;
  return Math.round(Number.parseFloat(clean) * 100);
}
