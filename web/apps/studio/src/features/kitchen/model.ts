import { formatMoney, TIME_ZONE, type Locale } from '@northline/ui';
import { clock, clockWithPeriod, hhmmLabel, toMinutes } from '../../lib/time';
import type { MenuSchedule, ModifierGroup, Ticket } from './api';
import type { KitchenT } from './messages';

/** "$17", "17.50", "17,50" → cents; anything else → undefined. */
export function parseDollars(text: string): number | undefined {
  const t = text.trim().replace(/[$\s]/g, '').replace(',', '.');
  if (!/^\d{1,5}(\.\d{1,2})?$/.test(t)) return undefined;
  return Math.round(Number(t) * 100);
}
export const dollars = (cents: number) => (cents / 100).toFixed(2);

const hourIn = (d: Date) => Number(new Intl.DateTimeFormat('en-US', { timeZone: TIME_ZONE, hour: 'numeric', hourCycle: 'h23' }).format(d));
/** ISO weekday (1 = Mon) in Edmonton. */
export const weekdayIn = (d: Date) => { const w = new Intl.DateTimeFormat('en-US', { timeZone: TIME_ZONE, weekday: 'short' }).format(d); return ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].indexOf(w) + 1; };
/** The service the kitchen is in ("Tuesday dinner"): before 11 → morning, 11–16 → lunch, then dinner. */
export const mealOf = (d: Date): 'morning' | 'lunch' | 'dinner' => { const h = hourIn(d); return h < 11 ? 'morning' : h < 16 ? 'lunch' : 'dinner'; };

export const minutesUntil = (iso: string, now: Date) => Math.round((new Date(iso).getTime() - now.getTime()) / 60000);

/** Who gets the bag and where they are ("Deliver · Priya arriving 7:12", "Pickup · customer 5 min away"). */
export function whereText(o: Ticket, t: KitchenT, locale: Locale, now = new Date()): string {
  const h = o.handoff;
  if (o.fulfilmentMode === 'pickup') {
    if (!h.eta) return t('w_pickup');
    const n = minutesUntil(h.eta, now);
    return n > 0 ? t('w_pickup_eta', { n }) : t('w_pickup_due');
  }
  switch (h.state) {
    case 'finding': return t('w_finding');
    case 'waiting': return h.name ? t('w_waiting', { name: h.name }) : t('w_waiting_anon');
    case 'arriving': {
      const time = h.eta ? clock(h.eta, locale) : '';
      return h.name ? t('w_arriving', { name: h.name, time }) : t('w_arriving_anon', { time });
    }
    default: return t('w_assigned');
  }
}

/** "A. Osei · placed 7:04" / "Group · Kofi +2 · placed 7:06". */
export function whoText(o: Ticket, t: KitchenT, locale: Locale): string {
  const who = o.groupSize > 1 ? t('group', { name: o.customerName ?? t('customer'), n: o.groupSize - 1 }) : o.customerName ?? t('customer');
  return t('placed', { who, time: clock(o.placedAt, locale) });
}

export function prepText(o: Ticket, t: KitchenT, now = new Date()): string {
  if (o.stage !== 'cooking' || !o.readyBy) return '';
  const n = minutesUntil(o.readyBy, now);
  return n >= 0 ? t('minLeft', { n }) : t('minLate', { n: -n });
}

export const lineText = (l: Ticket['lines'][number], t: KitchenT) => t('line', { qty: l.qty, title: l.modifiers.length ? `${l.title} · ${l.modifiers.join(', ')}` : l.title });

export function ruleText(g: Pick<ModifierGroup, 'pickRule' | 'pickCount' | 'required'>, t: KitchenT): string {
  const rule = t(`rule_${g.pickRule}`, { n: g.pickCount });
  return g.required ? t('ruleRequired', { rule }) : rule;
}

export const optionText = (o: { name: string; priceDeltaCents: number }, t: KitchenT, locale: Locale) =>
  t('optionPrice', { name: o.name, price: formatMoney(o.priceDeltaCents, locale, { whole: o.priceDeltaCents % 100 === 0 }) });

/** "11:00 am – 9:00 pm" / "11 h 00 – 21 h 00". */
export const rangeText = (r: string[], locale: Locale) => `${hhmmLabel(r[0] ?? '', locale)} – ${hhmmLabel(r[1] ?? '', locale)}`;

/** "Tue–Fri" for consecutive days, else "Tue, Thu". */
export function daysText(days: number[], t: KitchenT): string {
  const d = [...days].sort((a, b) => a - b);
  if (!d.length) return '';
  const consecutive = d.every((x, i) => i === 0 || x === d[i - 1]! + 1);
  const name = (n: number) => t(`day_${n}` as 'day_1');
  return consecutive && d.length > 2 ? `${name(d[0]!)}–${name(d[d.length - 1]!)}` : d.map(name).join(', ');
}

const shortClock = (hhmm: string, locale: Locale) => {
  if (locale === 'fr') return hhmmLabel(hhmm, 'fr');
  const [h, m] = hhmm.split(':').map(Number);
  return `${(h ?? 0) % 12 === 0 ? 12 : (h ?? 0) % 12}:${String(m ?? 0).padStart(2, '0')}`;
};

export function scheduleText(s: MenuSchedule, t: KitchenT, locale: Locale): string {
  if (s.mode === 'window') return t('sched_window', { days: daysText(s.days, t), from: shortClock(s.from ?? '', locale), to: shortClock(s.to ?? '', locale) });
  if (s.mode === 'quote') return t('sched_quote', { n: s.noticeHours ?? 48 });
  return t('sched_open');
}

/** Menu picker label: "Dinner menu (live)", "Lunch menu · 11–2". */
export function menuOptionText(m: { name: string; status: string; schedule: MenuSchedule }, t: KitchenT): string {
  if (m.schedule.mode === 'window' && m.schedule.from && m.schedule.to) {
    const h = (x: string) => { const [hh, mm] = x.split(':').map(Number); const h12 = (hh ?? 0) % 12 === 0 ? 12 : (hh ?? 0) % 12; return mm ? `${h12}:${String(mm).padStart(2, '0')}` : String(h12); };
    return t('menuOptWindow', { name: m.name, from: h(m.schedule.from), to: h(m.schedule.to) });
  }
  return m.status === 'live' ? t('menuOptLive', { name: m.name }) : m.name;
}

export const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/;

/** Opening-range checks mirroring the server (OpeningRanges): format, end after start, no overlap. */
export function rangeErrors(ranges: string[][], t: KitchenT): Record<number, string> {
  const out: Record<number, string> = {};
  const ok: [number, number][] = [];
  ranges.forEach((r, i) => {
    const [from = '', to = ''] = r;
    if (!HHMM.test(from) || !HHMM.test(to)) { out[i] = t('v_timeFormat'); return; }
    const a = toMinutes(from), b = toMinutes(to);
    if (b <= a) { out[i] = t('v_endAfter'); return; }
    if (ok.some(([x, y]) => x < b && a < y)) { out[i] = t('v_overlap'); return; }
    ok.push([a, b]);
  });
  return out;
}

export const timeOf = (iso: string, locale: Locale) => clockWithPeriod(iso, locale);
