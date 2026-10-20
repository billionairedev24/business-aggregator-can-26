import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ValidationError } from '@northline/client';
import { ChatBubble, ChatLog, ErrorState, Field, Skeleton, TextArea, useFormatters } from '@northline/ui';
import './messages.css';
import { markRead, openThread, threadQuery, useCustomerLive, useSendMessage, type ThreadRef } from './api';
import { useMessagesT } from './messages';

const MAX = 2000;

/**
 * One conversation with a provider (S-153): opened on demand for a quote request ("Ask a question") or a booking —
 * the same thread the provider answers in the Studio's Messages. New messages arrive over the customer's live stream
 * (polling while it is down). The api masks phone numbers and emails and flags off-platform payment; a flagged
 * message shows the nudge under it, as in the Studio.
 */
export function ThreadPanel({ thread, name, lede }: { thread: ThreadRef | { threadId: string }; name: string; lede?: string }) {
  const t = useMessagesT();
  const [threadId, setThreadId] = useState<string | undefined>('threadId' in thread ? thread.threadId : undefined);
  const [openError, setOpenError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const key = 'threadId' in thread ? thread.threadId : `${thread.refType}:${thread.refId}:${'merchantId' in thread ? thread.merchantId : ''}`;
  useEffect(() => {
    if ('threadId' in thread) { setThreadId(thread.threadId); return; }
    let live = true;
    setOpenError(false);
    openThread(thread).then(p => { if (live) setThreadId(p.thread.id); }, () => { if (live) setOpenError(true); });
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `key` identifies the thread asked for
  }, [key, attempt]);
  useCustomerLive(!!threadId);
  if (openError) return <ErrorState message={t('openError')} onRetry={() => setAttempt(a => a + 1)} />;
  if (!threadId) return <Skeleton height={120} />;
  return <Conversation threadId={threadId} name={name} lede={lede} />;
}

function Conversation({ threadId, name, lede }: { threadId: string; name: string; lede?: string }) {
  const t = useMessagesT();
  const { date } = useFormatters();
  const q = useQuery(threadQuery(threadId));
  const send = useSendMessage(threadId);
  const [text, setText] = useState('');
  const [error, setError] = useState<string>();
  const unread = q.data?.thread.unread;
  useEffect(() => { if (unread) void markRead(threadId).catch(() => undefined); }, [threadId, unread]);
  if (q.isPending) return <Skeleton height={120} />;
  if (q.isError) return <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} />;
  const submit = async () => {
    const body = text.trim();
    if (!body) { setError(t('required')); return; }
    if (body.length > MAX) { setError(t('tooLong')); return; }
    setError(undefined);
    try { await send.mutateAsync(body); setText(''); } catch (e) {
      setError(e instanceof ValidationError ? (e.byField().body ?? e.errors[0]?.message) : t('sendError'));
    }
  };
  return (
    <div className="nl-msg-thread">
      {lede ? <p className="nl-msg-muted">{lede}</p> : null}
      {q.data.messages.length === 0 ? <p className="nl-msg-muted">{t('noMessages')}</p> : (
        <ChatLog label={t('threadTitle', { name })}>
          {q.data.messages.map(m => (
            <ChatBubble key={m.id} side={m.from === 'me' ? 'me' : 'them'} sender={m.from === 'me' ? t('me') : m.from === 'business' ? name : t('northline')}
              meta={date(m.at, 'dateTime')} footer={m.flagged ? <p className="nl-msg-flag">{t('flagged')}</p> : undefined}>
              {m.body}
            </ChatBubble>
          ))}
        </ChatLog>
      )}
      <form className="nl-msg-composer" onSubmit={e => { e.preventDefault(); void submit(); }}>
        <Field label={t('composer')} hint={t('masked')} error={error}>
          <TextArea rows={3} value={text} maxLength={MAX + 100} onChange={e => setText(e.target.value)} />
        </Field>
        <button type="submit" className="btn btn-primary" disabled={send.isPending} aria-busy={send.isPending}>{send.isPending ? t('sending') : t('send')}</button>
      </form>
    </div>
  );
}
