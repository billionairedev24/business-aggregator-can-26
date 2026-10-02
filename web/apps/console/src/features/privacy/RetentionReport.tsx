import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, DataTable, Dialog, Drawer, ErrorState, PageSkeleton, Tag, useFormatters, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { useGrant } from '../shell/grant';
import { EXPORT_URL, periodParts, reportQuery, useRun, type Category, type RunView } from './retentionApi';
import { useRetentionT, type RetentionKey, type RetentionT } from './retentionMessages';
import '../shell/queues.css';

interface Row { id: string; name: string; code: string; period: string; action: string; lastRun: string; rows: string; held: string; next: string; state: string; c: Category }

type State = 'ok' | 'overdue' | 'never' | 'failed';

function stateOf(c: Category): State | undefined {
  if (c.enforcement !== 'job' && c.enforcement !== 'pipeline') return undefined;
  if (c.overdue) return 'overdue';
  if (!c.lastRun) return 'never';
  return c.lastRun.outcome === 'failed' ? 'failed' : 'ok';
}

/** "2 years" / "2 ans"; the raw ISO period when it isn't one the screen words. */
export function usePeriod(t: RetentionT) {
  return (iso: string | null | undefined) => {
    const p = periodParts(iso);
    return p ? t(`p_${p.unit}` as RetentionKey, { n: p.n }) : (iso ?? '');
  };
}

/**
 * The retention report (S-107): every category of the Privacy Policy's retention schedule with its period, action,
 * last run, rows changed, rows a legal hold keeps and the next run; the detail with the policy's words, the legal basis
 * and the holds; dry runs and runs (the `privacy` grant: privacy officer, support lead, admin) and the CSV export.
 */
export function RetentionReport() {
  const t = useRetentionT();
  const { date } = useFormatters();
  const period = usePeriod(t);
  const report = useQuery(reportQuery());
  const { can, roleName } = useGrant();
  const run = useRun();
  const [open, setOpen] = useState<string>();
  const [confirming, setConfirming] = useState<{ category?: string }>();
  const [notice, setNotice] = useState<string>();
  const done = (runs: RunView[], dryRun: boolean) => {
    const n = runs.reduce((sum, r) => sum + r.affected, 0);
    setNotice(dryRun ? t('ranDry', { n }) : t('ran', { n }));
  };
  const start = (dryRun: boolean, category?: string) =>
    run.mutate({ dryRun, category }, { onSuccess: runs => { done(runs, dryRun); setConfirming(undefined); } });

  if (report.isPending) return <PageSkeleton kpis={0} rows={8} />;
  if (report.isError) return <ErrorState message={t('loadError')} onRetry={() => void report.refetch()} />;
  const r = report.data;
  const rows: Row[] = r.categories.map(c => {
    const state = stateOf(c);
    return {
      id: c.code, name: c.name, code: c.code,
      period: c.period ? (c.afterDisputeClosed ? t('afterDispute', { period: period(c.period), after: period(c.afterDisputeClosed) }) : period(c.period)) : t('noEnd'),
      action: t(`a_${c.action}`),
      lastRun: c.lastRun ? `${date(c.lastRun.finishedAt, 'dateTime')}${c.lastRun.dryRun ? ` ${t('dryTag')}` : ''}` : t('never'),
      rows: String(c.rowsAffected), held: String(c.held),
      next: c.nextDueAt ? date(c.nextDueAt, 'dateTime') : t('never'),
      state: state ? t(`s_${state}`) : t(`e_${c.enforcement}`), c,
    };
  });
  const columns: DataTableColumn<Row>[] = [
    { key: 'name', label: t('colCategory'), sub: 'code', primary: true },
    { key: 'period', label: t('colPeriod') },
    { key: 'action', label: t('colAction'), type: 'tag', editable: false },
    { key: 'lastRun', label: t('colLastRun') },
    { key: 'rows', label: t('colRows') },
    { key: 'held', label: t('colHeld') },
    { key: 'next', label: t('colNext') },
    { key: 'state', label: t('colState'), type: 'tag', editable: false },
  ];
  const tones = (row: Row): Partial<Record<keyof Row, DataTableTone>> => {
    const s = stateOf(row.c);
    return { action: 'tag-neutral', state: s === 'overdue' || s === 'failed' ? 'tag-accent-2' : s === 'never' || row.c.enforcement === 'blocked' ? 'tag-highlight' : s ? 'tag-accent' : 'tag-neutral' };
  };
  const overdue = r.categories.filter(c => c.overdue).length;
  const selected = r.categories.find(c => c.code === open);
  return (
    <div>
      <span className="nl-q-kicker">{t('kicker')}</span>
      <h1 className="nl-q-title">{t('title', { n: r.categories.length, overdue })}</h1>
      <p className="nl-q-lede">{t('lede')}</p>
      <p className="nl-q-note">{r.nextRunAt ? t('nextRun', { date: date(r.nextRunAt, 'dateTime') }) : t('noSchedule')}{r.dryRunOnly ? ` ${t('dryRunOnly')}` : ''}</p>
      <div className="nl-q-actions">
        {can('privacy') ? <>
          <Button type="button" variant="secondary" disabled={run.isPending} onClick={() => start(true)}>{t('dryRunAll')}</Button>
          <Button type="button" variant="ghost" disabled={run.isPending} onClick={() => setConfirming({})}>{t('runAll')}</Button>
        </> : null}
        <a className="btn btn-secondary" href={EXPORT_URL} download>{t('exportCsv')}</a>
      </div>
      {notice && !selected ? <p role="status" className="nl-q-note">{notice}</p> : null}
      {run.error ? <p role="alert" className="nl-q-error">{run.error.message}</p> : null}
      <DataTable<Row> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} rowTones={tones}
        can={{ create: false, update: false, delete: false, export: false }} roleName={roleName} emptyText={t('empty')} pageSize={25}
        onOpen={row => setOpen(row.id)} />
      <div className="nl-q-cols nl-q-gap">
        <section className="nl-q-panel">
          <h2 className="nl-q-h2">{t('lawsTitle')}</h2>
          <ul className="nl-q-list">
            {r.laws.map(l => <li key={l.code}><strong>{l.name}</strong> · {t('lawDays', { days: l.decisionRetentionDays })}</li>)}
          </ul>
        </section>
        <section className="nl-q-panel">
          <h2 className="nl-q-h2">{t('otherTitle')}</h2>
          <p className="nl-q-note">{t('otherLede')}</p>
          <ul className="nl-q-list">
            {r.operational.map(o => <li key={o.code}><strong>{o.name}</strong> · {period(o.period)} · {o.where}{o.setting !== '—' ? ` · ${t('setting')}: ${o.setting}` : ''}</li>)}
          </ul>
        </section>
      </div>
      {selected ? <CategoryDrawer c={selected} t={t} canRun={can('privacy')} busy={run.isPending} onClose={() => setOpen(undefined)}
        onRun={dryRun => (dryRun ? start(true, selected.code) : setConfirming({ category: selected.code }))} notice={notice} /> : null}
      {confirming ? (
        <Dialog open onClose={() => setConfirming(undefined)} title={t('confirmTitle')}
          actions={<><Button variant="ghost" onClick={() => setConfirming(undefined)}>{t('cancel')}</Button>
            <Button className="nl-danger" disabled={run.isPending} onClick={() => start(false, confirming.category)}>{t('confirm')}</Button></>}>
          <p>{t('confirmBody')}</p>
        </Dialog>
      ) : null}
    </div>
  );
}

function CategoryDrawer({ c, t, canRun, busy, onClose, onRun, notice }: {
  c: Category; t: RetentionT; canRun: boolean; busy: boolean; onClose: () => void; onRun: (dryRun: boolean) => void; notice?: string;
}) {
  const { date } = useFormatters();
  const runs = c.enforcement === 'job' || c.enforcement === 'pipeline';
  const last = c.lastRun;
  return (
    <Drawer open onClose={onClose} title={c.name} width={560}
      footer={canRun && runs ? (
        <div className="nl-q-actions">
          <Button type="button" variant="secondary" disabled={busy} onClick={() => onRun(true)}>{t('dryRun')}</Button>
          <Button type="button" variant="ghost" disabled={busy} onClick={() => onRun(false)}>{t('runNow')}</Button>
        </div>
      ) : null}>
      <p><Tag tone="neutral">{t(`e_${c.enforcement}`)}</Tag> <Tag tone="accent">{t(`a_${c.action}`)}</Tag></p>
      <p>{c.clause && c.policy ? t('detailPolicy', { clause: c.clause, policy: c.policy }) : t('notInPolicy')}</p>
      <ul className="nl-q-list">
        <li><strong>{t('starts')}</strong>: {c.starts}</li>
        <li><strong>{t('basis')}</strong>: {c.basis}</li>
        <li><strong>{t('owner')}</strong>: {c.module} · <code>{c.code}</code></li>
        <li><strong>{t('holdsTitle')}</strong>: {c.holds.length ? c.holds.map(h => t(`h_${h}` as RetentionKey)).join(', ') : t('noHolds')}</li>
        {c.lawMinimum ? <li>{t('lawMin')}</li> : null}
        {last ? <li><strong>{t('colLastRun')}</strong>: {t('lastRunLine', {
          date: `${date(last.finishedAt, 'dateTime')}${last.dryRun ? ` ${t('dryTag')}` : ''}`, outcome: t(`o_${last.outcome}` as RetentionKey),
          affected: last.affected, held: last.held, remaining: last.remaining,
        })}</li> : null}
      </ul>
      {c.note ? <p className="nl-q-note">{c.note}</p> : null}
      {notice ? <p role="status" className="nl-q-note">{notice}</p> : null}
    </Drawer>
  );
}
