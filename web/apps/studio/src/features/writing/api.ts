import { useMutation } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/** S-131 writing help. Every answer is a draft (`aiAssisted: true`): nothing is saved, sent or published by these calls. */
const Copy = z.object({ title: z.string(), description: z.string(), bullets: z.array(z.string()) });
export type Copy = z.infer<typeof Copy>;
export const ListingCopy = z.object({ en: Copy, fr: Copy, aiAssisted: z.literal(true), model: z.string() });
export type ListingCopy = z.infer<typeof ListingCopy>;
export interface ListingFacts {
  kind: 'service' | 'product'; name?: string; categoryId?: string; brand?: string; attributes?: Record<string, string>;
  included?: string; durationMin?: number; notes?: string;
}
const blank = (s?: string) => !s || !s.trim();
export const compact = (f: ListingFacts): ListingFacts => ({
  kind: f.kind,
  ...(blank(f.name) ? {} : { name: f.name!.trim() }),
  ...(blank(f.categoryId) ? {} : { categoryId: f.categoryId }),
  ...(blank(f.brand) ? {} : { brand: f.brand!.trim() }),
  ...(f.attributes && Object.keys(f.attributes).length ? { attributes: f.attributes } : {}),
  ...(blank(f.included) ? {} : { included: f.included!.trim() }),
  ...(f.durationMin ? { durationMin: f.durationMin } : {}),
});
export const useListingCopy = (merchantId: string) => useMutation({
  mutationFn: (facts: ListingFacts) => http(`/api/v1/merchants/${merchantId}/listing-copy`, { method: 'POST', body: compact(facts) }, ListingCopy),
});

export const QuoteLineSuggestion = z.object({
  lines: z.array(z.object({ kind: z.enum(['labour', 'part', 'fee', 'travel']), description: z.string(), qty: z.number() })),
  questions: z.array(z.string()),
  aiAssisted: z.literal(true),
});
export type QuoteLineSuggestion = z.infer<typeof QuoteLineSuggestion>;
export const useQuoteLineSuggestions = (merchantId: string) => useMutation({
  mutationFn: (requestId: string) => http(`/api/v1/merchants/${merchantId}/quote-requests/${requestId}/line-suggestions`, { method: 'POST', body: {} }, QuoteLineSuggestion),
});

export const ReplySuggestions = z.object({ replies: z.array(z.string()), aiAssisted: z.literal(true) });
export const useReplySuggestions = (merchantId: string) => useMutation({
  mutationFn: (threadId: string) => http(`/api/v1/merchants/${merchantId}/threads/${threadId}/reply-suggestions`, { method: 'POST' }, ReplySuggestions),
});

const Version = z.object({ summary: z.string(), themes: z.array(z.string()) });
export const ReviewSummaryDraft = z.object({ en: Version, fr: Version, reviews: z.number(), aiAssisted: z.literal(true) });
export type ReviewSummaryDraft = z.infer<typeof ReviewSummaryDraft>;
export const useReviewSummaryDraft = (merchantId: string) => useMutation({
  mutationFn: () => http(`/api/v1/merchants/${merchantId}/reviews/summary-draft`, { method: 'POST' }, ReviewSummaryDraft),
});
