import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CaretLeft, CaretRight } from '@phosphor-icons/react';
import { DataTable, Drawer, ErrorState, Segmented, Skeleton, useLocale, type DataTableColumn, type Locale } from '@northline/ui';
import { useMerchantId } from '../shell/api';
import { useSession } from '../../lib/session';
import { timeOffQuery } from '../availability/api';
import { addDays, clock, localDate, localInstant, minuteOfDay, mondayOf, today } from '../../lib/time';
import { BoHeader } from '../../components/BackOffice';
import { calendarCellsQuery, jobsQuery, type CalendarCells, type Job } from './api';
import { JobPanel } from './JobPanel';
import { useAppointmentsT } from './messages';
import { QuoteRequests } from './QuoteRequests';
import './Appointments.css';

type View = 'day' | 'week' | 'list';
type T = ReturnType<typeof useAppointmentsT>;
const DONE = new Set(['completed', 'signed_off', 'cancelled']);
const NO_CELLS: CalendarCells = { openSlots: [], quoteHolds: [] };

const fmt = (date: string, locale: Locale, o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { ...o, timeZone: 'UTC' }).format(new Date(`${date}T12:00:00Z`)).replace('.', '');

export function AppointmentsScreen() {
  const t = useAppointmentsT();
  const { locale } = useLocale();
  const merchantId = useMerchantId();
  const [view, setView] = useState<View>('week');
  const [anchor, setAnchor] = useState(today());
  const [selected, setSelected] = useState<string | null>(null);
  const monday = mondayOf(anchor);
  const range = view === 'day' ? [anchor, addDays(anchor, 1)] : [monday, addDays(monday, 7)];
  const q = useQuery(jobsQuery(merchantId, localInstant(range[0]!), localInstant(range[1]!)));
  const timeOff = useQuery(timeOffQuery(merchantId));
  // S-74: open slots and quote holds — extras; the calendar shows without them when they fail
  const cellsQ = useQuery(calendarCellsQuery(merchantId, range[0]!, view === 'day' ? 1 : 7));
  const cells: CalendarCells = cellsQ.data ?? NO_CELLS;
  const jobs = useMemo(() => q.data ?? [], [q.data]);
  // S-117: after Previous/Next the query keeps the last week's jobs on screen until this week's arrive. Say so (busy,
  // dimmed, the list's rows not clickable) instead of showing the old week's jobs under the new week's title — a click
  // there opened a job of another week, and its rows were replaced under the pointer when the new week came in.
  const stale = q.isPlaceholderData;
  const open = jobs.find(j => j.id === selected);

  const step = view === 'day' ? 1 : 7;
  const title = view === 'day' ? fmt(anchor, locale, { weekday: 'long', month: 'long', day: 'numeric' }) : t('weekOf', { date: fmt(monday, locale, { month: 'short', day: 'numeric' }) });
  const blocked = (date: string) => (timeOff.data?.entries ?? []).filter(e => e.kind === 'closed' && !e.memberUserId && e.startsOn <= date && e.endsOn >= date);
  const upcoming = jobs.filter(j => !DONE.has(j.state)).length;

  return (
    <div className="nl-appt">
      <BoHeader title={t('title')} sub={t('sub', { count: upcoming })} />
      <div className="nl-cal-bar">
        <Segmented<View> name="appt-view" aria-label={t('view')} value={view} onChange={setView} options={[{ value: 'day', label: t('viewDay') }, { value: 'week', label: t('viewWeek') }, { value: 'list', label: t('viewList') }]} />
        <div className="nl-cal-nav">
          <button type="button" className="btn btn-secondary btn-icon" aria-label={view === 'day' ? t('prevDay') : t('prevWeek')} onClick={() => setAnchor(a => addDays(a, -step))}><CaretLeft size={18} /></button>
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => setAnchor(today())}>{t('today')}</button>
          <button type="button" className="btn btn-secondary btn-icon" aria-label={view === 'day' ? t('nextDay') : t('nextWeek')} onClick={() => setAnchor(a => addDays(a, step))}><CaretRight size={18} /></button>
        </div>
        <h2 className="nl-cal-label">{title}</h2>
        <ul className="nl-cal-legend" aria-label={t('legend')}>
          {(['confirmed', 'requested', 'en_route', 'completed'] as const).map(s => <li key={s}><span className="nl-cal-swatch" data-state={s} aria-hidden />{t(`state_${s}`)}</li>)}
        </ul>
      </div>

      {q.isPending ? <div className="nl-cal" aria-busy="true"><Skeleton height={420} /></div>
        : q.isError ? <ErrorState message={t('loadJobsError')} onRetry={() => void q.refetch()} />
        : view === 'list' ? <JobTable t={t} locale={locale} jobs={jobs} loading={stale} onOpen={setSelected} />
        : <div className="nl-appt-view" aria-busy={stale || undefined} inert={stale || undefined}>
            <TimeGrid t={t} locale={locale} days={view === 'week' ? weekDays(monday, jobs, cells) : [anchor]} jobs={jobs} cells={cells} selected={selected} onSelect={setSelected} blocked={blocked} />
          </div>}

      <QuoteRequests />
      <Drawer open={!!open} onClose={() => setSelected(null)} title={t('bookingDetail')}>
        {open ? <JobPanel jobId={open.id} /> : null}
      </Drawer>
    </div>
  );
}

/** Monday–Saturday, and Sunday when something is on it. */
function weekDays(monday: string, jobs: Job[], cells: CalendarCells): string[] {
  const days = Array.from({ length: 7 }, (_, i) => addDays(monday, i));
  const sunday = days[6]!;
  return jobs.some(j => localDate(j.startsAt) === sunday) || hasCells(dayCells(cells, sunday)) ? days : days.slice(0, 6);
}

const HOUR_PX = 64;
const MIN_ENTRY_PX = 44;

/** Where an entry sits on the grid: top and height in px from the grid's first hour. */
const place = (startsAt: string, minutes: number, firstHour: number) =>
  ({ ['--top' as string]: `${((minuteOfDay(startsAt) - firstHour * 60) / 60) * HOUR_PX}px`, ['--h' as string]: `${Math.max(MIN_ENTRY_PX, (minutes / 60) * HOUR_PX - 2)}px` });

const durationOf = (j: Job) => Math.max(15, Math.round((Date.parse(j.endsAt) - Date.parse(j.startsAt)) / 60000));

function JobButton({ j, t, locale, selected, onSelect, firstHour }: { j: Job; t: T; locale: Locale; selected: boolean; onSelect: (id: string) => void; firstHour: number }) {
  const me = useSession().data?.user.id;
  return (
    <button type="button" className="nl-appt-job" data-state={j.state} data-dim={DONE.has(j.state)} aria-pressed={selected} style={place(j.startsAt, durationOf(j), firstHour)} onClick={() => onSelect(j.id)}>
      <strong>{clock(j.startsAt, locale)}</strong> {j.memberName && j.memberUserId && j.memberUserId !== me ? t('jobWithMember', { title: j.title, member: j.memberName }) : j.title}<span className="nl-appt-job-who">{j.customerName ?? ''}</span>
    </button>
  );
}

/** S-74: one day's open slots and quote holds. */
const dayCells = (cells: CalendarCells, day: string): CalendarCells => ({
  openSlots: cells.openSlots.filter(c => localDate(c.startsAt) === day),
  quoteHolds: cells.quoteHolds.filter(c => localDate(c.startsAt) === day),
});
const hasCells = (c: CalendarCells) => c.openSlots.length > 0 || c.quoteHolds.length > 0;

/** A day's column: jobs, open slots and quote holds in time order (PH:62 week view; the cells are dim, not buttons). */
function DayEntries({ t, locale, jobs, cells, selected, onSelect, firstHour, slotMin }: { t: T; locale: Locale; jobs: Job[]; cells: CalendarCells; selected: string | null; onSelect: (id: string) => void; firstHour: number; slotMin: number }) {
  const entries = [
    ...jobs.map(j => ({ at: j.startsAt, key: `j-${j.id}`, node: <JobButton key={`j-${j.id}`} j={j} t={t} locale={locale} selected={selected === j.id} onSelect={onSelect} firstHour={firstHour} /> })),
    ...cells.openSlots.map(c => ({ at: c.startsAt, key: `o-${c.startsAt}`, node: <div key={`o-${c.startsAt}`} className="nl-appt-job nl-appt-cell" data-dim="true" style={place(c.startsAt, slotMin, firstHour)}><strong>{clock(c.startsAt, locale)}</strong> {t('openSlot')}</div> })),
    ...cells.quoteHolds.map(h => ({ at: h.startsAt, key: `q-${h.quoteId}`, node: <div key={`q-${h.quoteId}`} className="nl-appt-job nl-appt-cell nl-appt-hold" data-dim="true" style={place(h.startsAt, h.durationMin, firstHour)}><strong>{clock(h.startsAt, locale)}</strong> {t('heldForQuote', { name: h.customerName || 'none' })}</div> })),
  ].sort((a, b) => Date.parse(a.at) - Date.parse(b.at) || a.key.localeCompare(b.key));
  return <>{entries.map(e => e.node)}</>;
}

/**
 * The calendar (redesign § 2.2, Provider Hub PH:62): a 56px hour column and one column per day; bookings sit at their
 * time with a status-coloured left edge, today's column has the now line. Below 760px the days stack as lists.
 */
function TimeGrid({ t, locale, days, jobs, cells, selected, onSelect, blocked }: { t: T; locale: Locale; days: string[]; jobs: Job[]; cells: CalendarCells; selected: string | null; onSelect: (id: string) => void; blocked: (d: string) => { reason?: string | null }[] }) {
  const d0 = today();
  const inView = jobs.filter(j => days.includes(localDate(j.startsAt)));
  const starts = [...inView.map(j => minuteOfDay(j.startsAt)), ...days.flatMap(d => [...dayCells(cells, d).openSlots, ...dayCells(cells, d).quoteHolds].map(c => minuteOfDay(c.startsAt)))];
  const ends = [...inView.map(j => minuteOfDay(j.startsAt) + durationOf(j)), ...days.flatMap(d => dayCells(cells, d).quoteHolds.map(h => minuteOfDay(h.startsAt) + h.durationMin))];
  const firstHour = Math.max(0, Math.min(8, ...starts.map(m => Math.floor(m / 60))));
  const lastHour = Math.min(24, Math.max(18, ...ends.map(m => Math.ceil(m / 60)), ...starts.map(m => Math.floor(m / 60) + 1)));
  const hours = Array.from({ length: lastHour - firstHour }, (_, i) => firstHour + i);
  const empty = inView.length === 0 && days.every(d => !hasCells(dayCells(cells, d)) && blocked(d).length === 0);
  const now = minuteOfDay(new Date());
  return (
    <div className="nl-cal" style={{ ['--days' as string]: days.length, ['--grid-h' as string]: `${hours.length * HOUR_PX}px`, ['--hour' as string]: `${HOUR_PX}px` }}>
      <div className="nl-cal-head" aria-hidden>
        <span />
        {days.map(d => <div key={d} className="nl-cal-dayhead" data-today={d === d0 || undefined}><span>{fmt(d, locale, { weekday: 'short' })}</span><strong>{fmt(d, locale, { day: 'numeric' })}</strong></div>)}
      </div>
      <div className="nl-cal-body">
        <div className="nl-cal-hours" aria-hidden>{hours.map(h => <span key={h} style={{ ['--top' as string]: `${(h - firstHour) * HOUR_PX}px` }}>{hourLabel(h, locale)}</span>)}</div>
        {days.map(d => (
          <div key={d} role="group" className="nl-cal-col" data-today={d === d0 || undefined} aria-label={fmt(d, locale, { weekday: 'long', month: 'long', day: 'numeric' })}>
            <div className="nl-cal-colname" aria-hidden>{fmt(d, locale, { weekday: 'short', day: 'numeric' })}</div>
            {d === d0 && now >= firstHour * 60 && now <= lastHour * 60 ? <span className="nl-cal-now" style={{ ['--top' as string]: `${((now - firstHour * 60) / 60) * HOUR_PX}px` }} aria-hidden /> : null}
            {blocked(d).map((b, i) => <div key={i} className="nl-appt-job nl-appt-blocked" data-dim="true">{t('blocked', { why: b.reason ?? '—' })}</div>)}
            <DayEntries t={t} locale={locale} jobs={jobs.filter(j => localDate(j.startsAt) === d)} cells={dayCells(cells, d)} selected={selected} onSelect={onSelect} firstHour={firstHour} slotMin={30} />
          </div>
        ))}
      </div>
      {empty ? <p className="bo-muted nl-appt-empty">{days.length === 1 ? t('noJobsDay') : t('noJobsWeek')}</p> : null}
    </div>
  );
}

/** "9 am" / "9 h" on the hour column. */
function hourLabel(h: number, locale: Locale): string {
  if (locale === 'fr') return `${h} h`;
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${h12} ${h < 12 ? 'am' : 'pm'}`;
}

interface JobRow { id: string; when: string; job: string; who: string; member: string; state: string }
function JobTable({ t, locale, jobs, loading, onOpen }: { t: T; locale: Locale; jobs: Job[]; loading: boolean; onOpen: (id: string) => void }) {
  const rows: JobRow[] = jobs.map(j => ({ id: j.id, when: `${fmt(localDate(j.startsAt), locale, { weekday: 'short', day: 'numeric' })} · ${clock(j.startsAt, locale)}`, job: j.ref ? `${j.title} · ${j.ref}` : j.title, who: j.customerName ?? '—', member: j.memberName ?? '—', state: t(`state_${j.state}`) }));
  const columns: DataTableColumn<JobRow>[] = [
    { key: 'when', label: t('colWhen') }, { key: 'job', label: t('colJob'), primary: true }, { key: 'who', label: t('colCustomer') }, { key: 'member', label: t('colMember'), filter: 'facet' }, { key: 'state', label: t('colStatus'), type: 'tag' },
  ];
  return <DataTable<JobRow> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} loading={loading} can={{ create: false, update: false, delete: false, export: true }} onOpen={r => onOpen(r.id)} emptyText={t('noJobsWeek')} />;
}
