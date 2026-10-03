import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { Button, Checkbox, Dialog, EmptyState, ErrorState, Field, PageSkeleton, Select, Tag, TextArea, TextInput, useFormatters } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { meQuery } from '../shell/api';
import { useGrant } from '../shell/grant';
import { SCREEN_PATH } from '../shell/screens';
import { rotaQuery } from '../oncall/api';
import {
  checklistQuery, marketsQuery, useApprove, useCloseRequest, useHypercare, useRecordGate, useRequestLaunch, useRollback,
  type Checklist, type Gate, type RecordInput,
} from './api';
import { useGoLiveT, type GoLiveKey, type GoLiveT } from './messages';
import '../shell/queues.css';
import './golive.css';

export interface GoLiveSearch { market?: string }

const errorOf = (e: unknown) => (e instanceof ValidationError ? Object.values(e.byField())[0] : e instanceof ApiError ? e.message : e ? String(e) : undefined);
const TONE = { pass: 'accent', fail: 'accent-2', pending: 'neutral', not_applicable: 'neutral' } as const;

/** What a gate's evidence says, in words: the recorded text, else the platform's code with its values. */
export function evidenceText(g: Gate, t: GoLiveT, when: (iso: string) => string): string {
  if (g.code === 'recorded' && g.evidence) return g.evidence;
  const p: Record<string, string> = { ...g.params };
  for (const k of ['at', 'until']) if (p[k]) p[k] = when(p[k]!);
  if (p.stage) p.stage = t(`st_${p.stage}` as GoLiveKey);
  const key = `c_${g.code}` as GoLiveKey;
  const text = t(key, p);
  return text === key ? g.code : text;
}

/**
 * Go-live (S-118): per market, the checklist with its evidence and owners, manual gates recorded by their owners
 * (`attest`), the two-person switch to live with its emergency override, the rollback to pilot and the 14-day
 * hypercare rota (admins, `province`). Markets come from the region model; nothing here names a place.
 */
export function GoLive() {
  const t = useGoLiveT();
  const search = useSearch({ strict: false }) as GoLiveSearch;
  const navigate = useNavigate();
  const markets = useQuery(marketsQuery);
  if (markets.isPending) return <PageSkeleton kpis={2} rows={8} />;
  if (markets.isError) return <ErrorState message={t('loadError')} onRetry={() => void markets.refetch()} />;
  if (!markets.data.length) return <EmptyState>{t('noMarkets')}</EmptyState>;
  const chosen = markets.data.find(m => m.id === search.market)
    ?? markets.data.find(m => m.requestPending) ?? markets.data.find(m => m.stage === 'pilot') ?? markets.data[0]!;
  return (
    <div>
      <div className="nl-q-top">
        <span className="nl-q-kicker">{t('kicker')}</span>
        <div className="nl-q-filters">
          <Field label={t('market')}>
            <Select value={chosen.id} onChange={e => void navigate({ to: SCREEN_PATH.go_live, search: { market: e.target.value } as never })}
              options={markets.data.map(m => ({ value: m.id, label: t('marketLine', { city: m.city, province: m.province, stage: t(`st_${m.stage}` as GoLiveKey) }) }))} />
          </Field>
        </div>
      </div>
      <MarketChecklist key={chosen.id} market={chosen.id} />
    </div>
  );
}

function MarketChecklist({ market }: { market: string }) {
  const t = useGoLiveT();
  const { date } = useFormatters();
  const query = useQuery(checklistQuery(market));
  const { can, roleName } = useGrant();
  const [recording, setRecording] = useState<Gate>();
  if (query.isPending) return <PageSkeleton kpis={0} rows={8} />;
  if (query.isError) return <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />;
  const c = query.data;
  const when = (iso: string) => date(iso, 'dateTime');
  return (
    <>
      <h1 className="nl-q-title">{t('title', { city: c.market.city, stage: t(`st_${c.market.stage}` as GoLiveKey) })}</h1>
      <p className="nl-q-lede">{t('lede')}</p>
      <p role="status" className={c.ready ? 'nl-gl-ready' : 'nl-q-error'}>{c.ready ? t('ready') : t('blocked', { n: c.blocking.length })}</p>
      {!can('attest') && !can('province') ? <p className="nl-q-note">{t('viewOnly', { role: roleName })}</p> : null}
      <div className="nl-q-cols nl-q-gap">
        <section aria-labelledby="gl-gates">
          <h2 id="gl-gates" className="nl-q-h2">{t('gatesTitle')}</h2>
          <ul className="nl-q-list">
            {c.gates.map(g => (
              <li key={g.key} className="nl-q-row nl-gl-gate">
                <div className="nl-gl-what">
                  <strong>{t(`g_${g.key}` as GoLiveKey)}</strong>{g.required ? null : <span className="nl-q-note"> · {t('optional')}</span>}
                  <span className="nl-q-note">{t(`o_${g.owner}` as GoLiveKey)} · {t(`k_${g.kind}` as GoLiveKey)}</span>
                  <span>{evidenceText(g, t, when)}{g.evidenceUrl ? <> · <a href={g.evidenceUrl} target="_blank" rel="noreferrer">{t('evidenceLink')}</a></> : null}</span>
                  {g.recordedBy && g.recordedAt ? <span className="nl-q-note">{t(g.source === 'script' ? 'byScript' : 'by', { name: g.recordedBy.name, at: when(g.recordedAt) })}</span> : null}
                  <span className="nl-q-note">{t('runbook', { path: g.runbook })}</span>
                </div>
                <div className="nl-q-actions">
                  <Tag tone={TONE[g.status]}>{t(`s_${g.status}` as GoLiveKey)}</Tag>
                  {g.recordable && can('attest') ? <Button variant="secondary" onClick={() => setRecording(g)}>{t('record')}</Button> : null}
                </div>
              </li>
            ))}
          </ul>
        </section>
        <div>
          <LaunchPanel c={c} canSwitch={can('province')} />
          {c.market.stage === 'live' && can('province') ? <Hypercare c={c} /> : c.hypercare ? <Hypercare c={c} readOnly /> : null}
          <History c={c} />
        </div>
      </div>
      {recording ? <RecordDialog market={market} gate={recording} onClose={() => setRecording(undefined)} /> : null}
    </>
  );
}

function RecordDialog({ market, gate, onClose }: { market: string; gate: Gate; onClose: () => void }) {
  const t = useGoLiveT();
  const record = useRecordGate(market);
  const [status, setStatus] = useState<RecordInput['status']>('pass');
  const [evidence, setEvidence] = useState('');
  const [url, setUrl] = useState('');
  const error = errorOf(record.error);
  return (
    <Dialog open onClose={onClose} title={t('recordTitle', { gate: t(`g_${gate.key}` as GoLiveKey) })} actions={<>
      <Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
      <Button disabled={record.isPending} onClick={() => record.mutate({ gate: gate.key, status, evidence, evidenceUrl: url || undefined }, { onSuccess: onClose })}>{t('save')}</Button>
    </>}>
      <Field label={t('status')}>
        <Select value={status} onChange={e => setStatus(e.target.value as RecordInput['status'])}
          options={(['pass', 'fail', 'not_applicable'] as const).map(s => ({ value: s, label: t(`s_${s}` as GoLiveKey) }))} />
      </Field>
      <Field label={t('evidence')} hint={t('evidenceHint')}><TextArea value={evidence} maxLength={1000} onChange={e => setEvidence(e.target.value)} /></Field>
      <Field label={t('evidenceLink')}><TextInput type="url" value={url} maxLength={500} placeholder="https://" onChange={e => setUrl(e.target.value)} /></Field>
      {error ? <p role="alert" className="nl-q-error">{error}</p> : null}
    </Dialog>
  );
}

function LaunchPanel({ c, canSwitch }: { c: Checklist; canSwitch: boolean }) {
  const t = useGoLiveT();
  const { date } = useFormatters();
  const me = useQuery(meQuery).data?.userId;
  const ask = useRequestLaunch(c.market.id);
  const approve = useApprove(c.market.id);
  const close = useCloseRequest(c.market.id);
  const rollback = useRollback(c.market.id);
  const [note, setNote] = useState('');
  const [override, setOverride] = useState(false);
  const [reason, setReason] = useState('');
  const [confirm, setConfirm] = useState('');
  const [rolling, setRolling] = useState(false);
  const when = (iso: string) => date(iso, 'dateTime');
  const error = errorOf(ask.error ?? approve.error ?? close.error);
  const r = c.request;
  const launched = c.events.find(e => e.kind === 'launched');
  return (
    <section className="nl-q-panel" aria-labelledby="gl-launch">
      <h2 id="gl-launch" className="nl-q-h2">{c.market.stage === 'live' ? t('rollbackTitle') : t('launchTitle')}</h2>
      {c.market.stage === 'live' ? <>
        {launched ? <p>{t('liveSince', { at: when(launched.at) })}</p> : null}
        <p className="nl-q-note">{t('rollbackLede')}</p>
        {canSwitch ? <Button variant="secondary" onClick={() => setRolling(true)}>{t('rollback')}</Button> : null}
      </> : c.market.stage !== 'pilot' ? null : r ? <>
        <p>{t('pending', { name: r.requestedBy.name, at: when(r.requestedAt) })} {t('expires', { at: when(r.expiresAt) })}</p>
        {r.note ? <p>{r.note}</p> : null}
        {r.override ? <p className="nl-q-error">{t('pendingOverride', { reason: r.overrideReason ?? '' })}</p> : null}
        {r.blocking.length ? <p className="nl-q-note">{t('blockingAtRequest', { gates: r.blocking.map(k => t(`g_${k}` as GoLiveKey)).join(', ') })}</p> : null}
        {canSwitch && r.requestedBy.id !== me ? <>
          <Field label={t('confirm', { city: c.market.city })}><TextInput value={confirm} onChange={e => setConfirm(e.target.value)} /></Field>
          <div className="nl-q-actions">
            <Button disabled={approve.isPending || !confirm.trim()} onClick={() => approve.mutate({ id: r.id, confirm })}>{t('approve')}</Button>
            <Button variant="ghost" disabled={close.isPending} onClick={() => close.mutate({ id: r.id })}>{t('reject')}</Button>
          </div>
        </> : canSwitch ? <>
          <p className="nl-q-note">{t('ownRequest')}</p>
          <Button variant="ghost" disabled={close.isPending} onClick={() => close.mutate({ id: r.id })}>{t('withdraw')}</Button>
        </> : null}
      </> : <>
        <p className="nl-q-note">{t('launchLede')}</p>
        {canSwitch ? <>
          <Field label={t('note')}><TextInput value={note} maxLength={500} onChange={e => setNote(e.target.value)} /></Field>
          {!c.ready ? <Checkbox checked={override} onChange={setOverride} label={t('override')} /> : null}
          {override && !c.ready ? <Field label={t('overrideReason')} hint={t('overrideHint')}><TextArea value={reason} maxLength={500} onChange={e => setReason(e.target.value)} /></Field> : null}
          <div className="nl-q-actions">
            <Button disabled={ask.isPending || (!c.ready && !override)} onClick={() => ask.mutate({ note: note || undefined, overrideReason: override && !c.ready ? reason : undefined })}>{t('requestLaunch')}</Button>
          </div>
        </> : null}
      </>}
      {error ? <p role="alert" className="nl-q-error">{error}</p> : null}
      {rolling ? <RollbackDialog c={c} pending={rollback.isPending} error={errorOf(rollback.error)} onClose={() => setRolling(false)}
        onSubmit={v => rollback.mutate(v, { onSuccess: () => setRolling(false) })} /> : null}
    </section>
  );
}

function RollbackDialog({ c, pending, error, onClose, onSubmit }: { c: Checklist; pending: boolean; error?: string; onClose: () => void; onSubmit: (v: { reason: string; confirm: string }) => void }) {
  const t = useGoLiveT();
  const [reason, setReason] = useState('');
  const [confirm, setConfirm] = useState('');
  return (
    <Dialog open onClose={onClose} title={t('rollbackTitle')} actions={<>
      <Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
      <Button disabled={pending || !confirm.trim()} onClick={() => onSubmit({ reason, confirm })}>{t('rollback')}</Button>
    </>}>
      <p>{t('rollbackLede')}</p>
      <Field label={t('reason')} hint={t('rollbackHint')}><TextArea value={reason} maxLength={500} onChange={e => setReason(e.target.value)} /></Field>
      <Field label={t('confirm', { city: c.market.city })}><TextInput value={confirm} onChange={e => setConfirm(e.target.value)} /></Field>
      {error ? <p role="alert" className="nl-q-error">{error}</p> : null}
    </Dialog>
  );
}

function Hypercare({ c, readOnly }: { c: Checklist; readOnly?: boolean }) {
  const t = useGoLiveT();
  const { date } = useFormatters();
  const day = (d: string) => date(`${d}T12:00:00Z`, 'full');
  return (
    <section className="nl-q-section" aria-labelledby="gl-hypercare">
      <h2 id="gl-hypercare" className="nl-q-h2">{t('hypercareTitle')}</h2>
      <p className="nl-q-note">{t('hypercareLede')}</p>
      {c.hypercare ? <>
        <p>{t('hypercareRange', { from: day(c.hypercare.startsOn), to: day(c.hypercare.endsOn) })}</p>
        <div className="nl-q-tablewrap">
          <table className="nl-gl-table">
            <thead><tr><th scope="col">{t('colDay')}</th><th scope="col">{t('colPrimary')}</th><th scope="col">{t('colSecondary')}</th><th scope="col">{t('colBusiness')}</th></tr></thead>
            <tbody>{c.hypercare.days.map(d => <tr key={d.date}><th scope="row">{day(d.date)}</th><td>{d.primary.name}</td><td>{d.secondary.name}</td><td>{d.business.name}</td></tr>)}</tbody>
          </table>
        </div>
      </> : readOnly ? null : <HypercareForm market={c.market.id} />}
    </section>
  );
}

function HypercareForm({ market }: { market: string }) {
  const t = useGoLiveT();
  const staff = useQuery(rotaQuery).data?.staff ?? [];
  const create = useHypercare(market);
  const [startsOn, setStartsOn] = useState('');
  const [picked, setPicked] = useState<Record<'primaries' | 'secondaries' | 'businessContacts', string[]>>({ primaries: [], secondaries: [], businessContacts: [] });
  const toggle = (k: keyof typeof picked, id: string, on: boolean) => setPicked(p => ({ ...p, [k]: on ? [...p[k], id] : p[k].filter(x => x !== id) }));
  const error = errorOf(create.error);
  const group = (k: keyof typeof picked, label: string) => (
    <fieldset className="nl-gl-people">
      <legend>{label}</legend>
      {staff.map(s => <Checkbox key={s.id} checked={picked[k].includes(s.id)} onChange={on => toggle(k, s.id, on)} label={s.name} />)}
    </fieldset>
  );
  return (
    <>
      <Field label={t('startsOn')}><TextInput type="date" value={startsOn} onChange={e => setStartsOn(e.target.value)} /></Field>
      {group('primaries', t('primaries'))}
      {group('secondaries', t('secondaries'))}
      {group('businessContacts', t('business'))}
      <div className="nl-q-actions">
        <Button disabled={create.isPending} onClick={() => create.mutate({ ...picked, startsOn: startsOn || undefined })}>{t('createHypercare')}</Button>
      </div>
      {error ? <p role="alert" className="nl-q-error">{error}</p> : null}
    </>
  );
}

function History({ c }: { c: Checklist }) {
  const t = useGoLiveT();
  const { date } = useFormatters();
  const when = (iso: string) => date(iso, 'dateTime');
  const items = [
    ...c.events.map(e => ({ at: e.at, text: t(`e_${e.kind}` as GoLiveKey, { name: e.by.name, reason: e.reason ?? '' }) })),
    ...c.requests.filter(r => r.state !== 'approved').map(r => ({ at: r.requestedAt, text: t('requestLine', { by: r.requestedBy.name, at: when(r.requestedAt), state: t(`r_${r.state}` as GoLiveKey) }) })),
  ].sort((a, b) => b.at.localeCompare(a.at));
  return (
    <section className="nl-q-section" aria-labelledby="gl-history">
      <h2 id="gl-history" className="nl-q-h2">{t('historyTitle')}</h2>
      {items.length ? <ul className="nl-q-list">{items.map((i, n) => <li key={n}><span className="nl-q-note">{when(i.at)}</span> · {i.text}</li>)}</ul> : <p className="nl-q-note">{t('noHistory')}</p>}
    </section>
  );
}
