import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ChatBubble, ChatLog, DataTable, ErrorState, Skeleton, useFormatters, type DataTableColumn } from '@northline/ui';
import { useRole } from '../shell/api';
import { useShellT } from '../shell/messages';
import { AttachmentLinks } from '../messages/AttachmentLinks';
import { Composer } from '../messages/Composer';
import { useAgo } from '../messages/time';
import { LIMITS } from '../messages/validation';
import { caseQuery, casesQuery, useReplyToCase, type CaseSummary } from './api';
import { useCaseCells, useSlaNote } from './format';
import { useHelpT } from './messages';

interface Row { id: string; code: string; title: string; meta: string; sla: string; tone: 'tag-accent-2' | 'tag-neutral' }

export interface SentCase { code: string; topic: string; urgent: boolean }

/** My cases: the design's case DataTable ("Open a case"), the confirmation banner, and the open case's conversation. */
export function HelpCases({ merchantId, caseId, sent, onSelect, onCreate }: { merchantId: string; caseId?: string; sent: SentCase | null; onSelect: (id: string) => void; onCreate: () => void }) {
  const t = useHelpT();
  const shellT = useShellT();
  const role = useRole();
  const cells = useCaseCells();
  const sla = useSlaNote();
  const q = useQuery(casesQuery(merchantId));
  const cases = q.data ?? [];
  const byId = useMemo(() => new Map(cases.map(c => [c.id, c])), [cases]);
  const rows = useMemo<Row[]>(() => cases.map(c => {
    const cell = cells(c);
    return { id: c.id, code: c.code, title: c.subject, meta: cell.status, sla: cell.next, tone: cell.tone };
  }), [cases, cells]);
  const selected = (caseId && byId.get(caseId)) || cases.find(c => c.state !== 'resolved') || cases[0];

  const columns: DataTableColumn<Row>[] = [
    { key: 'code', label: t('col_case'), editable: false },
    { key: 'title', label: t('col_subject'), required: true, primary: true },
    { key: 'meta', label: t('col_status'), type: 'tag', editable: false, filter: false },
    { key: 'sla', label: t('col_next'), editable: false },
  ];

  return (
    <>
      <div className="nl-help-table">
        <DataTable<Row>
          entity={t('case')} plural={t('cases')} columns={columns} rows={rows} rowTones={r => ({ meta: r.tone })}
          can={{ create: true, update: false, delete: false, export: true }} roleName={shellT(`role_${role}` as 'role_owner')}
          createLabel={t('createCase')} searchPlaceholder={t('searchCases')} emptyText={t('noCases')}
          loading={q.isPending} error={q.isError ? t('casesError') : null} onRetry={() => void q.refetch()}
          onOpen={r => onSelect(r.id)} onCreateClick={onCreate}
        />
      </div>
      {sent ? (
        <div className="nl-help-sent" role="status">
          <strong>{t('sent', { code: sent.code })}</strong> {t('sentBody', { topic: t(`topic_${sent.topic}` as 'topic_other'), sla: sla(sent.urgent) })}
        </div>
      ) : null}
      {selected ? <CaseConversation key={selected.id} merchantId={merchantId} summary={selected} /> : null}
    </>
  );
}

function CaseConversation({ merchantId, summary }: { merchantId: string; summary: CaseSummary }) {
  const t = useHelpT();
  const ago = useAgo();
  const { date } = useFormatters();
  const q = useQuery(caseQuery(merchantId, summary.id));
  const reply = useReplyToCase(merchantId, summary.id);
  const resolved = summary.state === 'resolved';
  return (
    <section className="nl-help-convo" aria-labelledby="nl-help-convo-title">
      <h3 id="nl-help-convo-title" className="nl-help-h3 nl-help-convo-title">{t('conversation', { code: summary.code })}</h3>
      {q.isPending ? <Skeleton height={48} width="70%" /> : q.isError ? <ErrorState message={t('caseError')} onRetry={() => void q.refetch()} /> : (
        <ChatLog label={t('conversation', { code: summary.code })} className="nl-help-chat">
          {q.data.messages.map(m => {
            const mine = m.senderRole === 'merchant';
            const pending = m.id.startsWith('pending-');
            return (
              <ChatBubble key={m.id} side={mine ? 'me' : 'them'} sender={mine ? t('you') : m.senderName ?? t('northline')} pending={pending}
                meta={pending ? t('sending') : <time dateTime={m.at} title={date(m.at, 'dateTime')}>{ago(m.at)}</time>}
                footer={<AttachmentLinks merchantId={merchantId} files={m.attachments} />}>
                {m.body || null}
              </ChatBubble>
            );
          })}
        </ChatLog>
      )}
      {resolved ? <p className="nl-help-note">{t('resolvedNote')}</p> : (
        <Composer merchantId={merchantId} label={t('replyTo', { code: summary.code })} attach="text" attachText={t('attach')}
          maxLength={LIMITS.caseBody} onSend={d => reply.mutateAsync({ body: d.body, attachments: d.attachments })} />
      )}
    </section>
  );
}
