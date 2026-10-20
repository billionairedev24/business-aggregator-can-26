import { useEffect } from 'react';
import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';

/**
 * The customer's messages with providers (S-153, module `hire` over the Studio's own customer threads):
 *   GET  /api/v1/me/threads                       — every thread, most recent first
 *   POST /api/v1/me/threads {refType, refId, merchantId?} — open (or find) the thread of a quote request or a booking
 *   GET  /api/v1/me/threads/{id}                  — one thread with its messages
 *   POST /api/v1/me/threads/{id}/messages {body}  — write (masked and checked for off-platform payment by the api)
 *   POST /api/v1/me/threads/{id}/read
 *   GET  /api/v1/me/live                          — server-sent `message` events ({"ref": threadId}) when the business writes
 * Personal: loaded in the browser only.
 */
export const ThreadRow = z.object({
  id: z.string(), refType: z.enum(['quote', 'booking']).catch('booking'), refId: z.string(), refCode: z.string().nullish(),
  subject: z.string().nullish(), businessName: z.string(), businessSlug: z.string(), href: z.string(),
  lastMessageAt: z.string().nullish(), lastMessage: z.string().nullish(), unread: z.boolean(),
});
export type ThreadRow = z.infer<typeof ThreadRow>;
export const Message = z.object({ id: z.string(), from: z.enum(['me', 'business', 'northline']).catch('northline'), body: z.string(), at: z.string(), flagged: z.boolean() });
export type Message = z.infer<typeof Message>;
export const ThreadPage = z.object({ thread: ThreadRow, messages: z.array(Message) });
export type ThreadPage = z.infer<typeof ThreadPage>;
const Threads = z.object({ items: z.array(ThreadRow) });

/** While the live stream is down the open thread is asked again every 10 s, the list every 30 s. */
export const threadsQuery = queryOptions({
  queryKey: ['me', 'threads'],
  queryFn: () => http('/api/v1/me/threads', {}, Threads).then(r => r.items),
  refetchInterval: 30_000,
});
export const threadQuery = (threadId: string) => queryOptions({
  queryKey: ['me', 'threads', threadId],
  queryFn: () => http(`/api/v1/me/threads/${encodeURIComponent(threadId)}`, {}, ThreadPage),
  refetchInterval: 10_000,
});

export type ThreadRef = { refType: 'quote'; refId: string; merchantId: string } | { refType: 'booking'; refId: string };
export const openThread = (ref: ThreadRef) => http('/api/v1/me/threads', { method: 'POST', body: ref }, ThreadPage);
export const markRead = (threadId: string) => http(`/api/v1/me/threads/${encodeURIComponent(threadId)}/read`, { method: 'POST' });

export function useSendMessage(threadId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: string) => http(`/api/v1/me/threads/${encodeURIComponent(threadId)}/messages`, { method: 'POST', body: { body } }, Message),
    onSuccess: m => {
      qc.setQueryData(threadQuery(threadId).queryKey, (old: ThreadPage | undefined) => old ? { ...old, messages: [...old.messages, m] } : old);
      void qc.invalidateQueries({ queryKey: threadsQuery.queryKey, exact: true });
    },
  });
}

/**
 * The customer's live stream (S-68's bus, keyed by the customer): a `message` event refreshes that thread and the list.
 * Ids only — the thread is read again with the person's own session. EventSource reconnects on its own; while it is
 * down the queries above keep polling.
 */
export function useCustomerLive(enabled: boolean) {
  const qc = useQueryClient();
  useEffect(() => {
    if (!enabled || typeof EventSource === 'undefined') return;
    const source = new EventSource('/api/v1/me/live', { withCredentials: true });
    source.addEventListener('message', e => {
      const ref = refOf((e as MessageEvent<string>).data);
      void qc.invalidateQueries({ queryKey: ref ? ['me', 'threads', ref] : ['me', 'threads'] });
      void qc.invalidateQueries({ queryKey: threadsQuery.queryKey, exact: true });
    });
    return () => source.close();
  }, [enabled, qc]);
}

function refOf(data: string): string | null {
  try {
    const parsed: unknown = JSON.parse(data);
    return parsed && typeof parsed === 'object' && 'ref' in parsed && typeof parsed.ref === 'string' ? parsed.ref : null;
  } catch {
    return null;
  }
}
