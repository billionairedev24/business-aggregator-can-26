import { useEffect, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { ChatBubble, ChatLog, EmptyState, ErrorState, Skeleton, useFormatters, useLocale } from '@northline/ui';
import { useMerchantId, useRole } from '../shell/api';
import { useShellT } from '../shell/messages';
import { screenHref, type ScreenKey } from '../shell/nav';
import { threadQuery, threadsQuery, useMarkRead, useSendMessage, type ThreadSummary } from './api';
import { AttachmentLinks } from './AttachmentLinks';
import { Composer } from './Composer';
import { useMessagesT } from './messages';
import { useAgo } from './time';
import './messages.css';

const CAN_SEND = new Set(['owner', 'technician', 'cook', 'manager', 'staff']);
const REF_SCREEN: Record<string, ScreenKey> = { booking: 'appointments', quote: 'appointments', order: 'orders', dispute: 'refunds' };

/**
 * /b/$merchantId/messages — design: messages. Thread list (the open one outlined like a panel), the open thread with
 * its booking/order context, quick replies and the composer. Technicians see only their own jobs' threads.
 * Polls (TanStack Query refetchInterval); `threadId` comes from `?thread=`.
 */
export function MessagesScreen({ threadId, onSelect }: { threadId?: string; onSelect: (id: string) => void }) {
  const t = useMessagesT();
  const merchantId = useMerchantId();
  const role = useRole();
  const list = useQuery(threadsQuery(merchantId));
  const ago = useAgo();
  const threads = list.data ?? [];
  const selectedId = threadId && threads.some(x => x.id === threadId) ? threadId : threads[0]?.id;

  return (
    <>
      <span className="nl-kicker">{t('kicker')}</span>
      {!selectedId ? <h1 className="nl-sr-only">{t('kicker')}</h1> : null}
      {list.isPending ? <ListSkeleton /> : list.isError ? (
        <ErrorState message={t('loadError')} onRetry={() => void list.refetch()} />
      ) : threads.length === 0 ? (
        <EmptyState>{role === 'bookkeeper' ? t('emptyBookkeeper') : role === 'technician' ? t('emptyTechnician') : t('empty')}</EmptyState>
      ) : (
        <div className="nl-msg-grid">
          <nav className="nl-msg-list" aria-label={t('conversations')}>
            {threads.map(th => (
              <button key={th.id} type="button" className="nl-msg-item" aria-current={th.id === selectedId ? 'true' : undefined} data-unread={th.unread || undefined} onClick={() => onSelect(th.id)}>
                <span className="nl-msg-item-head">
                  <strong>{th.kind === 'support' && th.counterpartName === 'Northline support' ? t('northline') : th.counterpartName}{th.unread ? <span className="nl-msg-dot" aria-label={t('unread')} role="img" /> : null}</strong>
                  <span className="nl-msg-when">{ago(th.lastMessageAt)}</span>
                </span>
                <span className="nl-msg-last">{th.lastMessage === '' ? t('attachmentOnly') : th.lastMessage ?? ''}</span>
              </button>
            ))}
          </nav>
          {selectedId ? <ThreadPane key={selectedId} merchantId={merchantId} summary={threads.find(x => x.id === selectedId)!} canSend={CAN_SEND.has(role)} role={role} /> : <EmptyState>{t('choose')}</EmptyState>}
        </div>
      )}
    </>
  );
}

function ThreadPane({ merchantId, summary, canSend, role }: { merchantId: string; summary: ThreadSummary; canSend: boolean; role: string }) {
  const t = useMessagesT();
  const shellT = useShellT();
  const { locale } = useLocale();
  const { date } = useFormatters();
  const navigate = useNavigate();
  const q = useQuery(threadQuery(merchantId, summary.id, locale));
  const markRead = useMarkRead(merchantId);
  const send = useSendMessage(merchantId, summary.id, locale);
  const ago = useAgo();

  // Opening a thread with unread customer messages marks it read for the team (once per open).
  useEffect(() => { if (summary.unread) markRead.mutate(summary.id); }, [summary.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const name = summary.kind === 'support' && summary.counterpartName === 'Northline support' ? t('northline') : summary.counterpartName;
  const title = summary.subject ? t('title', { name, subject: summary.subject }) : name;
  const refScreen = summary.refType ? REF_SCREEN[summary.refType] : undefined;
  const refHref = refScreen ? screenHref(merchantId, refScreen) : undefined;
  const messages = useMemo(() => q.data?.messages ?? [], [q.data]);

  return (
    <section className="nl-msg-thread" aria-labelledby="nl-msg-title">
      <h1 id="nl-msg-title" className="nl-msg-title">{title}</h1>
      <div className="nl-msg-note">{t('masked')}</div>
      {summary.refCode && summary.refType ? (
        <div className="nl-msg-ref">
          <span className="tag tag-neutral">{t(`ref_${summary.refType}` as 'ref_booking', { code: summary.refCode })}</span>
          {refHref ? <a href={refHref} onClick={e => { e.preventDefault(); void navigate({ to: refHref }); }}>{t(`open_${summary.refType}` as 'open_booking')} →</a> : null}
        </div>
      ) : null}
      <div className="nl-msg-body">
        {q.isPending ? (
          <div aria-busy="true"><span className="nl-sr-only">{t('loading')}</span><Skeleton height={48} width="60%" /><Skeleton height={48} width="50%" style={{ marginTop: 12, marginLeft: 'auto' }} /></div>
        ) : q.isError ? (
          <ErrorState message={t('threadError')} onRetry={() => void q.refetch()} />
        ) : (
          <ChatLog label={title}>
            {messages.length === 0 ? <p className="nl-msg-note">{t('noMessages')}</p> : null}
            {messages.map(m => {
              const mine = m.senderRole === 'merchant';
              const pending = m.id.startsWith('pending-');
              return (
                <ChatBubble key={m.id} side={mine ? 'me' : 'them'} sender={mine ? t('you') : m.senderName ?? name} pending={pending}
                  meta={pending ? t('sending') : <time dateTime={m.at} title={date(m.at, 'dateTime')}>{m.senderName && !mine ? `${m.senderName} · ` : ''}{ago(m.at)}</time>}
                  footer={<>
                    <AttachmentLinks merchantId={merchantId} files={m.attachments} />
                    {m.flagged && mine ? <div className="nl-msg-flag" role="note">{t('flagged')}</div> : null}
                  </>}>
                  {m.body || null}
                </ChatBubble>
              );
            })}
          </ChatLog>
        )}
        {canSend ? (
          <Composer merchantId={merchantId} label={t('replyLabel', { name })} quickReplies={q.data?.quickReplies ?? []}
            replySuggestionsFor={summary.id}
            onSend={draft => send.mutateAsync(draft)} />
        ) : (
          <p className="nl-msg-note">{t('viewOnly', { role: shellT(`role_${role}` as 'role_owner') })}</p>
        )}
      </div>
    </section>
  );
}

function ListSkeleton() {
  return (
    <div className="nl-msg-grid" aria-busy="true">
      <div className="nl-msg-list">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} height={56} style={{ marginBottom: 6 }} />)}</div>
      <div><Skeleton width={280} height={28} /><Skeleton width="70%" height={14} style={{ marginTop: 10 }} /><Skeleton height={48} width="60%" style={{ marginTop: 20 }} /></div>
    </div>
  );
}
