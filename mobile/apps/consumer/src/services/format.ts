import type { Locale } from '@northline/mobile-kit';

/**
 * Money and times of Journey C. Money is CAD from `*_cents`; times are instants shown in the business's time zone
 * (`timeZone` on every booking answer — never the phone's, never a zone named in code).
 */
const tag = (locale: Locale) => (locale === 'fr-CA' ? 'fr-CA' : 'en-CA');

export function money(cents: number | null | undefined, locale: Locale): string {
  return new Intl.NumberFormat(tag(locale), { style: 'currency', currency: 'CAD' }).format((cents ?? 0) / 100);
}

/** Whole dollars when there are no cents ("$89"), as the design shows menu prices. */
export function price(cents: number | null | undefined, locale: Locale): string {
  const c = cents ?? 0;
  return new Intl.NumberFormat(tag(locale), { style: 'currency', currency: 'CAD', minimumFractionDigits: c % 100 === 0 ? 0 : 2 }).format(c / 100);
}

/** The calendar date (`YYYY-MM-DD`) of an instant in a zone. */
export function dateIn(iso: string | number | Date, timeZone: string): string {
  const d = new Date(iso);
  try {
    return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(d);
  } catch {
    return d.toISOString().slice(0, 10);
  }
}

/** A calendar day's button: "WED" and "9" (the date is already the business's). */
export function dayParts(date: string, locale: Locale): { dow: string; num: string } {
  const d = new Date(`${date}T12:00:00Z`);
  return {
    dow: new Intl.DateTimeFormat(tag(locale), { weekday: 'short', timeZone: 'UTC' }).format(d).replace('.', ''),
    num: String(d.getUTCDate()),
  };
}

/** "22:00" (quiet hours, a wall-clock time) in the reader's style: "10:00 p.m." / "22 h 00". */
export function clock(hhmm: string, locale: Locale): string {
  const [h = '0', m = '0'] = hhmm.split(':');
  const d = new Date(Date.UTC(2000, 0, 1, Number(h), Number(m)));
  return new Intl.DateTimeFormat(tag(locale), { hour: 'numeric', minute: '2-digit', timeZone: 'UTC' }).format(d);
}

/** Whole hours from now until `iso` (at least 0). */
export function hoursUntil(iso: string, now = Date.now()): number {
  return Math.max(0, Math.ceil((Date.parse(iso) - now) / 3_600_000));
}
