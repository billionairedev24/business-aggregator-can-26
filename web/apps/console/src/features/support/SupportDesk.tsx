import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router';
import {
  ActionPanel, ActionPanelItem, BoHeader, Button, DataTable, Dialog, ErrorState, FactList, Field, formatMoney, formatNumber, KpiStrip, PageSkeleton, RecordHeader, RecordLayout, RecordTabs,
  Segmented, Select, StatusTag, TextArea, TextInput, UnderlineTabs, useLocale, type DataTableColumn, type DataTableTone, type StatusTone,
} from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { meQuery } from '../shell/api';
import { useAge } from '../shell/age';
import { useGrant } from '../shell/grant';
import type { PlaceFilter } from '../shell/PlaceFilters';
import { usePlace } from '../shell/scope';
import { SCREEN_PATH } from '../shell/screens';
import {
  FILTERS, attachmentUrl, ticketQuery, ticketsQuery, useDecideRefund, useDeleteMacro, useEscalate, useMacros, useReply, useRequestRefund, useSaveMacro, useTake,
  type Detail, type Macro, type Ticket, type TicketFilter,
} from './api';
import { useSupportT, type SupportKey } from './messages';
import '../shell/queues.css';
import './support.css';

export interface SupportSearch extends PlaceFilter { filter?: TicketFilter }
const STATE_VIEWS = ['all', 'urgent', 'unassigned', 'mine', 'sla_risk'] as const;
const REQUESTERS = ['all', 'providers', 'sellers', 'kitchens', 'customers'] as const;
/** E-15 colour roles: escalated is danger, new and in progress are info, waiting is warning, resolved is done. */
export const ticketTone = (tk: Ticket): StatusTone =>
  tk.escalated && tk.state !== 'resolved' ? 'danger' : tk.state === 'resolved' ? 'success' : tk.state === 'waiting' ? 'warning' : 'info';
const PRIORITY_TONE: Record<string, DataTableTone> = { urgent: 'tag-danger', priority: 'tag-warning', normal: 'tag-neutral' };

interface Row { id: string; code: string; agent: string; who: string; subj: string; pri: string; age: string; stage: string; ticket: Ticket }

/**
 * Support desk (S-83, design 03 `support`): the agents' queue over every helpdesk case with the design's KPIs and
 * chips, the requester's context, the conversation, macros in the requester's language, and the role-gated actions —
 * reply (keep open / resolve), assign, escalate to trust & safety (`support`), refund requests that finance approves
 * (`refund`, never the agent who asked) and macro editing (`macros`, support leads). The api refuses what a role can't do.
 */
export function SupportDesk() {
  const t = useSupportT();
  const search = useSearch({ strict: false }) as SupportSearch;
  const place = usePlace();
  const chip: TicketFilter = FILTERS.includes(search.filter as TicketFilter) ? search.filter! : 'all';
  const navigate = useNavigate();
  const query = useQuery(ticketsQuery(place, chip));
  const { can, roleName } = useGrant();
  const age = useAge();
  const { locale } = useLocale();
  const [managing, setManaging] = useState(false);
  const go = (next: Partial<SupportSearch>) => void navigate({ to: SCREEN_PATH.support, search: { ...search, ...next } as never });
  const header = (sub?: string) => (
    <BoHeader title={t('heading')} sub={sub} actions={<>
      {can('macros') ? <Button variant="secondary" onClick={() => setManaging(true)}>{t('manageMacros')}</Button> : null}
    </>} />
  );
  if (query.isPending) return <div className="bo-page">{header()}<PageSkeleton kpis={6} rows={6} /></div>;
  if (query.isError && !query.data) {
    return <div className="bo-page">{header()}{query.error instanceof ValidationError ? <p role="alert" className="nl-q-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}</div>;
  }
  const data = query.data!;
  const k = data.kpis;
  const pct = (v: number | null | undefined) => (v == null ? t('none') : formatNumber(v, locale, { style: 'percent', maximumFractionDigits: 0 }));
  const median = k.medianFirstReplyMinutes == null ? null : t('minutes', { n: Math.round(k.medianFirstReplyMinutes) });
  const kpis: [string, string, string][] = [
    ['open', String(k.open), t('k_open', { urgent: k.urgent })],
    ['reply', median ?? t('none'), t('k_reply')],
    ['risk', String(k.slaAtRisk), t('k_risk')],
    ['unescalated', pct(k.resolvedWithoutEscalation), t('k_unescalated')],
    ['csat', k.csat == null ? t('none') : formatNumber(k.csat, locale, { maximumFractionDigits: 1 }), t('k_csat')],
    ['french', pct(k.frenchShare), t('k_french')],
  ];
  const rows: Row[] = data.items.map(i => ({
    id: i.id, code: i.code, agent: i.agentName ?? t('unassigned'), who: i.requesterName, subj: i.subject,
    pri: t(`p_${i.priority}`), age: age(i.createdAt), stage: i.escalated && i.state !== 'resolved' ? t('s_escalated') : t(`s_${i.state}`), ticket: i,
  }));
  const tones = (r: Row): Partial<Record<keyof Row, DataTableTone>> => ({
    pri: PRIORITY_TONE[r.ticket.priority] ?? 'tag-neutral', stage: `status-${ticketTone(r.ticket)}`,
  });
  const columns: DataTableColumn<Row>[] = [
    { key: 'code', label: t('colTicket'), sub: 'agent', subLabel: t('colAgent'), primary: true },
    { key: 'who', label: t('colRequester'), sub: 'subj', subLabel: t('colSubject') },
    { key: 'pri', label: t('colPriority'), type: 'tag', editable: false },
    { key: 'age', label: t('colAge'), editable: false },
    { key: 'stage', label: t('colStatus'), type: 'tag', editable: false },
  ];
  const stateView = (STATE_VIEWS as readonly string[]).includes(chip) ? chip : 'all';
  const requester = (REQUESTERS as readonly string[]).includes(chip) ? chip : 'all';
  return (
    <div className="bo-page">
      {header(median == null ? t('summaryNoMedian', { open: k.open, urgent: k.urgent }) : t('summary', { open: k.open, urgent: k.urgent, median }))}
      <KpiStrip label={t('kpis')} items={kpis.map(([key, value, label]) => ({ key, value, label }))} />
      <DataTable<Row> dense entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} rowTones={tones} pageSize={25}
        views={<UnderlineTabs aria-label={t('caseViews')} value={stateView} onChange={v => go({ filter: v === 'all' ? undefined : v })}
          options={STATE_VIEWS.map(v => ({ value: v, label: t(`tv_${v}` as SupportKey), count: formatNumber(data.counts[v] ?? 0, locale), tone: v === 'urgent' && data.counts.urgent ? 'danger' as const : undefined }))} />}
        filters={<Segmented name="support-requester" aria-label={t('requester')} value={requester} onChange={v => go({ filter: v === 'all' ? undefined : v })}
          options={REQUESTERS.map(v => ({ value: v, label: v === 'all' ? t('rq_all') : t(`f_${v}` as SupportKey) }))} />}
        can={{ create: false, update: false, delete: false }} roleName={roleName}
        emptyText={t('empty')} openLabel={t('open')} onOpen={r => void navigate({ to: '/support/$ticketId', params: { ticketId: r.id } })} />
      {managing ? <MacrosDialog onClose={() => setManaging(false)} /> : null}
    </div>
  );
}

const str = (v: unknown) => (typeof v === 'string' && v ? v : undefined);

/**
 * One support case (S-83; E-15 S-177 as a page): the head with its status and the requester's context as key facts,
 * the conversation with the reply (macros in the requester's language), the refund requests and the context as tabs,
 * and the role-gated actions in the right-hand panel — assign, request a refund (finance approves, never the agent who
 * asked), escalate to trust & safety. The api refuses what a role can't do; its words are shown.
 */
export function TicketPage() {
  const t = useSupportT();
  const { ticketId } = useParams({ strict: false }) as { ticketId: string };
  const detail = useQuery(ticketQuery(ticketId));
  const back = <div className="bo-rec-back"><Link to="/support">{t('back')}</Link></div>;
  if (detail.isPending) return <div className="bo-page">{back}<PageSkeleton kpis={0} rows={6} /></div>;
  if (detail.isError) {
    const missing = detail.error instanceof ApiError && detail.error.status === 404;
    return <div className="bo-page">{back}{missing ? <p role="alert">{t('notFoundTicket')}</p> : <ErrorState message={t('loadError')} onRetry={() => void detail.refetch()} />}</div>;
  }
  return <TicketView d={detail.data} />;
}

function TicketView({ d }: { d: Detail }) {
  const t = useSupportT();
  const { locale } = useLocale();
  const age = useAge();
  const { can, roleName } = useGrant();
  const me = useQuery(meQuery).data?.userId;
  const macros = useMacros().data?.items ?? [];
  const reply = useReply();
  const take = useTake();
  const escalate = useEscalate();
  const decideRefund = useDecideRefund();
  const [body, setBody] = useState('');
  const [macroKey, setMacroKey] = useState<string | undefined>();
  const [asking, setAsking] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [decisionNote, setDecisionNote] = useState('');
  const tk = d.ticket;
  const ctx = d.context;
  const lang = tk.lang === 'fr' ? 'fr' : 'en';
  const resolved = tk.state === 'resolved';
  const acts = can('support') && !resolved;
  const portal = str(ctx.portal) ?? tk.requesterType;
  const portalName = t(`r_${portal}` as SupportKey);
  const refunds = Array.isArray(ctx.refunds) ? ctx.refunds.length : 0;
  const errors = reply.error instanceof ValidationError ? reply.error.byField() : {};
  const refusal = [reply.error, take.error, escalate.error, decideRefund.error].find(e => e && !(e instanceof ValidationError)) as ApiError | Error | undefined;
  const pickMacro = (key: string) => {
    const m = macros.find(x => x.key === key);
    setMacroKey(m?.key);
    if (m) setBody(m.body[lang] ?? m.body.en ?? '');
  };
  const send = (resolve: boolean) => reply.mutate({ id: tk.id, body, resolve, macroKey }, {
    onSuccess: () => { setBody(''); setMacroKey(undefined); setNotice(t(resolve ? 'resolvedToast' : 'sent')); },
  });
  const stage = tk.escalated && !resolved ? t('s_escalated') : t(`s_${tk.state}` as SupportKey);
  return (
    <div className="bo-page">
      <RecordHeader back={<Link to="/support">{t('back')}</Link>} title={`${tk.code} · ${tk.subject}`}
        status={<StatusTag tone={ticketTone(tk)} size="lg">{stage}</StatusTag>}
        facts={[
          { label: t('f_requester'), value: `${tk.requesterName}${str(ctx.tier) ? ` · ${str(ctx.tier)}` : ''}` },
          { label: t('f_portal'), value: portalName === `r_${portal}` ? portal : portalName },
          { label: t('f_agent'), value: tk.agentName ?? t('unassigned') },
          { label: t('f_priority'), value: t(`p_${tk.priority}` as SupportKey) },
          { label: t('f_age'), value: age(tk.createdAt) },
          d.refLabel ? { label: t('f_ref'), value: d.refLabel } : null,
        ]} />
      {notice ? <p role="status" className="nl-q-note">{notice}</p> : null}
      {refusal ? <p role="alert" className="nl-q-error">{refusal.message}</p> : null}
      <RecordLayout asideLabel={t('actionsPanel')} aside={
        <ActionPanel title={t('actionsPanel')} note={can('support') ? t('footNote') : t('viewOnly', { role: roleName })}>
          <ActionPanelItem label={tk.agentId && tk.agentId === me ? t('taken') : t('take')} description={t('takeDesc')} disabled={!acts || take.isPending || tk.agentId === me}
            onClick={() => take.mutate(tk.id, { onSuccess: () => setNotice(t('takenToast')) })} />
          <ActionPanelItem label={`${t('requestRefund')}…`} description={t('refundDesc')} disabled={!acts} onClick={() => setAsking(true)} />
          <ActionPanelItem label={tk.escalated ? t('escalated') : t('escalate')} description={t('escalateDesc')} disabled={!acts || tk.escalated || escalate.isPending}
            onClick={() => escalate.mutate({ id: tk.id }, { onSuccess: () => setNotice(t('escalatedToast')) })} />
        </ActionPanel>}>
        <RecordTabs label={t('caseTabs')} tabs={[
          { value: 'conversation', label: t('conversation'), count: d.notes.length, render: () => <>
            <ol className="nl-sd-thread">
              {d.notes.map((n, i) => (
                <li key={i} className={`nl-sd-note nl-sd-note-${n.by}`}>
                  <span className="nl-q-note">{n.name ?? t(`by_${n.by}` as SupportKey)} · {age(n.at)}</span>
                  {n.body ? <p>{n.body}</p> : null}
                  {n.attachments?.length ? (
                    <ul className="nl-sd-files" aria-label={t('attachments', { count: n.attachments.length })}>
                      {n.attachments.map(f => (
                        <li key={f.id}>
                          <a href={attachmentUrl(tk.id, f.id)} target="_blank" rel="noopener noreferrer">
                            {f.contentType.startsWith('image/') && f.contentType !== 'image/heic' && f.contentType !== 'image/heif'
                              ? <img src={attachmentUrl(tk.id, f.id)} alt={t('attachmentPhoto', { name: f.fileName })} loading="lazy" />
                              : f.fileName}
                          </a>
                        </li>
                      ))}
                    </ul>
                  ) : null}
                </li>
              ))}
            </ol>
            <h3 className="bo-sheet-h3">{t('replyTitle')}</h3>
            {resolved ? <p className="nl-q-note">{t('resolvedNote')}</p> : (
              <div className="nl-sd-replybox">
                <Select aria-label={t('macroPick')} placeholder={t('macroPick')} value={macroKey ?? ''} disabled={!acts}
                  options={macros.map((m: Macro) => ({ value: m.key, label: m.title[locale] ?? m.title.en ?? m.key }))}
                  onChange={e => pickMacro(e.target.value)} />
                <Field label={t('replyLabel')} hint={t('replyLang', { lang: t(`lang_${lang}`) })} error={errors.body}>
                  <TextArea className="nl-sd-reply" placeholder={t('replyPlaceholder')} value={body} maxLength={5000} disabled={!acts} onChange={e => setBody(e.target.value)} />
                </Field>
                <div className="nl-q-actions">
                  <Button disabled={!acts || reply.isPending} onClick={() => send(false)}>{t('sendOpen')}</Button>
                  <Button variant="secondary" disabled={!acts || reply.isPending} onClick={() => send(true)}>{t('sendResolve')}</Button>
                </div>
              </div>
            )}
          </> },
          { value: 'refunds', label: t('refundsTitle'), count: d.refundRequests.length, render: () => (d.refundRequests.length ? (
            <ul className="nl-q-list">
              {d.refundRequests.map(r => (
                <li key={r.id} className="nl-q-row">
                  <span>{t('rr_line', { amount: formatMoney(r.amountCents, locale), who: r.requestedByName ?? t('none'), age: age(r.requestedAt) })}
                    {r.note ? <><br /><span className="nl-q-note">{r.note}</span></> : null}</span>
                  <StatusTag tone={r.state === 'approved' ? 'success' : r.state === 'declined' ? 'neutral' : 'warning'}>{t(`rr_${r.state}` as SupportKey)}</StatusTag>
                  {r.state === 'pending' && can('refund') && r.requestedBy !== me ? (
                    <div className="nl-q-actions nl-sd-decide">
                      <TextInput aria-label={t('decisionNote')} placeholder={t('decisionNote')} value={decisionNote} onChange={e => setDecisionNote(e.target.value)} />
                      <Button disabled={decideRefund.isPending} onClick={() => decideRefund.mutate({ requestId: r.id, decision: 'approve', note: decisionNote || undefined })}>{t('approve')}</Button>
                      <Button variant="secondary" disabled={decideRefund.isPending} onClick={() => decideRefund.mutate({ requestId: r.id, decision: 'decline', note: decisionNote || undefined })}>{t('decline')}</Button>
                    </div>
                  ) : null}
                </li>
              ))}
            </ul>) : <p className="bo-muted">{t('none')}</p>) },
          { value: 'context', label: t('contextTitle'), render: () => (
            <FactList rows={[
              [t('f_portal'), t('ctxPortal', { portal: portalName === `r_${portal}` ? portal : portalName })],
              str(ctx.tier) ? [t('f_requester'), t('ctxTier', { tier: str(ctx.tier)! })] : null,
              str(ctx.role) ? [t('f_requester'), t('ctxRole', { role: str(ctx.role)! })] : null,
              str(ctx.triageSummary) ? [t('f_ref'), t('ctxTriage', { summary: str(ctx.triageSummary)! })] : null,
              refunds ? [t('refundsTitle'), t('ctxRefunds', { n: refunds })] : null,
              [t('f_agent'), t('agentLine', { code: tk.code, agent: tk.agentName ?? t('unassigned') })],
            ]} />) },
        ]} />
      </RecordLayout>
      {asking ? <RefundDialog ticket={tk} onClose={() => setAsking(false)} onSent={() => { setAsking(false); setNotice(t('refundSent')); }} /> : null}
    </div>
  );
}

function RefundDialog({ ticket, onClose, onSent }: { ticket: Ticket; onClose: () => void; onSent: () => void }) {
  const t = useSupportT();
  const request = useRequestRefund();
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');
  const errors = request.error instanceof ValidationError ? request.error.byField() : {};
  const refusal = request.error && !(request.error instanceof ValidationError) ? request.error.message : null;
  const cents = Math.round(Number(amount.replace(',', '.').replace(/[^0-9.]/g, '')) * 100);
  return (
    <Dialog open onClose={onClose} title={t('refundDialog', { code: ticket.code })}
      actions={<>
        <Button variant="secondary" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={request.isPending} onClick={() => request.mutate({ id: ticket.id, amountCents: Number.isFinite(cents) ? cents : 0, note: note || undefined }, { onSuccess: onSent })}>{t('send')}</Button>
      </>}>
      <Field label={t('amount')} error={errors.amountCents}>
        <TextInput inputMode="decimal" value={amount} onChange={e => setAmount(e.target.value)} />
      </Field>
      <Field label={t('refundNote')} error={errors.note}>
        <TextArea value={note} maxLength={1000} onChange={e => setNote(e.target.value)} />
      </Field>
      {refusal ? <p role="alert" className="nl-q-error">{refusal}</p> : null}
    </Dialog>
  );
}

const EMPTY = { key: '', titleEn: '', titleFr: '', bodyEn: '', bodyFr: '' };

function MacrosDialog({ onClose }: { onClose: () => void }) {
  const t = useSupportT();
  const { locale } = useLocale();
  const macros = useMacros().data?.items ?? [];
  const save = useSaveMacro();
  const remove = useDeleteMacro();
  const [editing, setEditing] = useState<{ id?: string } & typeof EMPTY | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const errors = save.error instanceof ValidationError ? save.error.byField() : {};
  const refusal = [save.error, remove.error].find(e => e && !(e instanceof ValidationError));
  const edit = (m?: Macro) => {
    save.reset();
    setEditing(m ? { id: m.id, key: m.key, titleEn: m.title.en ?? '', titleFr: m.title.fr ?? '', bodyEn: m.body.en ?? '', bodyFr: m.body.fr ?? '' } : { ...EMPTY });
  };
  const set = (k: keyof typeof EMPTY) => (e: { target: { value: string } }) => setEditing(v => (v ? { ...v, [k]: e.target.value } : v));
  return (
    <Dialog open onClose={onClose} title={t('macrosTitle')} width={640}
      actions={<Button variant="secondary" onClick={onClose}>{t('close')}</Button>}>
      <p className="nl-q-note">{t('macrosNote')}</p>
      <ul className="nl-q-list">
        {macros.map(m => (
          <li key={m.id} className="nl-q-row">
            <span>{m.title[locale] ?? m.key}<br /><span className="nl-q-note">{m.key}</span></span>
            <span className="nl-q-actions">
              <Button variant="secondary" onClick={() => edit(m)}>{t('editMacro')}</Button>
              <Button variant="ghost" disabled={remove.isPending} onClick={() => remove.mutate(m.id, { onSuccess: () => setNotice(t('macroDeleted')) })}>{t('deleteMacro')}</Button>
            </span>
          </li>
        ))}
      </ul>
      {editing ? (
        <div className="nl-sd-macro-form">
          <Field label={t('macroKey')} error={errors.key}><TextInput value={editing.key} onChange={set('key')} /></Field>
          <Field label={t('titleEn')} error={errors.title}><TextInput value={editing.titleEn} onChange={set('titleEn')} /></Field>
          <Field label={t('titleFr')}><TextInput value={editing.titleFr} onChange={set('titleFr')} /></Field>
          <Field label={t('bodyEn')} error={errors.body}><TextArea value={editing.bodyEn} onChange={set('bodyEn')} /></Field>
          <Field label={t('bodyFr')}><TextArea value={editing.bodyFr} onChange={set('bodyFr')} /></Field>
          <div className="nl-q-actions">
            <Button disabled={save.isPending} onClick={() => save.mutate(
              { id: editing.id, key: editing.key, title: { en: editing.titleEn, fr: editing.titleFr }, body: { en: editing.bodyEn, fr: editing.bodyFr } },
              { onSuccess: () => { setEditing(null); setNotice(t('macroSaved')); } },
            )}>{t('saveMacro')}</Button>
            <Button variant="secondary" onClick={() => setEditing(null)}>{t('cancel')}</Button>
          </div>
        </div>
      ) : <div className="nl-q-gap"><Button onClick={() => edit()}>{t('newMacro')}</Button></div>}
      {notice ? <p role="status" className="nl-q-note">{notice}</p> : null}
      {refusal ? <p role="alert" className="nl-q-error">{refusal.message}</p> : null}
    </Dialog>
  );
}
