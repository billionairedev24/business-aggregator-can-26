import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CaretLeft, CaretRight } from '@phosphor-icons/react';
import { DataTable, ErrorState, Segmented, Skeleton, useLocale, type DataTableColumn, type Locale } from '@northline/ui';
import { useMerchantId } from '../shell/api';
import { useSession } from '../../lib/session';
import { timeOffQuery } from '../availability/api';
import { addDays, clock, localDate, localInstant, mondayOf, today } from '../../lib/time';
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

  useEffect(() => {
    if (selected && jobs.some(j => j.id === selected)) return;
    const d0 = today();
    const pick = jobs.find(j => localDate(j.startsAt) === d0 && !DONE.has(j.state)) ?? jobs.find(j => !DONE.has(j.state)) ?? jobs[0];
    if (pick) setSelected(pick.id);
  }, [jobs, selected]);

  const step = view === 'day' ? 1 : 7;
  const title = view === 'day' ? fmt(anchor, locale, { weekday: 'long', month: 'long', day: 'numeric' }) : t('weekOf', { date: fmt(monday, locale, { month: 'short', day: 'numeric' }) });
  const blocked = (date: string) => (timeOff.data?.entries ?? []).filter(e => e.kind === 'closed' && !e.memberUserId && e.startsOn <= date && e.endsOn >= date);

  return (
    <div className="nl-appt">
      <div className="nl-appt-head">
        <div>
          <span className="nl-kicker">{t('kicker')}</span>
          <div className="nl-appt-titlerow">
            <button type="button" className="btn btn-ghost btn-icon" aria-label={view === 'day' ? t('prevDay') : t('prevWeek')} onClick={() => setAnchor(a => addDays(a, -step))}><CaretLeft size={18} /></button>
            <h1 className="nl-page-title">{title}</h1>
            <button type="button" className="btn btn-ghost btn-icon" aria-label={view === 'day' ? t('nextDay') : t('nextWeek')} onClick={() => setAnchor(a => addDays(a, step))}><CaretRight size={18} /></button>
          </div>
        </div>
        <Segmented<View> name="appt-view" aria-label={t('view')} value={view} onChange={setView} options={[{ value: 'day', label: t('viewDay') }, { value: 'week', label: t('viewWeek') }, { value: 'list', label: t('viewList') }]} />
      </div>

      {q.isPending ? <div className="nl-appt-week" aria-busy="true">{Array.from({ length: 6 }, (_, i) => <div key={i}><Skeleton height={14} width={60} style={{ marginBottom: 8 }} /><Skeleton height={56} style={{ marginBottom: 6 }} /><Skeleton height={56} /></div>)}</div>
        : q.isError ? <ErrorState message={t('loadJobsError')} onRetry={() => void q.refetch()} />
        : view === 'list' ? <JobTable t={t} locale={locale} jobs={jobs} loading={stale} onOpen={setSelected} />
        : <div className="nl-appt-view" aria-busy={stale || undefined} inert={stale || undefined}>
            {view === 'week'
              ? <WeekGrid t={t} locale={locale} monday={monday} jobs={jobs} cells={cells} selected={selected} onSelect={setSelected} blocked={blocked} />
              : <DayList t={t} locale={locale} jobs={jobs} cells={dayCells(cells, anchor)} selected={selected} onSelect={setSelected} blocked={blocked(anchor)} />}
          </div>}

      <div className="nl-appt-cols">
        <QuoteRequests />
        <JobPanel jobId={selected} />
      </div>
    </div>
  );
}

function JobButton({ j, t, locale, selected, onSelect }: { j: Job; t: T; locale: Locale; selected: boolean; onSelect: (id: string) => void }) {
  const me = useSession().data?.user.id;
  return (
    <button type="button" className="nl-appt-job" data-dim={DONE.has(j.state)} aria-pressed={selected} onClick={() => onSelect(j.id)}>
      <strong>{clock(j.startsAt, locale)}</strong><br />{j.memberName && j.memberUserId && j.memberUserId !== me ? t('jobWithMember', { title: j.title, member: j.memberName }) : j.title}<br /><span className="nl-appt-job-who">{j.customerName ?? ''}</span>
    </button>
  );
}

/** S-74: one day's open slots and quote holds. */
const dayCells = (cells: CalendarCells, day: string): CalendarCells => ({
  openSlots: cells.openSlots.filter(c => localDate(c.startsAt) === day),
  quoteHolds: cells.quoteHolds.filter(c => localDate(c.startsAt) === day),
});
const hasCells = (c: CalendarCells) => c.openSlots.length > 0 || c.quoteHolds.length > 0;

/** A day's column: jobs, open slots and quote holds in time order (design 02 week view; the cells are dim). */
function DayEntries({ t, locale, jobs, cells, selected, onSelect }: { t: T; locale: Locale; jobs: Job[]; cells: CalendarCells; selected: string | null; onSelect: (id: string) => void }) {
  const entries = [
    ...jobs.map(j => ({ at: j.startsAt, key: `j-${j.id}`, node: <JobButton key={`j-${j.id}`} j={j} t={t} locale={locale} selected={selected === j.id} onSelect={onSelect} /> })),
    ...cells.openSlots.map(c => ({ at: c.startsAt, key: `o-${c.startsAt}`, node: <div key={`o-${c.startsAt}`} className="nl-appt-job nl-appt-cell" data-dim="true"><strong>{clock(c.startsAt, locale)}</strong><br />{t('openSlot')}</div> })),
    ...cells.quoteHolds.map(h => ({ at: h.startsAt, key: `q-${h.quoteId}`, node: <div key={`q-${h.quoteId}`} className="nl-appt-job nl-appt-cell" data-dim="true"><strong>{clock(h.startsAt, locale)}</strong><br />{t('heldForQuote', { name: h.customerName || 'none' })}</div> })),
  ].sort((a, b) => Date.parse(a.at) - Date.parse(b.at) || a.key.localeCompare(b.key));
  return <>{entries.map(e => e.node)}</>;
}

function WeekGrid({ t, locale, monday, jobs, cells, selected, onSelect, blocked }: { t: T; locale: Locale; monday: string; jobs: Job[]; cells: CalendarCells; selected: string | null; onSelect: (id: string) => void; blocked: (d: string) => { reason?: string | null }[] }) {
  const days = Array.from({ length: 7 }, (_, i) => addDays(monday, i));
  const sunday = days[6]!;
  const shown = jobs.some(j => localDate(j.startsAt) === sunday) || hasCells(dayCells(cells, sunday)) ? days : days.slice(0, 6);
  if (jobs.length === 0 && !hasCells(cells) && shown.every(d => blocked(d).length === 0)) return <p className="nl-muted nl-appt-empty">{t('noJobsWeek')}</p>;
  return (
    <div className="nl-appt-week" style={{ ['--nl-days' as string]: shown.length }}>
      {shown.map(d => (
        <div key={d} role="group" aria-label={fmt(d, locale, { weekday: 'long', month: 'long', day: 'numeric' })}>
          <div className="nl-appt-dayname">{fmt(d, locale, { weekday: 'short', day: 'numeric' })}</div>
          {blocked(d).map((b, i) => <div key={i} className="nl-appt-job nl-appt-blocked" data-dim="true">{t('blocked', { why: b.reason ?? '—' })}</div>)}
          <DayEntries t={t} locale={locale} jobs={jobs.filter(j => localDate(j.startsAt) === d)} cells={dayCells(cells, d)} selected={selected} onSelect={onSelect} />
        </div>
      ))}
    </div>
  );
}

function DayList({ t, locale, jobs, cells, selected, onSelect, blocked }: { t: T; locale: Locale; jobs: Job[]; cells: CalendarCells; selected: string | null; onSelect: (id: string) => void; blocked: { reason?: string | null }[] }) {
  if (jobs.length === 0 && !hasCells(cells) && blocked.length === 0) return <p className="nl-muted nl-appt-empty">{t('noJobsDay')}</p>;
  return (
    <div className="nl-appt-day">
      {blocked.map((b, i) => <div key={i} className="nl-appt-job nl-appt-blocked" data-dim="true">{t('blocked', { why: b.reason ?? '—' })}</div>)}
      <DayEntries t={t} locale={locale} jobs={jobs} cells={cells} selected={selected} onSelect={onSelect} />
    </div>
  );
}

interface JobRow { id: string; when: string; job: string; who: string; member: string; state: string }
function JobTable({ t, locale, jobs, loading, onOpen }: { t: T; locale: Locale; jobs: Job[]; loading: boolean; onOpen: (id: string) => void }) {
  const rows: JobRow[] = jobs.map(j => ({ id: j.id, when: `${fmt(localDate(j.startsAt), locale, { weekday: 'short', day: 'numeric' })} · ${clock(j.startsAt, locale)}`, job: j.ref ? `${j.title} · ${j.ref}` : j.title, who: j.customerName ?? '—', member: j.memberName ?? '—', state: t(`state_${j.state}`) }));
  const columns: DataTableColumn<JobRow>[] = [
    { key: 'when', label: t('colWhen') }, { key: 'job', label: t('colJob'), primary: true }, { key: 'who', label: t('colCustomer') }, { key: 'member', label: t('colMember'), filter: 'facet' }, { key: 'state', label: t('colStatus'), type: 'tag' },
  ];
  return <DataTable<JobRow> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} loading={loading} can={{ create: false, update: false, delete: false, export: true }} onOpen={r => onOpen(r.id)} emptyText={t('noJobsWeek')} />;
}
