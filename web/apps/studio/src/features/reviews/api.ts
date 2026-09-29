import { infiniteQueryOptions, queryOptions, useMutation, useQueryClient, type InfiniteData } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/** Reviews API (server: ca.northline.trust). Reviews are verified and read-only; a business replies once and reports. */
const base = (merchantId: string) => `/api/v1/merchants/${merchantId}/reviews`;

export const ReviewSummary = z.object({
  average: z.number(), count: z.number(),
  distribution: z.array(z.object({ stars: z.number(), count: z.number(), percent: z.number() })),
  praise: z.array(z.object({ tag: z.string(), percent: z.number() })),
});
export type ReviewSummary = z.infer<typeof ReviewSummary>;

export const ReportReason = z.enum(['fake', 'offensive', 'personal_info', 'wrong_business', 'other']);
export type ReportReason = z.infer<typeof ReportReason>;

export const Review = z.object({
  id: z.string(), rating: z.number(), authorName: z.string().nullish(), jobLabel: z.string().nullish(), refType: z.string(),
  text: z.string().nullish(), tags: z.array(z.string()), createdAt: z.string(), reply: z.string().nullish(), replyAt: z.string().nullish(),
  reportedAt: z.string().nullish(), reportReason: ReportReason.nullish(),
});
export type Review = z.infer<typeof Review>;
const Page = z.object({ items: z.array(Review), nextOffset: z.number().nullish() });
type Page = z.infer<typeof Page>;

export const PAGE_SIZE = 10;
export const reviewsKeys = {
  summary: (m: string) => ['merchant', m, 'reviews', 'summary'] as const,
  list: (m: string) => ['merchant', m, 'reviews', 'list'] as const,
};

export const reviewSummaryQuery = (m: string) => queryOptions({ queryKey: reviewsKeys.summary(m), queryFn: () => http(`${base(m)}/summary`, {}, ReviewSummary) });

export const reviewsQuery = (m: string) => infiniteQueryOptions({
  queryKey: reviewsKeys.list(m),
  initialPageParam: 0,
  queryFn: ({ pageParam }) => http(`${base(m)}?limit=${PAGE_SIZE}&offset=${pageParam}`, {}, Page),
  getNextPageParam: last => last.nextOffset ?? undefined,
});

function patch(qc: ReturnType<typeof useQueryClient>, m: string, id: string, change: (r: Review) => Review) {
  qc.setQueryData<InfiniteData<Page>>(reviewsKeys.list(m), data => data && {
    ...data, pages: data.pages.map(p => ({ ...p, items: p.items.map(r => (r.id === id ? change(r) : r)) })),
  });
}

/** Public reply — shown at once (optimistic), rolled back if the server refuses. */
export function useReplyToReview(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, text }: { id: string; text: string }) => http(`${base(m)}/${id}/reply`, { method: 'POST', body: { text } }, Review),
    onMutate: async ({ id, text }) => {
      await qc.cancelQueries({ queryKey: reviewsKeys.list(m) });
      const previous = qc.getQueryData<InfiniteData<Page>>(reviewsKeys.list(m));
      patch(qc, m, id, r => ({ ...r, reply: text.trim(), replyAt: new Date().toISOString() }));
      return { previous };
    },
    onError: (_e, _v, ctx) => { if (ctx?.previous) qc.setQueryData(reviewsKeys.list(m), ctx.previous); },
    onSuccess: saved => patch(qc, m, saved.id, () => saved),
  });
}

export function useReportReview(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason, note }: { id: string; reason: ReportReason; note: string }) =>
      http(`${base(m)}/${id}/report`, { method: 'POST', body: { reason, note: note.trim() || null } }, Review),
    onSuccess: saved => patch(qc, m, saved.id, () => saved),
  });
}
