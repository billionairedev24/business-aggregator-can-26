import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { MagnifyingGlass } from '@phosphor-icons/react';
import {
  Button, ChartCard, DataTable, Drawer, ErrorState, Field, PageSkeleton, Segmented, SheetSection, StatusTag, Tag, TextArea, useFormatters,
  type DataTableAction, type DataTableColumn, type DataTableTone,
} from '@northline/ui';
import { ApiError } from '../../lib/http';
import { useAge } from '../shell/age';
import { useGrant } from '../shell/grant';
import { aiFlagsQuery, flagContentQuery, scansQuery, useFlagAction, useFlagDecision, type AiFlag } from './api';
import { useAiT, type AiKey, type AiT } from './aiMessages';

const CATEGORIES = ['off_platform_payment', 'abuse', 'scam', 'prohibited', 'misleading', 'fake_review', 'personal_info', 'spam', 'other'] as const;
const TYPES = ['listing', 'review', 'message', 'merchant'] as const;

export const categoryText = (code: string, t: AiT) => ((CATEGORIES as readonly string[]).includes(code) ? t(`cat_${code as (typeof CATEGORIES)[number]}`) : code);
export const typeText = (type: string, t: AiT) => ((TYPES as readonly string[]).includes(type) ? t(`t_${type as (typeof TYPES)[number]}`) : type);
const codeOf = (e: unknown) => (e instanceof ApiError && e.body && typeof e.body === 'object' ? (e.body as { code?: string }).code : undefined);

/** "Waiting", "Approved", "Done: hidden" — a flag's state in the queue's words. */
export function stateText(f: Pick<AiFlag, 'state' | 'action'>, t: AiT): string {
  if (f.state === 'open') return t('st_open');
  if (f.state === 'dismissed') return t('st_dismissed');
  const key = `a_${f.action ?? 'actioned'}` as AiKey;
  const action = t(key);
  return t('st_actioned', { action: action === key ? t('a_actioned') : action });
}

interface Row { id: string; item: string; business: string; why: string; categories: string; raised: string; state: string; src: AiFlag }

/**
 * Trust & safety › AI screening (S-158, S-133's queue): what the screening flagged with the model's reasons and
 * categories. Staff open the item (audit-logged) and approve it (nothing changes), hide it (a listing is rejected, a
 * review hidden, a message replaced) or escalate it to the on-call. The model never decides.
 */
export function AiScreening() {
  const t = useAiT();
  const age = useAge();
  const { can, roleName } = useGrant();
  const [state, setState] = useState<'open' | 'all'>('open');
  const [open, setOpen] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const q = useQuery(aiFlagsQuery(state));
  const rows: Row[] = (q.data?.items ?? []).map(f => ({
    id: f.id, item: typeText(f.targetType, t), business: f.businessName ?? f.merchantId ?? '—', why: f.explanation,
    categories: f.categories.map(c => categoryText(c, t)).join(', '), raised: age(f.createdAt), state: stateText(f, t), src: f,
  }));
  const columns: DataTableColumn<Row>[] = [
    { key: 'item', label: t('c_item'), sub: 'business', subLabel: t('c_business'), primary: true, filter: 'facet' },
    { key: 'why', label: t('c_why') },
    { key: 'categories', label: t('c_categories'), filter: 'facet' },
    { key: 'raised', label: t('c_raised'), editable: false },
    { key: 'state', label: t('c_state'), type: 'tag', editable: false },
  ];
  const actions: DataTableAction<Row>[] = [{ id: 'review', label: t('review'), icon: MagnifyingGlass, inline: true, bulk: false }];
  const flag = q.data?.items.find(f => f.id === open);
  return (
    <ChartCard id="ts-ai" title={t('tabAi')}>
      <p className="bo-muted">{t('aiLede')}</p>
      <Segmented name="ts-ai-state" aria-label={t('show')} value={state} onChange={v => setState(v as 'open' | 'all')} options={[{ value: 'open', label: t('showOpen') }, { value: 'all', label: t('showAll') }]} />
      {toast ? <p role="status" className="nl-q-note">{toast}</p> : null}
      {!can('decide') ? <p className="bo-muted">{t('cannot')}</p> : null}
      {q.isPending ? <PageSkeleton kpis={0} rows={5} /> : q.isError ? <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /> : (
        <DataTable<Row> dense entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} pageSize={25} emptyText={t('empty')} roleName={roleName}
          rowTones={r => ({ state: (r.src.state === 'open' ? 'status-warning' : r.src.state === 'dismissed' ? 'status-neutral' : 'status-success') as DataTableTone })}
          can={{ create: false, update: can('decide'), delete: false }} actions={actions} onOpen={r => setOpen(r.id)}
          onAction={(_, hit) => { setOpen(hit[0]!.id); return false; }} />
      )}
      {flag ? <FlagSheet flag={flag} canDecide={can('decide')} onClose={() => setOpen(null)} onDone={text => { setOpen(null); setToast(text); }} /> : null}
    </ChartCard>
  );
}

function FlagSheet({ flag, canDecide, onClose, onDone }: { flag: AiFlag; canDecide: boolean; onClose: () => void; onDone: (text: string) => void }) {
  const t = useAiT();
  const f = useFormatters();
  const [show, setShow] = useState(false);
  const [note, setNote] = useState('');
  const content = useQuery({ ...flagContentQuery(flag.id), enabled: show && canDecide });
  const act = useFlagAction();
  const decide = useFlagDecision();
  const type = typeText(flag.targetType, t);
  const hideable = flag.targetType === 'listing' || flag.targetType === 'review' || flag.targetType === 'message';
  const error = act.error ?? decide.error;
  const done = (result: string) => onDone(t('decidedToast', { type, result }));
  const text = (n: string) => (n.trim() ? n.trim() : undefined);
  return (
    <Drawer open onClose={onClose} width={560} title={t('sheetTitle', { type, categories: flag.categories.map(c => categoryText(c, t)).join(', ') })}
      footer={flag.state === 'open' && canDecide ? (
        <div className="nl-q-actions">
          <Button variant="secondary" disabled={decide.isPending || act.isPending} title={t('approveHint')}
            onClick={() => decide.mutate({ id: flag.id, decision: 'dismissed', note: text(note) }, { onSuccess: () => done(t('st_dismissed')) })}>{t('approve')}</Button>
          {hideable ? <Button variant="destructive" disabled={decide.isPending || act.isPending} title={t(`hideHint_${flag.targetType as 'listing' | 'review' | 'message'}`)}
            onClick={() => act.mutate({ id: flag.id, action: 'hide', note: text(note) }, { onSuccess: () => done(t('a_hide')) })}>{t('hide')}</Button> : null}
          <Button variant="tertiary" disabled={decide.isPending || act.isPending}
            onClick={() => act.mutate({ id: flag.id, action: 'escalate', note: text(note) }, { onSuccess: () => done(t('a_escalate')) })}>{t('escalate')}</Button>
        </div>
      ) : undefined}>
      <SheetSection title={t('reasons')}>
        <p>{flag.explanation}</p>
        <p className="bo-muted">{flag.categories.map(c => <Tag key={c} tone="neutral">{categoryText(c, t)}</Tag>)}</p>
        {flag.evidence.model ? <p className="bo-muted">{t('model', { model: flag.evidence.model, prompt: flag.evidence.prompt ?? '—' })}</p> : null}
      </SheetSection>
      <SheetSection title={t('content')}>
        {!canDecide ? <p className="bo-muted">{t('cannot')}</p> : !show ? (
          <><p className="bo-muted">{t('contentNote')}</p><Button variant="secondary" onClick={() => setShow(true)}>{t('showContent')}</Button></>
        ) : content.isPending ? <PageSkeleton kpis={0} rows={2} /> : content.isError ? <p role="alert" className="nl-q-error">{t('contentError')}</p> : (
          <blockquote className="nl-ts-content">
            {content.data.title ? <strong>{content.data.title}</strong> : null}
            <p>{content.data.text}</p>
            <footer className="bo-muted">{content.data.context}</footer>
          </blockquote>
        )}
      </SheetSection>
      {flag.state === 'open' && canDecide ? (
        <Field label={t('note')} hint={t('noteHint')}><TextArea value={note} maxLength={500} rows={2} onChange={e => setNote(e.target.value)} /></Field>
      ) : flag.decidedAt ? <p className="bo-muted">{stateText(flag, t)} · {f.date(flag.decidedAt, 'dateTime')}</p> : null}
      {error ? <p role="alert" className="nl-q-error">{codeOf(error) === 'flag_decided' ? t('closed') : t('failed')}</p> : null}
    </Drawer>
  );
}

/**
 * Trust & safety › Weekly scan (S-158, S-133's anomaly scan): per market and week, how many businesses were looked
 * at, the market summary, whether the model or only the rules explained it, and each flagged business with its
 * signals and explanation (decided in the AI screening queue or the flag list).
 */
export function WeeklyScan() {
  const t = useAiT();
  const f = useFormatters();
  const q = useQuery(scansQuery);
  if (q.isPending) return <PageSkeleton kpis={0} rows={4} />;
  if (q.isError) return <ErrorState message={t('scanError')} onRetry={() => void q.refetch()} />;
  const signal = (code: string) => { const k = `sig_${code}` as AiKey; const s = t(k); return s === k ? code : s; };
  return (
    <>
      <p className="bo-muted">{t('scanLede')}</p>
      {q.data.items.length === 0 ? <p className="bo-muted">{t('scanEmpty')}</p> : null}
      {q.data.items.map(s => (
        <ChartCard key={s.id} id={`scan-${s.id}`} title={t('scanTitle', { market: s.market === 'unplaced' ? t('unplaced') : s.market, week: f.date(`${s.weekStart}T12:00:00Z`, 'full') })}>
          <p className="bo-muted">{t('scanCounts', { merchants: s.merchants, flags: s.flagsRaised })} · <Tag tone={s.aiExplained ? 'info' : 'neutral'}>{t(s.aiExplained ? 'explainedAi' : 'explainedRules')}</Tag></p>
          {s.summary ? <p>{s.summary}</p> : null}
          <ul className="nl-rows">
            {s.flags.map(fl => (
              <li key={fl.id}>
                <span className="nl-row-main">
                  <span><strong>{fl.businessName ?? fl.merchantId}</strong> · {t('signals', { signals: (fl.evidence.signals ?? '').split(',').filter(Boolean).map(signal).join(', ') })}</span>
                  <span className="nl-row-sub">{fl.explanation}</span>
                </span>
                <StatusTag tone={fl.state === 'open' ? 'warning' : fl.state === 'dismissed' ? 'neutral' : 'success'}>{stateText(fl, t)}</StatusTag>
              </li>
            ))}
          </ul>
        </ChartCard>
      ))}
    </>
  );
}
