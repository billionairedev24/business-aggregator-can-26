import { keepPreviousData, queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';
import { Message, type Attachment } from '../messages/api';

/** Help & support API (server: ca.northline.messaging — help centre, platform status, helpdesk cases). */
const base = (merchantId: string) => `/api/v1/merchants/${merchantId}/help`;
const lang = (locale: string) => ({ 'accept-language': locale === 'fr' ? 'fr-CA' : 'en-CA' });
const items = <T extends z.ZodType>(item: T) => z.object({ items: z.array(item) }).transform(r => r.items);

export const HelpTopic = z.object({ key: z.string(), name: z.string(), articleCount: z.number(), caseTopic: z.string() });
export type HelpTopic = z.infer<typeof HelpTopic>;
export const ArticleSummary = z.object({ slug: z.string(), title: z.string(), section: z.string(), readMin: z.number() });
export type ArticleSummary = z.infer<typeof ArticleSummary>;
export const Article = ArticleSummary.extend({ body: z.string(), topicKeys: z.array(z.string()) });
export type Article = z.infer<typeof Article>;
export const StatusComponent = z.object({ key: z.string(), name: z.string(), state: z.enum(['operational', 'degraded', 'outage']), note: z.string().nullish(), since: z.string().nullish() });
export type StatusComponent = z.infer<typeof StatusComponent>;
export const Related = z.object({ type: z.string(), id: z.string(), label: z.string() });
export type Related = z.infer<typeof Related>;

export const CaseSummary = z.object({
  id: z.string(), code: z.string(), subject: z.string(), topic: z.string(),
  state: z.enum(['new', 'in_progress', 'waiting', 'resolved']), priority: z.enum(['normal', 'priority', 'urgent']), urgent: z.boolean(),
  channel: z.string().nullish(), agentName: z.string().nullish(), lastAgentReplyAt: z.string().nullish(), slaDueAt: z.string().nullish(),
  resolvedAt: z.string().nullish(), resolutionNote: z.string().nullish(), refType: z.string().nullish(), refId: z.string().nullish(),
  refLabel: z.string().nullish(), createdAt: z.string(),
});
export type CaseSummary = z.infer<typeof CaseSummary>;
export const CaseDetail = z.object({ summary: CaseSummary, messages: z.array(Message) });
export type CaseDetail = z.infer<typeof CaseDetail>;

export const helpKeys = {
  topics: (m: string, l: string) => ['merchant', m, 'help', 'topics', l] as const,
  articles: (m: string, l: string, q: string, topic: string) => ['merchant', m, 'help', 'articles', l, q, topic] as const,
  article: (m: string, l: string, slug: string) => ['merchant', m, 'help', 'article', l, slug] as const,
  status: (m: string, l: string) => ['merchant', m, 'help', 'status', l] as const,
  related: (m: string, l: string) => ['merchant', m, 'help', 'related', l] as const,
  cases: (m: string) => ['merchant', m, 'help', 'cases'] as const,
  case: (m: string, id: string) => ['merchant', m, 'help', 'cases', id] as const,
  badges: (m: string) => ['merchant', m, 'nav-badges'] as const,
};

export const topicsQuery = (m: string, l: string) => queryOptions({ queryKey: helpKeys.topics(m, l), staleTime: 5 * 60_000, queryFn: () => http(`${base(m)}/topics`, { headers: lang(l) }, items(HelpTopic)) });

export const articlesQuery = (m: string, l: string, q: string, topic: string) => queryOptions({
  queryKey: helpKeys.articles(m, l, q, topic), staleTime: 60_000, placeholderData: keepPreviousData,
  queryFn: () => {
    const params = new URLSearchParams();
    if (q) params.set('q', q);
    else if (topic) params.set('topic', topic);
    return http(`${base(m)}/articles${params.size ? `?${params}` : ''}`, { headers: lang(l) }, items(ArticleSummary));
  },
});

export const articleQuery = (m: string, l: string, slug: string) => queryOptions({ queryKey: helpKeys.article(m, l, slug), queryFn: () => http(`${base(m)}/articles/${slug}`, { headers: lang(l) }, Article) });
export const statusQuery = (m: string, l: string) => queryOptions({ queryKey: helpKeys.status(m, l), refetchInterval: 60_000, queryFn: () => http(`${base(m)}/status`, { headers: lang(l) }, items(StatusComponent)) });
export const relatedQuery = (m: string, l: string) => queryOptions({ queryKey: helpKeys.related(m, l), queryFn: () => http(`${base(m)}/related`, { headers: lang(l) }, items(Related)) });
export const casesQuery = (m: string) => queryOptions({ queryKey: helpKeys.cases(m), refetchInterval: 30_000, queryFn: () => http(`${base(m)}/cases`, {}, items(CaseSummary)) });
export const caseQuery = (m: string, id: string) => queryOptions({ queryKey: helpKeys.case(m, id), refetchInterval: 10_000, queryFn: () => http(`${base(m)}/cases/${id}`, {}, CaseDetail) });

export interface OpenCaseInput { topic: string; related: Related | null; body: string; attachments: Attachment[]; channel: string; urgent: boolean }

export function useOpenCase(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (i: OpenCaseInput) => http(`${base(m)}/cases`, {
      method: 'POST',
      body: { topic: i.topic, refType: i.related?.type ?? null, refId: i.related?.id ?? null, refLabel: i.related?.label ?? null, body: i.body, attachmentIds: i.attachments.map(a => a.id), channel: i.channel, urgent: i.urgent },
    }, CaseSummary),
    onSuccess: created => {
      qc.setQueryData<CaseSummary[]>(helpKeys.cases(m), old => [created, ...(old ?? []).filter(c => c.id !== created.id)]);
      void qc.invalidateQueries({ queryKey: helpKeys.cases(m), exact: true });
      void qc.invalidateQueries({ queryKey: helpKeys.badges(m) });
    },
  });
}

export function useReplyToCase(m: string, caseId: string) {
  const qc = useQueryClient();
  const key = helpKeys.case(m, caseId);
  return useMutation({
    mutationFn: (i: { body: string; attachments: Attachment[] }) => http(`${base(m)}/cases/${caseId}/messages`, { method: 'POST', body: { body: i.body, attachmentIds: i.attachments.map(a => a.id) } }, Message),
    onMutate: async i => {
      await qc.cancelQueries({ queryKey: key });
      const previous = qc.getQueryData<CaseDetail>(key);
      const pending: Message = { id: `pending-${Date.now()}`, senderRole: 'merchant', body: i.body, attachments: i.attachments, at: new Date().toISOString(), flagged: false, templateKey: null };
      if (previous) qc.setQueryData<CaseDetail>(key, { ...previous, messages: [...previous.messages, pending] });
      return { previous };
    },
    onError: (_e, _i, ctx) => { if (ctx?.previous) qc.setQueryData(key, ctx.previous); },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: key });
      void qc.invalidateQueries({ queryKey: helpKeys.cases(m), exact: true });
    },
  });
}
