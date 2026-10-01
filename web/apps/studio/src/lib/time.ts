import { type Locale, timeZone } from '@northline/ui';

/** Calendar date (YYYY-MM-DD) of an instant in the merchant's time zone. */
export function localDate(value: string | number | Date): string {
  const d = value instanceof Date ? value : new Date(value);
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: timeZone(), year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(d);
  const get = (t: string) => parts.find(p => p.type === t)?.value ?? '';
  return `${get('year')}-${get('month')}-${get('day')}`;
}

/** Today in the merchant's time zone (YYYY-MM-DD). */
export const today = (now: Date = new Date()) => localDate(now);

/** Adds days to a YYYY-MM-DD date. */
export function addDays(date: string, days: number): string {
  const d = new Date(`${date}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** ISO weekday of a YYYY-MM-DD date: 1 = Monday … 7 = Sunday. */
export const isoWeekday = (date: string) => { const w = new Date(`${date}T12:00:00Z`).getUTCDay(); return w === 0 ? 7 : w; };

/** Monday of the week containing `date`. */
export const mondayOf = (date: string) => addDays(date, 1 - isoWeekday(date));

/** Offset (minutes) of the merchant's time zone at a given UTC instant. */
function offsetMinutes(at: Date): number {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: timeZone(), hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).formatToParts(at);
  const g = (t: string) => Number(parts.find(p => p.type === t)?.value);
  const asUtc = Date.UTC(g('year'), g('month') - 1, g('day'), g('hour'), g('minute'));
  return Math.round((asUtc - at.getTime()) / 60000);
}

/** The instant of local (merchant time zone) date + HH:mm, as ISO-8601. */
export function localInstant(date: string, time = '00:00'): string {
  const guess = new Date(`${date}T${time}:00Z`);
  const off = offsetMinutes(guess);
  const at = new Date(guess.getTime() - off * 60000);
  const off2 = offsetMinutes(at);
  return new Date(guess.getTime() - off2 * 60000).toISOString();
}

/** "9:00" / "2:00" — the design's compact clock (12 h, no am/pm) — or "9 h 00" in French. */
export function clock(value: string | Date, locale: Locale): string {
  const d = value instanceof Date ? value : new Date(value);
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: timeZone(), hour: 'numeric', minute: '2-digit', hourCycle: locale === 'fr' ? 'h23' : 'h12' }).formatToParts(d);
  const h = parts.find(p => p.type === 'hour')?.value ?? '';
  const m = parts.find(p => p.type === 'minute')?.value ?? '00';
  return locale === 'fr' ? `${h} h ${m}` : `${h}:${m}`;
}

/** "5:45 pm" / "17 h 45". */
export function clockWithPeriod(value: string | Date, locale: Locale): string {
  const d = value instanceof Date ? value : new Date(value);
  if (locale === 'fr') return clock(d, 'fr');
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: timeZone(), hour: 'numeric', minute: '2-digit', hourCycle: 'h12' }).formatToParts(d);
  const h = parts.find(p => p.type === 'hour')?.value ?? '';
  const m = parts.find(p => p.type === 'minute')?.value ?? '00';
  const p = (parts.find(x => x.type === 'dayPeriod')?.value ?? '').toLowerCase();
  return m === '00' ? `${h} ${p}` : `${h}:${m} ${p}`;
}

/** "07:00" → "7:00 am" (en) / "7 h 00" (fr). */
export function hhmmLabel(hhmm: string, locale: Locale): string {
  const [hs, ms] = hhmm.split(':');
  const h = Number(hs), m = ms ?? '00';
  if (locale === 'fr') return `${h} h ${m}`;
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${h12}:${m} ${h < 12 ? 'am' : 'pm'}`;
}

export const toMinutes = (hhmm: string) => { const [h, m] = hhmm.split(':').map(Number); return (h ?? 0) * 60 + (m ?? 0); };
export const fromMinutes = (min: number) => `${String(Math.floor(min / 60)).padStart(2, '0')}:${String(min % 60).padStart(2, '0')}`;
