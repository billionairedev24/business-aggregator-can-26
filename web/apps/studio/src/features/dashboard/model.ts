import type { Icon } from '@phosphor-icons/react';
import { Bank, ChatCircleText, CookingPot, FileText, Package, ShieldWarning, Stack, Storefront, Tag, Warning } from '@phosphor-icons/react';
import { formatWhen, timeZone, type KpiItem, type Locale, type StatusTone, type Trend, useFormatters } from '@northline/ui';
import type { MerchantSummary } from '../shell/api';
import type { ScreenKey } from '../shell/nav';
import type { Dashboard } from './api';
import type { Report, PayoutOverview } from '../finance/api';
import type { ThreadSummary } from '../messages/api';
import type { OrderBoard } from '../orders/api';
import type { ListingItem } from '../catalogue/api';
import type { LiveBoard, MenuItem, Setup as KitchenSetup } from '../kitchen/api';
import type { DashboardT } from './messages';
import { localDate } from '../../lib/time';

/*
 * The Studio dashboard's model (E-15): everything the screen shows, derived from what the Studio's api already answers
 * — the dashboard read model plus the sales report, payouts, messages, orders, listings and the kitchen board. A part
 * whose source is missing (not loaded, or not allowed for the viewer's role) is left out, never guessed.
 */

export type DashboardKind = 'provider' | 'seller' | 'both' | 'kitchen';
type Fmt = ReturnType<typeof useFormatters>;

/** What a newly approved business still has to set up (§ 3.10: page and first listings come after approval). */
export interface Setup { page: 'none' | 'draft' | 'live' | 'unknown'; listings: number | null }

export interface DashboardInputs {
  kind: DashboardKind;
  data: Dashboard;
  now: Date;
  firstName?: string | null;
  status?: MerchantSummary['status'];
  setup?: Setup;
  report?: Report;
  payout?: PayoutOverview;
  threads?: readonly ThreadSummary[];
  orders?: OrderBoard;
  listings?: readonly ListingItem[];
  live?: LiveBoard;
  kitchenSetup?: KitchenSetup;
  dishes?: readonly MenuItem[];
}

const sells = (k: DashboardKind) => k === 'seller' || k === 'both';
const books = (k: DashboardKind) => k === 'provider' || k === 'both';

// ── the greeting line ───────────────────────────────────────────────────────────────────────────────────────────

const hourIn = (now: Date) => Number(new Intl.DateTimeFormat('en-US', { timeZone: timeZone(), hour: 'numeric', hourCycle: 'h23' }).format(now));

/** "Good afternoon, Ravi" by the hour where the business is. */
export function greeting(i: DashboardInputs, t: DashboardT): string {
  const h = hourIn(i.now);
  const name = i.firstName?.trim() || t('hello');
  if (!i.firstName?.trim()) return name;
  return t(h < 12 ? 'morning' : h < 18 ? 'afternoon' : 'evening', { name });
}

/** "Friday, October 23 · Run R-611 closes at 5:20 p.m." — the date, then the day's next pickup or booking. */
export function dayLine(i: DashboardInputs, t: DashboardT, locale: Locale): string {
  const date = new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { timeZone: timeZone(), weekday: 'long', month: 'long', day: 'numeric' }).format(i.now);
  const parts = [date.charAt(0).toUpperCase() + date.slice(1)];
  const c = i.data.counts;
  if (i.kind === 'kitchen' && i.live) parts.push(t('openTickets', { count: i.live.counts.open }));
  if (sells(i.kind) && c.runCutoff && c.runLabel && new Date(c.runCutoff) > i.now) parts.push(t('nextRun', { run: c.runLabel, time: formatWhen(c.runCutoff, locale, 'time') }));
  if (books(i.kind)) {
    const next = [...i.data.jobsToday].sort((a, b) => a.startsAt.localeCompare(b.startsAt)).find(j => new Date(j.startsAt) > i.now && j.state !== 'cancelled');
    if (next) parts.push(t('nextBooking', { time: formatWhen(next.startsAt, locale, 'time'), what: next.title }));
    else if (i.data.jobsToday.length) parts.push(t('noMoreBookings'));
  }
  return parts.join(' · ');
}

/** The store's state: kitchens pause and resume themselves; other businesses show what the platform has them as. */
export interface StoreState { tone: StatusTone; text: string; canPause: boolean; paused: boolean }
export function storeState(i: DashboardInputs, t: DashboardT, locale: Locale): StoreState {
  if (i.kind === 'kitchen') {
    const until = i.live?.pausedUntil ?? i.kitchenSetup?.pausedUntil;
    if (until && new Date(until) > i.now) return { tone: 'warning', text: t('pausedUntil', { time: formatWhen(until, locale, 'time') }), canPause: !!i.live, paused: true };
    const hours = i.kitchenSetup?.hours;
    if (hours) {
      const end = openUntil(hours, i.now, locale);
      if (end === null) return { tone: 'neutral', text: t('closedNow'), canPause: false, paused: false };
      return { tone: 'success', text: t('openUntil', { time: end }), canPause: !!i.live, paused: false };
    }
    return { tone: 'success', text: t('open_kitchen'), canPause: !!i.live, paused: false };
  }
  if (i.status === 'paused' || i.status === 'suspended') return { tone: 'warning', text: t('paused'), canPause: false, paused: true };
  return { tone: 'success', text: t(`open_${i.kind}`), canPause: false, paused: false };
}

/** The end of today's opening range that holds `now` ("6 p.m." from "18:00"), or null when closed now. */
function openUntil(hours: KitchenSetup['hours'], now: Date, locale: Locale): string | null {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: timeZone(), weekday: 'short', hour: 'numeric', minute: '2-digit', hourCycle: 'h23' }).formatToParts(now);
  const wd = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].indexOf(parts.find(p => p.type === 'weekday')?.value ?? '') + 1;
  const mins = Number(parts.find(p => p.type === 'hour')?.value) * 60 + Number(parts.find(p => p.type === 'minute')?.value);
  const toMin = (s: string) => { const [h, m] = s.split(':').map(Number); return (h ?? 0) * 60 + (m ?? 0); };
  const range = hours.find(h => h.weekday === wd)?.ranges.find(r => r.length >= 2 && toMin(r[0]!) <= mins && mins < toMin(r[1]!));
  if (!range) return null;
  const [h, m] = range[1]!.split(':').map(Number);
  return formatWhen(new Date(Date.UTC(2000, 0, 1, h ?? 0, m ?? 0)), locale, 'time', 'UTC');
}

// ── needs you now ───────────────────────────────────────────────────────────────────────────────────────────────

export interface ActionItem { key: string; tone: StatusTone; icon: Icon; title: string; detail?: string; label: string; to: ScreenKey; search?: Record<string, string> }
const RANK: Record<StatusTone, number> = { danger: 0, warning: 1, info: 2, success: 3, neutral: 4 };

/** "38 min", "3 h", "2 days" since an instant. */
export function age(at: string, now: Date, t: DashboardT): string {
  const min = Math.max(1, Math.round((now.getTime() - new Date(at).getTime()) / 60000));
  if (min < 60) return t('ageMin', { n: min });
  if (min < 48 * 60) return t('ageHour', { n: Math.round(min / 60) });
  return t('ageDay', { n: Math.round(min / 1440) });
}

/** The cards of "Needs you now", the most urgent first: each a job, its deadline and the one screen that does it. */
export function actions(i: DashboardInputs, t: DashboardT, f: Fmt, locale: Locale): ActionItem[] {
  const out: ActionItem[] = [];
  const d = i.data, c = d.counts;
  if (i.kind === 'kitchen' && i.live) {
    if (i.live.counts.fresh > 0) out.push({ key: 'fresh', tone: 'danger', icon: CookingPot, title: t('newTickets', { count: i.live.counts.fresh }), detail: t('newTicketsDetail'), label: t('openKds'), to: 'kds' });
    if (i.live.counts.ready > 0) out.push({ key: 'ready', tone: 'info', icon: Package, title: t('readyTickets', { count: i.live.counts.ready }), detail: t('readyDetail'), label: t('openKds'), to: 'kds' });
  }
  if (sells(i.kind) && c.toPack > 0) {
    const cutoff = c.runCutoff && new Date(c.runCutoff) > i.now ? c.runCutoff : null;
    const soon = cutoff ? new Date(cutoff).getTime() - i.now.getTime() < 3_600_000 : false;
    out.push({ key: 'pack', tone: soon ? 'danger' : 'warning', icon: Package, title: t('pack', { count: c.toPack }), detail: cutoff && c.runLabel ? t('packBy', { time: formatWhen(cutoff, locale, 'time'), run: c.runLabel }) : t('packSoon'), label: t('startPacking'), to: 'orders', search: { view: 'to_pack' } });
  }
  if (books(i.kind) && c.quoteRequestsOpen > 0) {
    const by = c.quoteRespondBy && new Date(c.quoteRespondBy) > i.now ? c.quoteRespondBy : null;
    out.push({ key: 'quotes', tone: 'warning', icon: ChatCircleText, title: t('quotes', { count: c.quoteRequestsOpen }), detail: by ? t('replyBy', { time: formatWhen(by, locale, localDate(by) === localDate(i.now) ? 'time' : 'dateTime') }) : t('replyNow'), label: t('sendQuotes'), to: 'appointments' });
  }
  const unread = (i.threads ?? []).filter(th => th.unread && th.kind !== 'support');
  if (unread.length) {
    const oldest = [...unread].sort((a, b) => (a.lastMessageAt ?? '').localeCompare(b.lastMessageAt ?? ''))[0]!;
    const when = oldest.lastMessageAt ? age(oldest.lastMessageAt, i.now, t) : null;
    const text = oldest.lastMessage ? `“${oldest.lastMessage.length > 60 ? `${oldest.lastMessage.slice(0, 57)}…` : oldest.lastMessage}”` : null;
    out.push({ key: 'messages', tone: 'info', icon: ChatCircleText, title: t('messages', { count: unread.length }), detail: when ? (text ? t('oldest', { age: when, name: oldest.counterpartName, text }) : t('oldestNoText', { age: when, name: oldest.counterpartName })) : oldest.counterpartName, label: t('reply'), to: 'messages', search: { thread: oldest.id } });
  }
  const low = sells(i.kind) ? d.lowStock[0] : undefined;
  if (low) {
    const more = d.lowStock.length - 1;
    out.push({ key: 'stock', tone: low.stock <= 0 ? 'danger' : 'warning', icon: Stack, title: t('lowStock', { name: low.name }), detail: [t('stockLeft', { count: low.stock }), more > 0 ? t('moreLow', { count: more }) : null].filter(Boolean).join(' · '), label: t('updateStock'), to: 'products', search: more > 0 ? { view: 'stock' } : { view: 'stock', q: low.name } });
  }
  const soldOut = (i.dishes ?? []).filter(x => x.soldOut);
  if (i.kind === 'kitchen' && soldOut.length) out.push({ key: 'soldout', tone: 'warning', icon: Stack, title: t('soldOut', { count: soldOut.length }), detail: soldOut.slice(0, 3).map(x => x.name).join(', '), label: t('openMenu'), to: 'menu' });
  if (d.cases.length) {
    const first = d.cases[0]!;
    out.push({ key: 'issues', tone: 'danger', icon: Warning, title: t('issues', { count: d.cases.length }), detail: t('issueDetail', { who: first.customerName ?? '—', what: first.subject ?? '' }), label: t('resolve'), to: 'refunds' });
  }
  if (i.payout?.returned) out.push({ key: 'returned', tone: 'danger', icon: Bank, title: t('payoutReturned'), detail: t('payoutReturnedDetail', { amount: f.price(i.payout.returned.amountCents) }), label: t('fixBank'), to: 'payouts' });
  else if (i.payout?.nextPayoutAt) {
    const p = i.payout, amount = f.price(p.payableCents), at = new Date(i.payout.nextPayoutAt);
    out.push({ key: 'payout', tone: 'success', icon: Bank, title: t('payout', { date: new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { timeZone: timeZone(), weekday: 'long', month: 'short', day: 'numeric' }).format(at) }), detail: p.account ? t('payoutTo', { amount, bank: p.account.institutionName, last4: p.account.last4 }) : t('payoutAmount', { amount }), label: t('viewPayout'), to: 'payouts' });
  }
  const doc = d.compliance[0];
  if (doc) {
    const name = t(`doc_${doc.checkType}` as Parameters<DashboardT>[0]);
    const expired = doc.status === 'expired' || (doc.expiresAt ? new Date(doc.expiresAt) <= i.now : false);
    const days = doc.pausesAt ? Math.ceil((new Date(doc.pausesAt).getTime() - i.now.getTime()) / 86_400_000) : 0;
    const within30 = doc.expiresAt ? new Date(doc.expiresAt).getTime() - i.now.getTime() < 30 * 86_400_000 : false;
    const detail = !doc.expiresAt ? t('docMissing')
      : expired ? (days > 0 ? t('docExpired', { date: f.when(doc.expiresAt, 'full'), days }) : t('docPaused', { date: f.when(doc.expiresAt, 'full') }))
      : t('docExpires', { date: f.when(doc.expiresAt, 'full') });
    out.push({ key: 'doc', tone: expired ? 'danger' : within30 || doc.status === 'expiring' ? 'warning' : 'neutral', icon: expired ? ShieldWarning : FileText, title: name, detail, label: t('upload'), to: 'compliance' });
  }
  if (i.setup && (i.setup.page === 'none' || i.setup.page === 'draft')) out.push({ key: 'page', tone: 'info', icon: Storefront, title: t(i.setup.page === 'none' ? `setupPage_${i.kind}` : `publishPage_${i.kind}`), detail: t('setupPageDetail'), label: t('openPage'), to: 'storefront' });
  if (i.setup?.listings === 0 && i.kind !== 'kitchen') out.push({ key: 'listings', tone: 'info', icon: Tag, title: t(`setupListings_${i.kind}`), detail: t('setupListingsDetail'), label: t('addListing'), to: 'products' });
  return out.map((a, n) => ({ a, n })).sort((x, y) => RANK[x.a.tone] - RANK[y.a.tone] || x.n - y.n).map(x => x.a);
}

// ── key figures ─────────────────────────────────────────────────────────────────────────────────────────────────

const pctText = (pct: number) => `${pct >= 0 ? '+' : '−'}${Math.abs(pct)}%`;
const trendOf = (pct: number): Trend => (pct > 0 ? 'up' : pct < 0 ? 'down' : 'flat');

/** Today's sales from the 30-day report (its last day), against the same weekday a week earlier. */
export function salesToday(report: Report | undefined): { today: number; weekAgo: number | null; date: string } | null {
  if (!report || report.granularity !== 'day' || !report.series.length) return null;
  const s = report.series;
  return { today: s[s.length - 1]!.currentCents, weekAgo: s.length >= 8 ? s[s.length - 8]!.currentCents : null, date: s[s.length - 1]!.start };
}

export function kpis(i: DashboardInputs, t: DashboardT, f: Fmt, locale: Locale, go: (k: ScreenKey) => () => void): KpiItem[] {
  const d = i.data, c = d.counts, r = d.reputation;
  const items: KpiItem[] = [];
  const st = salesToday(i.report);
  if (st) {
    const pct = st.weekAgo && st.weekAgo > 0 ? Math.round(((st.today - st.weekAgo) / st.weekAgo) * 100) : null;
    const day = new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { weekday: 'long', timeZone: 'UTC' }).format(new Date(`${st.date}T12:00:00Z`));
    items.push({ key: 'sales', label: t('salesToday'), value: f.price(st.today), ...(pct === null ? {} : { delta: t('vsLast', { pct: pctText(pct), day }), trend: trendOf(pct), arrow: true }), onClick: go('reports') });
  } else {
    // no sales report for this role: the month's net from the dashboard itself
    const e = d.earnings;
    const pct = e.netLastMonthCents > 0 ? Math.round(((e.netThisMonthCents - e.netLastMonthCents) / e.netLastMonthCents) * 100) : null;
    const month = new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', timeZone: 'UTC' }).format(new Date(Date.UTC(2000, Number(d.today.slice(5, 7)) - 2, 15))).replace('.', '');
    items.push({ key: 'net', label: t('netMonth'), value: f.money(e.netThisMonthCents, { whole: true }), ...(pct === null ? {} : { delta: t('vsMonth', { pct: pctText(pct), month }), trend: trendOf(pct), arrow: true }), onClick: go('earnings') });
  }
  if (i.kind === 'kitchen' && i.live) items.push({ key: 'tickets', label: t('ticketsOpen'), value: f.number(i.live.counts.open), delta: t('ticketsDelta', { fresh: i.live.counts.fresh, cooking: i.live.counts.cooking }), onClick: go('kds') });
  if (sells(i.kind)) {
    const today = localDate(i.now);
    const placed = i.orders?.items.filter(o => o.placedAt && localDate(o.placedAt) === today).length;
    items.push(placed !== undefined
      ? { key: 'orders', label: t('ordersToday'), value: f.number(placed), delta: t('toPackDelta', { count: f.number(c.toPack) }), onClick: go('orders') }
      : { key: 'orders', label: t('ordersMonth'), value: f.number(c.ordersThisMonth), delta: t('itemsDelta', { items: c.itemsThisMonth }), onClick: go('orders') });
  }
  if (books(i.kind)) items.push({ key: 'bookings', label: t('bookingsToday'), value: f.number(d.jobsToday.filter(j => j.state !== 'cancelled').length), delta: t('quotesDelta', { count: c.quoteRequestsOpen }), onClick: go('appointments') });
  if (i.report && i.report.count > 0) {
    const g = i.report.grossChangePct;
    items.push({ key: 'average', label: t(i.kind === 'provider' ? 'averageBooking' : 'averageOrder'), value: f.price(i.report.averageTicketCents), ...(g == null ? {} : { delta: t('vsPrevious', { pct: pctText(g) }), trend: trendOf(g), arrow: true }), onClick: go('reports') });
  }
  items.push({ key: 'rating', label: t('rating'), value: r.rating != null ? f.number(r.rating, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) : '—', delta: t('ratings', { count: r.reviews }), onClick: go('reviews') });
  return items;
}

// ── sales over time ─────────────────────────────────────────────────────────────────────────────────────────────

export type Range = '14d' | '30d' | '12mo';
export interface SalesSeries { title: string; points: { label: string; name: string; value: number }[]; legend: { current: string; past: string } }

export function salesSeries(range: Range, report: Report | undefined, t: DashboardT, f: Fmt, locale: Locale): SalesSeries | null {
  if (!report || !report.series.length) return null;
  const intl = locale === 'fr' ? 'fr-CA' : 'en-CA';
  const day = (iso: string, o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(intl, { timeZone: 'UTC', ...o }).format(new Date(`${iso.slice(0, 10)}T12:00:00Z`)).replace('.', '');
  const monthly = report.granularity === 'month';
  const s = range === '14d' ? report.series.slice(-14) : report.series;
  const points = s.map(p => ({
    label: monthly ? day(p.start, { month: 'narrow' }) : range === '14d' ? day(p.start, { weekday: 'narrow' }) : day(p.start, { day: 'numeric' }),
    name: monthly ? day(p.start, { month: 'long', year: 'numeric' }) : day(p.start, { weekday: 'long', month: 'short', day: 'numeric' }),
    value: p.currentCents,
  }));
  const past = points.slice(0, -1);
  const avg = past.length ? Math.round(past.reduce((n, p) => n + p.value, 0) / past.length) : 0;
  const last = points[points.length - 1]?.value ?? 0;
  return {
    title: t(`sales_${range}`), points,
    legend: monthly
      ? { current: t('monthSoFar', { amount: f.price(last) }), past: t('previousMonths', { count: past.length, amount: f.money(avg, { whole: true }) }) }
      : { current: t('todaySoFar', { amount: f.price(last) }), past: t('previousDays', { count: past.length, amount: f.money(avg, { whole: true }) }) },
  };
}

/** Without a sales report (a role that can't read it): the dashboard's own 12 weeks of net earnings. */
export function weeklySeries(i: DashboardInputs, t: DashboardT, f: Fmt, locale: Locale): SalesSeries {
  const prov = books(i.kind), sell = sells(i.kind) || i.kind === 'kitchen';
  const points = i.data.earnings.weeks.map((w, n) => ({
    label: /^\d{4}-\d{2}-\d{2}$/.test(w.start) ? new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${w.start}T12:00:00Z`)).replace('.', '') : `W${n + 1}`,
    name: w.start, value: (prov ? w.servicesCents : 0) + (sell ? w.partsCents : 0),
  }));
  const past = points.slice(0, -1);
  const avg = past.length ? Math.round(past.reduce((n, p) => n + p.value, 0) / past.length) : 0;
  return { title: t('weeklyNet'), points, legend: { current: t('thisWeek', { amount: f.money(points[points.length - 1]?.value ?? 0, { whole: true }) }), past: t('previousWeeks', { count: past.length, amount: f.money(avg, { whole: true }) }) } };
}

// ── top products, services and dishes ───────────────────────────────────────────────────────────────────────────

export interface TopRow { id: string; name: string; imageUrl?: string | null; sold: number; salesCents: number | null; stock: number | null; priceCents?: number | null; soldOut?: boolean }

/** The 30 days' best sellers of the catalogue (units from the listing, money from the report's per-listing totals). */
export function topListings(i: DashboardInputs, kind: 'product' | 'service'): TopRow[] {
  const gross = new Map((i.report?.byListing ?? []).filter(b => b.name).map(b => [b.name!, b.grossCents]));
  return (i.listings ?? [])
    .filter(l => (kind === 'product' ? l.kind !== 'service' : l.kind === 'service') && l.sales30d > 0)
    .sort((a, b) => b.sales30d - a.sales30d)
    .slice(0, 5)
    .map(l => ({ id: l.id, name: l.name, imageUrl: l.imageUrl, sold: l.sales30d, salesCents: gross.get(l.name) ?? null, stock: l.stock ?? null }));
}

/** Today's best-selling dishes of the kitchen's live menu. */
export function topDishes(i: DashboardInputs): TopRow[] {
  return (i.dishes ?? []).filter(x => x.soldToday > 0).sort((a, b) => b.soldToday - a.soldToday).slice(0, 5)
    .map(x => ({ id: x.id, name: x.name, sold: x.soldToday, salesCents: x.soldToday * x.priceCents, stock: null, priceCents: x.priceCents, soldOut: x.soldOut }));
}

/** Stock as a status: out (danger), low (warning: the dashboard's low-stock list), else neutral. */
export function stockTone(row: TopRow, lowNames: ReadonlySet<string>): StatusTone {
  if (row.stock === null) return 'neutral';
  if (row.stock <= 0) return 'danger';
  return lowNames.has(row.name) ? 'warning' : 'neutral';
}
