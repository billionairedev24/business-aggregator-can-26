import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { Button, DataTable, Drawer, ErrorState, Field, PageSkeleton, Select, Tag, TextArea, TextInput, useFormatters, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { useGrant } from '../shell/grant';
import { SCREEN_PATH } from '../shell/screens';
import { detailQuery, exportUrl, ownersQuery, PERSONAS, queueQuery, useAct, type Detail, type Item, type State } from './api';
import { useUatT, type UatKey, type UatT } from './messages';
import '../shell/queues.css';

interface Row { id: string; reference: string; summary: string; persona: string; state: string; blocking: string; owner: string; received: string; item: Item }
export interface UatSearch { view?: 'feedback' | 'participants' | 'report'; state?: string; blocking?: 'yes' | 'no'; persona?: string; item?: string }

/**
 * The pilot feedback queue (S-121): what participants sent, newest first, with the triage state, whether it blocks
 * the launch and who owns it; the detail drawer moves it along the flow (new → triaged → accepted → fixed → verified →
 * closed, or won't fix / duplicate), sets the owner and the tracker issue, and merges duplicates. CSV of the filtered list.
 */
export function FeedbackQueue() {
  const t = useUatT();
  const { date } = useFormatters();
  const search = useSearch({ strict: false }) as UatSearch;
  const navigate = useNavigate();
  const filter = { state: search.state ?? 'open', blocking: search.blocking ? search.blocking === 'yes' : undefined, persona: search.persona };
  const queue = useQuery(queueQuery(filter));
  const { can, roleName } = useGrant();
  const go = (next: Partial<UatSearch>) => void navigate({ to: SCREEN_PATH.uat, search: { ...search, view: undefined, ...next } });

  if (queue.isPending) return <PageSkeleton kpis={0} rows={6} />;
  if (queue.isError) return <ErrorState message={t('loadError')} onRetry={() => void queue.refetch()} />;
  const rows: Row[] = queue.data.map(i => ({
    id: i.id, reference: i.reference, summary: i.summary, persona: t(`p_${i.persona}`), state: t(`s_${i.state}`),
    blocking: i.blocking == null ? t('notDecided') : i.blocking ? t('yes') : t('no'), owner: i.ownerName ?? t('unassigned'),
    received: date(i.createdAt, 'dateTime'), item: i,
  }));
  const columns: DataTableColumn<Row>[] = [
    { key: 'reference', label: t('colRef'), primary: true },
    { key: 'summary', label: t('colSummary') },
    { key: 'persona', label: t('colPersona') },
    { key: 'state', label: t('colState'), type: 'tag', editable: false },
    { key: 'blocking', label: t('colBlocking'), type: 'tag', editable: false },
    { key: 'owner', label: t('colOwner') },
    { key: 'received', label: t('colReceived') },
  ];
  const tones = (r: Row): Partial<Record<keyof Row, DataTableTone>> => ({
    state: r.item.state === 'new' ? 'tag-highlight' : r.item.state === 'accepted' || r.item.state === 'fixed' ? 'tag-accent' : 'tag-neutral',
    blocking: r.item.blocking ? 'tag-accent-2' : 'tag-neutral',
  });
  const blocking = queue.data.filter(i => i.blocking && (i.state === 'accepted' || i.state === 'fixed')).length;
  return (
    <div>
      <div className="nl-q-top">
        <span className="nl-q-kicker">{t('kicker')}</span>
        <div className="nl-q-filters">
          <Select aria-label={t('filterState')} value={filter.state} onChange={e => go({ state: e.target.value })}
            options={[{ value: 'open', label: t('open') }, { value: 'all', label: t('all') }, ...(['new', 'triaged', 'accepted', 'fixed', 'verified', 'closed', 'wont_fix', 'duplicate'] as const).map(s => ({ value: s, label: t(`s_${s}`) }))]} />
          <Select aria-label={t('filterBlocking')} value={search.blocking ?? ''} onChange={e => go({ blocking: (e.target.value || undefined) as UatSearch['blocking'] })}
            options={[{ value: '', label: `${t('filterBlocking')}: ${t('any')}` }, { value: 'yes', label: t('yes') }, { value: 'no', label: t('no') }]} />
          <Select aria-label={t('filterPersona')} value={search.persona ?? ''} onChange={e => go({ persona: e.target.value || undefined })}
            options={[{ value: '', label: `${t('filterPersona')}: ${t('any')}` }, ...PERSONAS.map(p => ({ value: p, label: t(`p_${p}`) }))]} />
        </div>
      </div>
      <h1 className="nl-q-title">{t('title', { n: queue.data.length, blocking })}</h1>
      <p className="nl-q-lede">{t('lede')}</p>
      <div className="nl-q-actions"><a className="btn btn-secondary" href={exportUrl(filter)} download>{t('exportCsv')}</a></div>
      <DataTable<Row> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} rowTones={tones}
        can={{ create: false, update: false, delete: false, export: false }} roleName={roleName} emptyText={t('empty')} pageSize={25}
        onOpen={r => go({ item: r.id })} />
      {search.item ? <ItemDrawer id={search.item} canAct={can('uat')} candidates={queue.data} t={t} onClose={() => go({ item: undefined })} /> : null}
    </div>
  );
}

function ItemDrawer({ id, canAct, candidates, t, onClose }: { id: string; canAct: boolean; candidates: Item[]; t: UatT; onClose: () => void }) {
  const { date } = useFormatters();
  const detail = useQuery(detailQuery(id));
  const owners = useQuery({ ...ownersQuery, enabled: canAct });
  const act = useAct();
  const [notice, setNotice] = useState<string>();
  const d = detail.data;
  return (
    <Drawer open onClose={onClose} width={600}
      title={d ? t('detailTitle', { reference: d.item.reference, category: t(`c_${d.item.category}` as UatKey) }) : '…'}>
      {detail.isPending ? <PageSkeleton kpis={0} rows={4} /> : detail.isError || !d ? <ErrorState message={t('loadError')} onRetry={() => void detail.refetch()} /> : (
        <div>
          <p>
            <Tag tone={d.item.blocking ? 'accent-2' : 'neutral'}>{t(`s_${d.item.state}`)}{d.item.blocking ? ` · ${t('blocks')}` : ''}</Tag>{' '}
            <Tag tone="outline">{t(`v_${d.item.severity}` as UatKey)}</Tag>
          </p>
          <p className="nl-q-note">{t('from', { participant: d.item.participant, persona: t(`p_${d.item.persona}`), date: date(d.item.createdAt, 'dateTime') })}</p>
          <p className="nl-uat-body">{d.body}</p>
          <p className="nl-q-note">{t('context', { app: d.item.app, route: d.item.route, version: d.appVersion, locale: d.locale, platform: d.platform })}</p>
          {d.item.screenshot ? <p><a href={`/api/v1/console/uat/feedback/${encodeURIComponent(d.item.id)}/screenshot`} target="_blank" rel="noreferrer">{t('openScreenshot')}</a></p> : null}
          {d.duplicateOfItem ? <p>{t('duplicateOf', { reference: d.duplicateOfItem.reference })}</p> : null}
          {d.duplicates.length ? <section className="nl-q-gap">
            <h3 className="nl-q-h2">{t('duplicatesTitle')}</h3>
            <ul className="nl-q-list">{d.duplicates.map(x => <li key={x.id}>{x.reference} · {x.summary}</li>)}</ul>
          </section> : null}
          {d.item.trackerUrl ? <p><a href={d.item.trackerUrl} target="_blank" rel="noreferrer noopener">{t('openTracker')}</a></p> : null}
          {canAct ? <Triage d={d} t={t} owners={owners.data ?? []} candidates={candidates.filter(c => c.id !== d.item.id && c.state !== 'duplicate')}
            busy={act.isPending} error={act.error?.message}
            onAct={a => act.mutate({ id: d.item.id, ...a }, { onSuccess: () => setNotice(t('done')) })} /> : <p className="nl-q-note">{t('viewOnly')}</p>}
          {notice ? <p role="status" className="nl-q-note">{notice}</p> : null}
          <section className="nl-q-gap">
            <h3 className="nl-q-h2">{t('historyTitle')}</h3>
            <ul className="nl-q-list">
              <li>{date(d.item.createdAt, 'dateTime')} · {t('received')}</li>
              {d.history.map((h, i) => <li key={i}>
                {t('historyLine', { date: date(h.at, 'dateTime'), who: h.actorName ?? h.actorId, from: h.from ? t(`s_${h.from}` as UatKey) : '—', to: t(`s_${h.to}` as UatKey) })}
                {h.blocking ? t('historyBlocking') : ''}{h.note ? ` — ${h.note}` : ''}
              </li>)}
            </ul>
          </section>
        </div>
      )}
    </Drawer>
  );
}

type Move = { act: 'moves'; to: State; blocking?: boolean; duplicateOf?: string; note?: string } | { act: 'owner'; ownerId: string | null } | { act: 'tracker'; url: string | null };

function Triage({ d, t, owners, candidates, busy, error, onAct }: {
  d: Detail; t: UatT; owners: { id: string; name: string }[]; candidates: Item[]; busy: boolean; error?: string; onAct: (m: Move) => void;
}) {
  const [to, setTo] = useState<State | ''>(d.next[0] ?? '');
  const [blocking, setBlocking] = useState<'' | 'yes' | 'no'>(d.item.blocking == null ? '' : d.item.blocking ? 'yes' : 'no');
  const [duplicateOf, setDuplicateOf] = useState('');
  const [note, setNote] = useState('');
  const [owner, setOwner] = useState(d.item.ownerId ?? '');
  const [tracker, setTracker] = useState(d.item.trackerUrl ?? '');
  return (
    <section className="nl-q-gap nl-uat-triage">
      <h3 className="nl-q-h2">{t('nextTitle')}</h3>
      {d.next.length ? <form onSubmit={e => {
        e.preventDefault();
        if (!to) return;
        onAct({ act: 'moves', to, blocking: to === 'accepted' && blocking ? blocking === 'yes' : undefined, duplicateOf: to === 'duplicate' ? duplicateOf || undefined : undefined, note: note.trim() || undefined });
      }}>
        <Field label={t('nextTitle')}>
          <Select value={to} onChange={e => setTo(e.target.value as State)} options={d.next.map(s => ({ value: s, label: t(`s_${s}`) }))} />
        </Field>
        {to === 'accepted' ? <Field label={t('blockingChoice')}>
          <Select value={blocking} onChange={e => setBlocking(e.target.value as typeof blocking)} placeholder="—"
            options={[{ value: 'yes', label: t('blocks') }, { value: 'no', label: t('doesNotBlock') }]} />
        </Field> : null}
        {to === 'duplicate' ? <Field label={t('duplicatePick')}>
          <Select value={duplicateOf} onChange={e => setDuplicateOf(e.target.value)} placeholder={t('duplicatePlaceholder')}
            options={candidates.map(c => ({ value: c.id, label: `${c.reference} · ${c.summary}` }))} />
        </Field> : null}
        <Field label={t('note')}><TextArea rows={2} value={note} maxLength={1000} onChange={e => setNote(e.target.value)} /></Field>
        <div className="nl-q-actions"><Button type="submit" disabled={busy || !to}>{t('move')}</Button></div>
      </form> : null}
      <form className="nl-q-gap" onSubmit={e => { e.preventDefault(); onAct({ act: 'owner', ownerId: owner || null }); }}>
        <Field label={t('ownerLabel')}>
          <Select value={owner} onChange={e => setOwner(e.target.value)} options={[{ value: '', label: t('unassigned') }, ...owners.map(o => ({ value: o.id, label: o.name }))]} />
        </Field>
        <div className="nl-q-actions"><Button type="submit" variant="secondary" disabled={busy}>{t('saveOwner')}</Button></div>
      </form>
      <form className="nl-q-gap" onSubmit={e => { e.preventDefault(); onAct({ act: 'tracker', url: tracker.trim() || null }); }}>
        <Field label={t('trackerLabel')}><TextInput type="url" inputMode="url" value={tracker} maxLength={500} onChange={e => setTracker(e.target.value)} /></Field>
        <div className="nl-q-actions"><Button type="submit" variant="secondary" disabled={busy}>{t('saveTracker')}</Button></div>
      </form>
      {error ? <p role="alert" className="nl-q-error">{error}</p> : null}
    </section>
  );
}
