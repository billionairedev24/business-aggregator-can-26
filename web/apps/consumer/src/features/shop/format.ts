import { type Locale, timeZone } from '@northline/ui';
import type { Run } from './api';

const INTL: Record<Locale, string> = { en: 'en-CA', fr: 'fr-CA' };

/** "5:20 p.m." / "17 h 20" in the market's time zone. */
export const clock = (iso: string, locale: Locale, zone: string = timeZone()) =>
  new Intl.DateTimeFormat(INTL[locale], { timeZone: zone, hour: 'numeric', minute: '2-digit' }).format(new Date(iso));

/** "6–9 p.m." / "18 h – 21 h" (design 06: "Tonight 6–9 pm"); minutes only when a time isn't on the hour. */
export function windowRange(startIso: string, endIso: string, locale: Locale, zone: string = timeZone()) {
  const start = new Date(startIso), end = new Date(endIso);
  const onHour = start.getUTCMinutes() === 0 && end.getUTCMinutes() === 0;
  const f = new Intl.DateTimeFormat(INTL[locale], { timeZone: zone, hour: 'numeric', ...(onHour ? {} : { minute: '2-digit' }) });
  return f.formatRange(start, end);
}

/** "Tuesday" / "mardi". */
export const weekday = (iso: string, locale: Locale, zone: string = timeZone()) =>
  new Intl.DateTimeFormat(INTL[locale], { timeZone: zone, weekday: 'long' }).format(new Date(iso));

/** The hour in the market's time zone, 0–23. */
const localHour = (iso: string, zone: string) =>
  Number(new Intl.DateTimeFormat('en-CA', { timeZone: zone, hour: 'numeric', hourCycle: 'h23' }).format(new Date(iso)));

/**
 * Which words a run gets: `tonight` (today, from 3 pm), `today`, `tomorrow`, or `later` (another day — the copy then
 * names the weekday). The api says which day it is in the market, so server and browser render the same words.
 */
export type RunWhen = 'tonight' | 'today' | 'tomorrow' | 'later';
export function runWhen(run: Pick<Run, 'day' | 'startsAt'>, zone: string = timeZone()): RunWhen {
  if (run.day === 'today') return localHour(run.startsAt, zone) >= 15 ? 'tonight' : 'today';
  return run.day;
}
