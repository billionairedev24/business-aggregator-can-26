import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { BoHeader, ChatBubble, ChatLog, EmptyState, ErrorState, Skeleton, StatusTag, UnderlineTabs, useFormatters, useLocale } from '@northline/ui';
import { useMerchantId, useRole } from '../shell/api';
import { useShellT } from '../shell/messages';
import { screenHref, type ScreenKey } from '../shell/nav';
import { useLivePoll } from '../../lib/live';
import { THREAD_POLL_MS, THREADS_POLL_MS, threadQuery, threadsQuery, useMarkRead, useSendMessage, type ThreadSummary } from './api';
import { AttachmentLinks } from './AttachmentLinks';
import { Composer } from './Composer';
import { useMessagesT } from './messages';
import { useAgo } from './time';
import './messages.css';
import { brand } from '@northline/tokens';

/** The api's name of the platform's support thread (its brand name + " support"), shown under the localized label. */
const SUPPORT_NAME = `${brand.name} support`;

const CAN_SEND = new Set(['owner', 'technician', 'cook', 'manager', 'staff']);
const REF_SCREEN: Record<string, ScreenKey> = { booking: 'appointments', quote: 'appointments', order: 'orders', dispute: 'refunds' };

/**
 * /b/$merchantId/messages (S-176, on the Studio dashboard's language): the page head with the unread count, then one
 * panel — the conversations (All / Unread tabs; an unread one is bold with an ink dot and "Unread" for screen readers,
 * the open one on the grey tint with the ink bar, as the sidebar's current page) beside the open conversation with its
 * booking/order context, quick replies and the composer. Technicians see only their own jobs' threads.
 * Refreshed by the live stream (S-68), polling while it is down; `threadId` comes from `?thread=`.
 */
export function MessagesScreen({ threadId, onSelect }: { threadId?: string; onSelect: (id: string) => void }) {
  const t = useMessagesT();
  const merchantId = useMerchantId();
  const role = useRole();
  const list = useQuery({ ...threadsQuery(merchantId), refetchInterval: useLivePoll(THREADS_POLL_MS) });
  const ago = useAgo();
  const f = useFormatters();
  const [filter, setFilter] = useState<'all' | 'unread'>('all');
  const threads = list.data ?? [];
  const unread = threads.filter(x => x.unread).length;
  const shown = filter === 'unread' ? threads.filter(x => x.unread || x.id === threadId) : threads;
  const selectedId = threadId && threads.some(x => x.id === threadId) ? threadId : shown[0]?.id ?? threads[0]?.id;
  const nameOf = (th: ThreadSummary) => (th.kind === 'support' && th.counterpartName === SUPPORT_NAME ? t('northline') : th.counterpartName);

  return (
    <div className="nl-msg-page">
      <BoHeader title={t('kicker')} sub={list.data ? (unread ? t('unreadSub', { count: unread }) : t('allRead')) : undefined} />
      {list.isPending ? <ListSkeleton /> : list.isError ? (
        <ErrorState message={t('loadError')} onRetry={() => void list.refetch()} />
      ) : threads.length === 0 ? (
        <EmptyState>{role === 'bookkeeper' ? t('emptyBookkeeper') : role === 'technician' ? t('emptyTechnician') : t('empty')}</EmptyState>
      ) : (
        <div className="nl-msg-grid">
          <div className="nl-msg-side">
            <UnderlineTabs aria-label={t('show')} value={filter} onChange={setFilter} fill
              options={[{ value: 'all', label: t('tabAll'), count: f.number(threads.length) }, { value: 'unread', label: t('unread'), count: f.number(unread) }]} />
            <nav className="nl-msg-list" aria-label={t('conversations')}>
              {shown.length === 0 ? <p className="nl-msg-note nl-msg-none">{t('noUnread')}</p> : null}
              {shown.map(th => (
                <button key={th.id} type="button" className="nl-msg-item" aria-current={th.id === selectedId ? 'true' : undefined} data-unread={th.unread || undefined} onClick={() => onSelect(th.id)}>
                  <span className="nl-msg-avatar" data-support={th.kind === 'support' || undefined} aria-hidden>{initials(nameOf(th))}</span>
                  <span className="nl-msg-item-text">
                    <span className="nl-msg-item-head">
                      <strong className="nl-msg-name">{nameOf(th)}</strong>
                      <span className="nl-msg-when">{ago(th.lastMessageAt)}</span>
                    </span>
                    {th.subject || th.refCode ? <span className="nl-msg-subject">{[th.refCode, th.subject].filter(Boolean).join(' · ')}</span> : null}
                    <span className="nl-msg-last-row">
                      <span className="nl-msg-last">{th.lastMessage === '' ? t('attachmentOnly') : th.lastMessage ?? ''}</span>
                      {th.unread ? <span className="nl-msg-dot" role="img" aria-label={t('unread')} /> : null}
                    </span>
                  </span>
                </button>
              ))}
            </nav>
          </div>
          {selectedId ? <ThreadPane key={selectedId} merchantId={merchantId} summary={threads.find(x => x.id === selectedId)!} canSend={CAN_SEND.has(role)} role={role} /> : <EmptyState>{t('choose')}</EmptyState>}
        </div>
      )}
    </div>
  );
}

function ThreadPane({ merchantId, summary, canSend, role }: { merchantId: string; summary: ThreadSummary; canSend: boolean; role: string }) {
  const t = useMessagesT();
  const shellT = useShellT();
  const { locale } = useLocale();
  const { date } = useFormatters();
  const navigate = useNavigate();
  const q = useQuery({ ...threadQuery(merchantId, summary.id, locale), refetchInterval: useLivePoll(THREAD_POLL_MS) });
  const markRead = useMarkRead(merchantId);
  const send = useSendMessage(merchantId, summary.id, locale);
  const ago = useAgo();

  // Opening a thread with unread customer messages marks it read for the team (once per open).
  useEffect(() => { if (summary.unread) markRead.mutate(summary.id); }, [summary.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const name = summary.kind === 'support' && summary.counterpartName === SUPPORT_NAME ? t('northline') : summary.counterpartName;
  const title = summary.subject ? t('title', { name, subject: summary.subject }) : name;
  const refScreen = summary.refType ? REF_SCREEN[summary.refType] : undefined;
  const refHref = refScreen ? screenHref(merchantId, refScreen) : undefined;
  const messages = useMemo(() => q.data?.messages ?? [], [q.data]);

  return (
    <section className="nl-msg-thread" aria-labelledby="nl-msg-title">
      <div className="nl-msg-thread-head">
        <h2 id="nl-msg-title" className="nl-msg-title">{title}</h2>
        <div className="nl-msg-note">{t('masked')}</div>
        {summary.refCode && summary.refType ? (
          <div className="nl-msg-ref">
            <StatusTag tone="neutral">{t(`ref_${summary.refType}` as 'ref_booking', { code: summary.refCode })}</StatusTag>
            {refHref ? <a href={refHref} onClick={e => { e.preventDefault(); void navigate({ to: refHref }); }}>{t(`open_${summary.refType}` as 'open_booking')} →</a> : null}
          </div>
        ) : null}
      </div>
      <div className="nl-msg-body">
        {q.isPending ? (
          <div aria-busy="true"><span className="nl-sr-only">{t('loading')}</span><Skeleton height={48} width="60%" /><Skeleton height={48} width="50%" className="nl-msg-skel-me" /></div>
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

/** "Amara Osei" → "AO" (the list's round avatar; decorative). */
const initials = (name: string) => name.split(/\s+/).filter(Boolean).slice(0, 2).map(w => w.charAt(0).toUpperCase()).join('');

function ListSkeleton() {
  return (
    <div className="nl-msg-grid" aria-busy="true">
      <div className="nl-msg-list nl-msg-skel">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} height={56} />)}</div>
      <div className="nl-msg-skel nl-msg-skel-pane"><Skeleton width={280} height={28} /><Skeleton width="70%" height={14} /><Skeleton height={48} width="60%" /></div>
    </div>
  );
}
