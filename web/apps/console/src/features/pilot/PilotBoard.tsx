import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { Button, DataTable, Dialog, Drawer, ErrorState, Field, FileButton, PageSkeleton, Select, Tag, TextArea, TextInput, useFormatters, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { meQuery } from '../shell/api';
import { SCREEN_PATH } from '../shell/screens';
import {
  BLOCKER_OWNERS, boardQuery, detailQuery, exportUrl, photoUrl, TYPES, useBlocker, useCancelVisit, useInvite, useNote, useOwner, useReinvite, useScheduleVisit,
  useVisitOutcome, useVisitPhoto, type Detail, type Market, type Row, type Step, type Visit,
} from './api';
import { usePilotT, type PilotKey, type PilotT } from './messages';
import '../shell/queues.css';

export interface PilotSearch { market?: string; pilot?: string }

interface TableRow { id: string; name: string; sub: string; type: string; stage: string; next: string; owner: string; blocker: string; row: Row }

/** The words for a step's next action, with its parameters (dates in the reader's format). */
export function actionText(step: Step | null | undefined, t: PilotT, when: (iso: string) => string): string {
  if (!step) return t('a_none');
  if (!step.action) return t(`s_${step.key}` as PilotKey);
  const p: Record<string, string> = { ...step.params };
  for (const k of ['expiresAt', 'at', 'slot']) if (p[k]) p[k] = when(p[k]!);
  const key = `a_${step.action}` as PilotKey;
  const text = t(key, p);
  return text === key ? step.action : text;
}

/**
 * Pilot onboarding (S-120): a market's pilot businesses from invite to live — stage counts, a filterable table, the
 * CSV, and per business the derived checklist with the next action and who has to act, the blocker and owner staff
 * wrote down, notes, invites and kitchen visits. Merchant success and admins act (`onboard`); trust & safety read.
 */
export function PilotBoard() {
  const t = usePilotT();
  const { date } = useFormatters();
  const search = useSearch({ strict: false }) as PilotSearch;
  const navigate = useNavigate();
  const go = (s: PilotSearch) => void navigate({ to: SCREEN_PATH.pilot, search: { ...search, ...s } as never });
  const query = useQuery(boardQuery(search.market));
  const { can, roleName } = useGrant();
  const [inviting, setInviting] = useState(false);
  const [notice, setNotice] = useState<string>();

  if (query.isPending) return <PageSkeleton kpis={4} rows={6} />;
  if (query.isError && !query.data) return <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />;
  const b = query.data!;
  const when = (iso: string) => date(iso, 'dateTime');
  const rows: TableRow[] = b.items.map(r => ({
    id: r.id, name: r.businessName, sub: r.city ? `${r.label} · ${r.city}` : r.label, type: t(`t_${r.businessType}` as PilotKey),
    stage: t(`s_${r.stage}` as PilotKey), next: actionText(r.next, t, when) + (r.next?.owner ? ` · ${t(`o_${r.next.owner}` as PilotKey)}` : ''),
    owner: r.ownerName ?? t('nobody'), blocker: r.blocked ? (r.blocker ?? t('blockedTag')) : '', row: r,
  }));
  const tones = (r: TableRow): Partial<Record<keyof TableRow, DataTableTone>> => ({ stage: r.row.stage === 'live' ? 'tag-accent' : r.row.blocked ? 'tag-accent-2' : 'tag-neutral' });
  const columns: DataTableColumn<TableRow>[] = [
    { key: 'name', label: t('colBusiness'), sub: 'sub', subLabel: t('colMarket'), primary: true },
    { key: 'type', label: t('colType'), filter: 'facet' },
    { key: 'stage', label: t('colStage'), type: 'tag', filter: 'facet', editable: false },
    { key: 'next', label: t('colNext'), editable: false },
    { key: 'owner', label: t('colOwner'), filter: 'facet', editable: false },
    { key: 'blocker', label: t('colBlocker'), editable: false },
  ];
  const marketName = (m: Market) => t('marketLine', { city: m.city, province: m.province, stage: t(`st_${m.stage}` as PilotKey) });
  return (
    <div>
      <div className="nl-q-top">
        <span className="nl-q-kicker">{t('kicker')}</span>
        <div className="nl-q-filters">
          <Field label={t('market')}>
            <Select value={search.market ?? ''} onChange={e => go({ market: e.target.value || undefined, pilot: undefined })}
              options={[{ value: '', label: t('allMarkets') }, ...b.markets.map(m => ({ value: m.id, label: marketName(m) }))]} />
          </Field>
        </div>
      </div>
      <h1 className="nl-q-title">{t('title', { n: b.items.length, live: b.live, blocked: b.blocked })}</h1>
      <p className="nl-q-lede">{t('lede')}</p>
      <ul className="nl-q-chips" aria-label={t('colStage')}>
        {Object.entries(b.stages).map(([k, n]) => <li key={k}><Tag tone={k === 'live' ? 'accent' : 'neutral'}>{`${t(`s_${k}` as PilotKey)} · ${n}`}</Tag></li>)}
      </ul>
      <div className="nl-q-actions">
        {can('onboard') ? <Button onClick={() => setInviting(true)}>{t('invite')}</Button> : null}
        <a className="btn btn-secondary" href={exportUrl(search.market)} download>{t('exportCsv')}</a>
      </div>
      {notice ? <p role="status" className="nl-q-note">{notice}</p> : null}
      <DataTable<TableRow> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} rowTones={tones}
        can={{ create: false, update: false, delete: false }} roleName={roleName} emptyText={t('empty')} openLabel={t('open')} onOpen={r => go({ pilot: r.id })} />
      {search.pilot ? <PilotDrawer id={search.pilot} canAct={can('onboard')} onClose={() => go({ pilot: undefined })} onNotice={setNotice} /> : null}
      {inviting ? <InviteDialog markets={b.markets} market={search.market} onClose={() => setInviting(false)}
        onSent={(link, id) => { setInviting(false); setNotice(t('inviteSent', { link })); go({ pilot: id }); }} /> : null}
    </div>
  );
}

function PilotDrawer({ id, canAct, onClose, onNotice }: { id: string; canAct: boolean; onClose: () => void; onNotice: (s: string) => void }) {
  const t = usePilotT();
  const { date } = useFormatters();
  const detail = useQuery(detailQuery(id));
  const me = useQuery(meQuery).data;
  const owner = useOwner();
  const blocker = useBlocker();
  const note = useNote();
  const reinvite = useReinvite();
  const [text, setText] = useState('');
  const [who, setWho] = useState<string>('business');
  const [body, setBody] = useState('');
  const failure = [owner, blocker, note, reinvite].map(m => m.error).find(Boolean);
  const errorText = failure instanceof ApiError ? failure.message : failure ? String(failure) : undefined;
  const d = detail.data;
  const when = (iso: string) => date(iso, 'dateTime');
  return (
    <Drawer open onClose={onClose} title={d ? d.row.businessName : '…'} width={600}>
      {detail.isPending ? <PageSkeleton kpis={0} rows={5} />
        : detail.isError || !d ? <ErrorState message={t('loadError')} onRetry={() => void detail.refetch()} />
          : <>
            <p>{t(`t_${d.row.businessType}` as PilotKey)}{d.row.city ? ` · ${d.row.city}` : ''} · <Tag tone={d.row.stage === 'live' ? 'accent' : 'neutral'}>{t(`s_${d.row.stage}` as PilotKey)}</Tag></p>
            <h3 className="nl-q-h">{t('checklist')}</h3>
            <ol className="nl-q-list">
              {d.row.checklist.map(s => (
                <li key={s.key} className="nl-q-row">
                  <span>{t(`s_${s.key}` as PilotKey)}{s.action && s.state !== 'done' ? <><br /><span className="nl-q-note">{actionText(s, t, when)}{s.owner ? ` · ${t(`o_${s.owner}` as PilotKey)}` : ''}</span></> : null}</span>
                  <Tag tone={s.state === 'done' ? 'accent' : s.state === 'blocked' ? 'accent-2' : 'neutral'}>{t(`state_${s.state}` as PilotKey)}</Tag>
                </li>
              ))}
            </ol>

            <h3 className="nl-q-h">{t('ownerTitle')}</h3>
            <p>{d.row.ownerName ?? t('nobody')}</p>
            {canAct ? <div className="nl-q-actions">
              {me ? <Button variant="secondary" disabled={owner.isPending} onClick={() => owner.mutate({ id, ownerId: me.userId })}>{t('assignMe')}</Button> : null}
              {d.row.ownerId ? <Button variant="ghost" disabled={owner.isPending} onClick={() => owner.mutate({ id })}>{t('unassign')}</Button> : null}
            </div> : null}

            <h3 className="nl-q-h">{t('blockerTitle')}</h3>
            {d.row.blocker ? <p>{d.row.blocker} · {t(`o_${d.row.blockerOwner ?? 'northline'}` as PilotKey)}{d.row.blockerSince ? ` · ${t('blockerSince', { date: date(d.row.blockerSince, 'long') })}` : ''}</p> : <p className="nl-q-note">{t('nobody')}</p>}
            {canAct ? <>
              <Field label={t('blockerText')}><TextInput value={text} maxLength={300} onChange={e => setText(e.target.value)} /></Field>
              <Field label={t('blockerOwner')}><Select value={who} onChange={e => setWho(e.target.value)} options={BLOCKER_OWNERS.map(o => ({ value: o, label: t(`o_${o}` as PilotKey) }))} /></Field>
              <div className="nl-q-actions">
                <Button variant="secondary" disabled={blocker.isPending || !text.trim()} onClick={() => blocker.mutate({ id, text: text.trim(), owner: who }, { onSuccess: () => setText('') })}>{t('saveBlocker')}</Button>
                {d.row.blocker ? <Button variant="ghost" disabled={blocker.isPending} onClick={() => blocker.mutate({ id })}>{t('clearBlocker')}</Button> : null}
              </div>
            </> : null}

            {d.row.businessType === 'kitchen' && d.row.merchantId ? <Visits d={d} canAct={canAct} /> : null}

            <h3 className="nl-q-h">{t('notesTitle')}</h3>
            {d.notes.length ? <ul className="nl-q-list">{d.notes.map(n => <li key={n.id}><strong>{n.authorName ?? n.authorId}</strong> · {date(n.createdAt, 'dateTime')}<br /><span>{n.body}</span></li>)}</ul> : <p className="nl-q-note">{t('noNotes')}</p>}
            {canAct ? <>
              <Field label={t('noteLabel')}><TextArea value={body} maxLength={2000} onChange={e => setBody(e.target.value)} /></Field>
              <div className="nl-q-actions"><Button variant="secondary" disabled={note.isPending || !body.trim()} onClick={() => note.mutate({ id, body }, { onSuccess: () => setBody('') })}>{t('addNote')}</Button></div>
            </> : null}

            {d.invites.length ? <>
              <h3 className="nl-q-h">{t('invitesTitle')}</h3>
              <ul className="nl-q-list">{d.invites.map(i => <li key={i.id}>{t('inviteLine', { email: i.email, date: date(i.createdAt, 'long'), state: t(`i_${i.state}` as PilotKey) })}</li>)}</ul>
              {canAct && !d.row.merchantId ? <div className="nl-q-actions"><Button variant="secondary" disabled={reinvite.isPending}
                onClick={() => reinvite.mutate({ id }, { onSuccess: r => onNotice(t('linkSent', { link: r.link })) })}>{t('resend')}</Button></div> : null}
            </> : null}
            {errorText ? <p role="alert" className="nl-q-error">{errorText}</p> : null}
            <div className="nl-q-actions"><Button variant="ghost" onClick={onClose}>{t('closePanel')}</Button></div>
          </>}
    </Drawer>
  );
}

function Visits({ d, canAct }: { d: Detail; canAct: boolean }) {
  const t = usePilotT();
  const { date } = useFormatters();
  const schedule = useScheduleVisit();
  const photo = useVisitPhoto();
  const cancel = useCancelVisit();
  const [at, setAt] = useState('');
  const [inspector, setInspector] = useState('');
  const [recording, setRecording] = useState<Visit>();
  const id = d.row.id;
  const err = [schedule, photo, cancel].map(m => m.error).find(Boolean);
  const errors = err instanceof ValidationError ? err.byField() : {};
  const other = err && !(err instanceof ValidationError) ? (err as Error).message : undefined;
  const me = useQuery(meQuery).data;
  return (
    <section aria-labelledby="nl-pilot-visits">
      <h3 id="nl-pilot-visits" className="nl-q-h">{t('visitsTitle')}</h3>
      <p className="nl-q-note">{t(d.kitchenVisitRequired ? 'visitRequired' : 'visitOptional')}</p>
      {d.visits.length ? (
        <ul className="nl-q-list">
          {d.visits.map(v => (
            <li key={v.id}>
              {t('visitLine', { date: date(v.scheduledAt, 'dateTime'), who: v.inspectorName ?? t('nobody'), status: t(`v_${v.status}` as PilotKey) })}
              {v.note ? <><br /><span className="nl-q-note">{v.note}</span></> : null}
              {v.photoIds.length ? <><br />{v.photoIds.map((p, i) => <a key={p} href={photoUrl(id, v.id, p)} target="_blank" rel="noopener">{t('photo', { n: i + 1 })} </a>)}</> : null}
              {canAct && v.status === 'scheduled' ? <div className="nl-q-actions">
                <FileButton accept="image/jpeg,image/png" pending={photo.isPending} onFile={file => photo.mutate({ id, visitId: v.id, file })}>{t('addPhoto')}</FileButton>
                <Button variant="secondary" onClick={() => setRecording(v)}>{t('record')}</Button>
                <Button variant="ghost" disabled={cancel.isPending} onClick={() => cancel.mutate({ id, visitId: v.id })}>{t('cancelVisit')}</Button>
              </div> : null}
            </li>
          ))}
        </ul>
      ) : <p className="nl-q-note">{t('noVisits')}</p>}
      {canAct ? <>
        <Field label={t('when')} error={errors.at}><TextInput type="datetime-local" value={at} onChange={e => setAt(e.target.value)} /></Field>
        <Field label={t('inspector')} error={errors.inspectorId ?? errors.inspectorName}><TextInput value={inspector} maxLength={80} onChange={e => setInspector(e.target.value)} /></Field>
        <div className="nl-q-actions"><Button variant="secondary" disabled={schedule.isPending || !at}
          onClick={() => schedule.mutate({ id, at: new Date(at).toISOString(), inspectorName: inspector.trim() || undefined, inspectorId: inspector.trim() ? undefined : me?.userId },
            { onSuccess: () => { setAt(''); setInspector(''); } })}>{t('schedule')}</Button></div>
      </> : null}
      {other ? <p role="alert" className="nl-q-error">{other}</p> : null}
      {recording ? <OutcomeDialog pilotId={id} visit={recording} items={d.visitItems} onClose={() => setRecording(undefined)} /> : null}
    </section>
  );
}

function OutcomeDialog({ pilotId, visit, items, onClose }: { pilotId: string; visit: Visit; items: string[]; onClose: () => void }) {
  const t = usePilotT();
  const save = useVisitOutcome();
  const [marks, setMarks] = useState<Record<string, string>>(() => Object.fromEntries(items.map(i => [i, 'pass'])));
  const [outcome, setOutcome] = useState<'passed' | 'failed'>('passed');
  const [note, setNote] = useState('');
  const errors = save.error instanceof ValidationError ? save.error.byField() : {};
  const other = save.error && !(save.error instanceof ValidationError) ? save.error.message : undefined;
  return (
    <Dialog open onClose={onClose} title={t('outcomeTitle')}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={save.isPending} onClick={() => save.mutate({ id: pilotId, visitId: visit.id, outcome, checklist: marks, note: note.trim() || undefined }, { onSuccess: onClose })}>{t('save')}</Button></>}>
      {items.map(i => (
        <Field key={i} label={t(`i_${i}` as PilotKey)}>
          <Select value={marks[i] ?? 'pass'} onChange={e => setMarks(m => ({ ...m, [i]: e.target.value }))}
            options={['pass', 'fail', 'na'].map(m => ({ value: m, label: t(`m_${m}` as PilotKey) }))} />
        </Field>
      ))}
      {errors.checklist ? <p role="alert" className="nl-q-error">{errors.checklist}</p> : null}
      <Field label={t('outcome')} error={errors.outcome}>
        <Select value={outcome} onChange={e => setOutcome(e.target.value as 'passed' | 'failed')} options={[{ value: 'passed', label: t('passed') }, { value: 'failed', label: t('failed') }]} />
      </Field>
      <Field label={t('visitNote')} error={errors.note}><TextArea value={note} maxLength={1000} onChange={e => setNote(e.target.value)} /></Field>
      {other ? <p role="alert" className="nl-q-error">{other}</p> : null}
    </Dialog>
  );
}

function InviteDialog({ markets, market, onClose, onSent }: { markets: Market[]; market?: string; onClose: () => void; onSent: (link: string, id: string) => void }) {
  const t = usePilotT();
  const invite = useInvite();
  const me = useQuery(meQuery).data;
  const [marketId, setMarketId] = useState(market ?? markets.find(m => m.stage === 'pilot')?.id ?? markets[0]?.id ?? '');
  const [type, setType] = useState<string>('provider');
  const [label, setLabel] = useState('');
  const [email, setEmail] = useState('');
  const [language, setLanguage] = useState<'en' | 'fr'>('en');
  const errors = invite.error instanceof ValidationError ? invite.error.byField() : {};
  const other = invite.error && !(invite.error instanceof ValidationError) ? invite.error.message : undefined;
  return (
    <Dialog open onClose={onClose} title={t('inviteTitle')}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={invite.isPending} onClick={() => invite.mutate({ marketId, businessType: type, label, email, language, ownerId: me?.userId },
          { onSuccess: r => onSent(r.link, r.detail.row.id) })}>{t('sendInvite')}</Button></>}>
      <Field label={t('inviteMarket')} error={errors.marketId}><Select value={marketId} onChange={e => setMarketId(e.target.value)} options={markets.map(m => ({ value: m.id, label: `${m.city} · ${m.province}` }))} /></Field>
      <Field label={t('inviteType')} error={errors.businessType}><Select value={type} onChange={e => setType(e.target.value)} options={TYPES.map(x => ({ value: x, label: t(`t_${x}` as PilotKey) }))} /></Field>
      <Field label={t('inviteLabel')} error={errors.label}><TextInput value={label} maxLength={80} onChange={e => setLabel(e.target.value)} /></Field>
      <Field label={t('inviteEmail')} error={errors.email}><TextInput type="email" value={email} onChange={e => setEmail(e.target.value)} /></Field>
      <Field label={t('inviteLanguage')}><Select value={language} onChange={e => setLanguage(e.target.value as 'en' | 'fr')} options={[{ value: 'en', label: t('lang_en') }, { value: 'fr', label: t('lang_fr') }]} /></Field>
      {other ? <p role="alert" className="nl-q-error">{other}</p> : null}
    </Dialog>
  );
}
