import type { Locale, Translate } from '@northline/ui';
import { formatMoney, TIME_ZONE } from '@northline/ui';
import type { PricingMode } from './api';

type PriceKeys = 'priceQuote' | 'priceFrom' | 'perHour' | 'priceFree';

/** "$89" · "$45/h" · "Quote" · "Free" (whole dollars when round, as the design shows). */
export function price(t: Translate<PriceKeys>, locale: Locale, mode: PricingMode, cents: number | null | undefined, from = false): string {
  if (mode === 'quote' || cents === null || cents === undefined) return t('priceQuote');
  if (cents === 0) return t('priceFree');
  const money = formatMoney(cents, locale, { whole: cents % 100 === 0 });
  const text = mode === 'hourly' ? t('perHour', { price: money }) : money;
  return from ? t('priceFrom', { price: text }) : text;
}

const INTL: Record<Locale, string> = { en: 'en-CA', fr: 'fr-CA' };
const dayKey = (d: Date) => new Intl.DateTimeFormat('en-CA', { timeZone: TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit' }).format(d);

/** A short time as the design writes it: "3 pm", "9:30 am" (en) · "15 h", "9 h 30" (fr). */
export function shortTime(at: Date, locale: Locale): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: TIME_ZONE, hour: 'numeric', minute: '2-digit', hourCycle: 'h23' }).formatToParts(at);
  const h = Number(parts.find(p => p.type === 'hour')?.value ?? 0);
  const m = parts.find(p => p.type === 'minute')?.value ?? '00';
  if (locale === 'fr') return m === '00' ? `${h} h` : `${h} h ${m}`;
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${h12}${m === '00' ? '' : `:${m}`} ${h < 12 ? 'am' : 'pm'}`;
}

type AvailKeys = 'availToday' | 'availTomorrow' | 'availDay' | 'noOpenings';

/** "Today 3 pm" · "Tomorrow 9 am" · "Thu 9 am" (in the market's time zone), relative to `now`. */
export function nextAvailable(t: Translate<AvailKeys>, locale: Locale, iso: string | null | undefined, now = new Date()): string {
  if (!iso) return t('noOpenings');
  const at = new Date(iso);
  const time = shortTime(at, locale);
  const today = dayKey(now);
  const tomorrow = dayKey(new Date(now.getTime() + 86_400_000));
  if (dayKey(at) === today) return t('availToday', { time });
  if (dayKey(at) === tomorrow) return t('availTomorrow', { time });
  const day = new Intl.DateTimeFormat(INTL[locale], { timeZone: TIME_ZONE, weekday: 'short' }).format(at);
  return t('availDay', { day, time });
}

/** "98%" / "98 %" — quality percentages, whole unless below 10 (dispute rates: "0.3%"). */
export function percent(value: number, locale: Locale): string {
  return new Intl.NumberFormat(INTL[locale], { style: 'percent', maximumFractionDigits: value < 10 ? 1 : 0 }).format(value / 100);
}

/** The average rating with one decimal ("4.9" / "4,9"). */
export function rating(value: number, locale: Locale): string {
  return new Intl.NumberFormat(INTL[locale], { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(value);
}
