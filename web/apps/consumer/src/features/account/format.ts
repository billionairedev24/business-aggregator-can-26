import { timeZone, type Locale } from '@northline/ui';
import { FR_NAMES } from '../services/taxonomy';
import { clock, windowRange } from '../shop/format';
import type { ActivityItem } from './api';
import type { AccountT } from './messages';

const INTL: Record<Locale, string> = { en: 'en-CA', fr: 'fr-CA' };

const dayKey = (d: Date, zone: string) => new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(d);

/** "Sep 26" / "26 sept." in the market's zone. */
export const shortDate = (iso: string, locale: Locale, zone: string = timeZone()) =>
  new Intl.DateTimeFormat(INTL[locale], { timeZone: zone, month: 'short', day: 'numeric' }).format(new Date(iso));

/**
 * The "When" of a row (design 06 orderList: "Tonight 6–9 pm", "Thu 10 · 9:00", "Oct 12"): a delivery window today or
 * tomorrow in words, another coming day as weekday + time, anything past as its date.
 */
export function whenText(item: Pick<ActivityItem, 'when' | 'whenEnd' | 'active'>, t: AccountT, locale: Locale, now = new Date(), zone: string = timeZone()): string {
  const at = new Date(item.when);
  if (!item.active) return shortDate(item.when, locale, zone);
  const range = item.whenEnd ? windowRange(item.when, item.whenEnd, locale, zone) : clock(item.when, locale, zone);
  const today = dayKey(now, zone);
  const tomorrow = dayKey(new Date(now.getTime() + 86_400_000), zone);
  const day = dayKey(at, zone);
  if (day === today) {
    const hour = Number(new Intl.DateTimeFormat('en-CA', { timeZone: zone, hour: 'numeric', hourCycle: 'h23' }).format(at));
    return t(hour >= 15 ? 'when_tonight' : 'when_today', { range });
  }
  if (day === tomorrow) return t('when_tomorrow', { range });
  const weekday = new Intl.DateTimeFormat(INTL[locale], { timeZone: zone, weekday: 'short', month: 'short', day: 'numeric' }).format(at);
  return `${weekday} · ${range}`;
}

/** The "What" of a row: shop orders by their delivery, food by the kitchen, jobs and requests by their title. */
export function titleText(item: ActivityItem, t: AccountT): string {
  switch (item.kind) {
    case 'order': return t(item.delivery === 'direct' ? 'title_direct' : 'title_pooled', { shops: item.shops });
    case 'food': return item.with[0] ?? t('title_food');
    default: return item.title;
  }
}

/** The "With" of a row. */
export function withText(item: ActivityItem, t: AccountT): string {
  if (item.kind === 'food') return t(item.delivery === 'pickup' ? 'with_pickup' : 'with_delivery');
  return item.with.join(', ');
}

/** The status tag: "Packing", "Case RF-2201"… */
export function statusText(item: Pick<ActivityItem, 'status' | 'caseRef'>, t: AccountT): string {
  if (item.status === 'case') return t('status_case', { number: item.caseRef?.number ?? '' });
  const key = `status_${item.status}` as Parameters<AccountT>[0];
  const text = t(key);
  return text === key ? item.status : text;
}

/** A category id's leaf ("service.automotive.mobile-mechanic" → "Mobile mechanic" / « Mécanicien mobile »). */
export function categoryText(categoryId: string | null | undefined, locale: Locale): string | undefined {
  if (!categoryId) return undefined;
  const slug = categoryId.split('.').pop() ?? '';
  if (locale === 'fr' && FR_NAMES[slug]) return FR_NAMES[slug];
  const words = slug.replace(/-/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}
