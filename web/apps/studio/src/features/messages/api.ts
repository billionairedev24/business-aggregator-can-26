import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/**
 * Messages API (server: ca.northline.messaging). The Studio polls — the thread list every 15 s, the open thread every
 * 5 s — until server-sent events land (DECISIONS.md). Attachments are shared with help cases.
 */
const base = (merchantId: string) => `/api/v1/merchants/${merchantId}`;
const lang = (locale: string) => ({ 'accept-language': locale === 'fr' ? 'fr-CA' : 'en-CA' });

export const Attachment = z.object({ id: z.string(), fileName: z.string(), contentType: z.string(), byteSize: z.number() });
export type Attachment = z.infer<typeof Attachment>;

export const SenderRole = z.enum(['merchant', 'customer', 'agent', 'system']);
export const Message = z.object({
  id: z.string(), senderRole: SenderRole, senderName: z.string().nullish(), body: z.string(), attachments: z.array(Attachment),
  at: z.string(), flagged: z.boolean(), templateKey: z.string().nullish(),
});
export type Message = z.infer<typeof Message>;

export const ThreadSummary = z.object({
  id: z.string(), kind: z.enum(['customer', 'support', 'case']), counterpartName: z.string(), subject: z.string().nullish(),
  refType: z.string().nullish(), refId: z.string().nullish(), refCode: z.string().nullish(), assigneeId: z.string().nullish(),
  lastMessageAt: z.string().nullish(), lastMessage: z.string().nullish(), unread: z.boolean(),
});
export type ThreadSummary = z.infer<typeof ThreadSummary>;

export const QuickReply = z.object({ key: z.string(), text: z.string() });
export type QuickReply = z.infer<typeof QuickReply>;
export const ThreadDetail = z.object({ thread: ThreadSummary, messages: z.array(Message), quickReplies: z.array(QuickReply) });
export type ThreadDetail = z.infer<typeof ThreadDetail>;

export const messagesKeys = {
  threads: (m: string) => ['merchant', m, 'threads'] as const,
  thread: (m: string, id: string, locale: string) => ['merchant', m, 'threads', id, locale] as const,
  badges: (m: string) => ['merchant', m, 'nav-badges'] as const,
};

export const threadsQuery = (m: string) => queryOptions({
  queryKey: messagesKeys.threads(m),
  queryFn: () => http(`${base(m)}/threads`, {}, z.object({ items: z.array(ThreadSummary) })).then(r => r.items),
  refetchInterval: 15_000,
});

export const threadQuery = (m: string, id: string, locale: string) => queryOptions({
  queryKey: messagesKeys.thread(m, id, locale),
  queryFn: () => http(`${base(m)}/threads/${id}`, { headers: lang(locale) }, ThreadDetail),
  refetchInterval: 5_000,
});

export const attachmentUrl = (m: string, id: string) => `${base(m)}/message-attachments/${id}`;

/** POST /message-attachments (multipart) — used by the Messages composer and help cases. */
export function uploadAttachment(m: string, file: File) {
  const form = new FormData();
  form.append('file', file);
  return http(`${base(m)}/message-attachments`, { method: 'POST', body: form }, Attachment);
}

/** Opening a thread marks it read for the whole team; the list and the sidebar badge refresh. */
export function useMarkRead(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (threadId: string) => http(`${base(m)}/threads/${threadId}/read`, { method: 'POST' }),
    onMutate: threadId => {
      qc.setQueryData<ThreadSummary[]>(messagesKeys.threads(m), old => old?.map(t => (t.id === threadId ? { ...t, unread: false } : t)));
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: messagesKeys.threads(m), exact: true });
      void qc.invalidateQueries({ queryKey: messagesKeys.badges(m) });
    },
  });
}

export interface SendInput { body: string; attachments: Attachment[]; templateKey?: string | null }

/** Sends with an optimistic bubble ("Sending…"); the server's masked text replaces it on success. */
export function useSendMessage(m: string, threadId: string, locale: string) {
  const qc = useQueryClient();
  const key = messagesKeys.thread(m, threadId, locale);
  return useMutation({
    mutationFn: (input: SendInput) => http(`${base(m)}/threads/${threadId}/messages`, {
      method: 'POST', body: { body: input.body, attachmentIds: input.attachments.map(a => a.id), templateKey: input.templateKey ?? null },
    }, Message),
    onMutate: async input => {
      await qc.cancelQueries({ queryKey: key });
      const previous = qc.getQueryData<ThreadDetail>(key);
      const pending: Message = { id: `pending-${Date.now()}`, senderRole: 'merchant', body: input.body.trim(), attachments: input.attachments, at: new Date().toISOString(), flagged: false, templateKey: input.templateKey ?? null };
      if (previous) qc.setQueryData<ThreadDetail>(key, { ...previous, messages: [...previous.messages, pending] });
      return { previous };
    },
    onError: (_e, _input, ctx) => { if (ctx?.previous) qc.setQueryData(key, ctx.previous); },
    onSuccess: sent => {
      qc.setQueryData<ThreadDetail>(key, old => old && { ...old, messages: [...old.messages.filter(x => !x.id.startsWith('pending-')), sent] });
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: key });
      void qc.invalidateQueries({ queryKey: messagesKeys.threads(m), exact: true });
    },
  });
}
