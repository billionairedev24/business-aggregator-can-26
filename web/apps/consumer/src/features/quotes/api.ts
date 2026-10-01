import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';
import type { Locale } from '@northline/ui';
import { Confirmation } from '../booking/api';

/**
 * Quotes on the consumer side (S-56, module `hire`):
 *   POST /api/v1/me/quote-requests                      — "Send request to N providers"
 *   GET  /api/v1/me/quote-requests/{id}?lang            — compare: every provider asked, with their latest quote
 *   GET  /api/v1/me/quotes/{id}?lang                    — one quote (every line, versions); marks it viewed
 *   POST /api/v1/me/quotes/{id}/decline
 *   POST /api/v1/me/quotes/{id}/accept (Idempotency-Key, X-Step-Up)  — opens the escrow payment of the deposit
 *   POST /api/v1/me/quotes/{id}/accept/confirm (Idempotency-Key)     — the card is authorized: accept and book
 */
export const Line = z.object({
  kind: z.enum(['labour', 'part', 'fee', 'travel', 'discount']).catch('fee'),
  description: z.string(), note: z.string().nullish(), qty: z.coerce.number(), unitCents: z.number(), amountCents: z.number(), taxable: z.boolean(),
});
export type Line = z.infer<typeof Line>;

export const Version = z.object({ quoteId: z.string(), version: z.number().int(), state: z.string(), totalCents: z.number(), sentAt: z.string().nullish() });

export const Quote = z.object({
  id: z.string(), requestId: z.string(), ref: z.string(), merchantId: z.string(), version: z.number().int(), state: z.string(), expired: z.boolean(),
  scope: z.string(), exclusions: z.string().nullish(), proposedAt: z.string().nullish(), durationMin: z.number().int().nullish(),
  warranty: z.string(), depositKind: z.string(), depositBps: z.number().int().nullish(), lines: z.array(Line),
  subtotalCents: z.number(), taxBps: z.number().int(), taxCents: z.number(), totalCents: z.number(), depositCents: z.number(),
  sentAt: z.string().nullish(), validUntil: z.string().nullish(), versions: z.array(Version), currentQuoteId: z.string(),
});
export type Quote = z.infer<typeof Quote>;

export const ProviderSummary = z.object({
  merchantId: z.string(), slug: z.string(), name: z.string(), tier: z.string(), brandColor: z.string(),
  rating: z.number(), reviewCount: z.number().int(), onTimePct: z.number().nullish(), disputePct: z.number().nullish(), verifiedFacts: z.array(z.string()),
});
export type ProviderSummary = z.infer<typeof ProviderSummary>;

export const Offer = z.object({ provider: ProviderSummary, status: z.enum(['quoted', 'waiting', 'declined']).catch('waiting'), quote: Quote.nullish() });
export type Offer = z.infer<typeof Offer>;

export const Comparison = z.object({
  requestId: z.string(), ref: z.string(), categorySlug: z.string().nullish(), title: z.string(), description: z.string().nullish(),
  area: z.string().nullish(), preferredAt: z.string().nullish(), createdAt: z.string(), respondBy: z.string().nullish(), expiresAt: z.string().nullish(),
  offers: z.array(Offer),
});
export type Comparison = z.infer<typeof Comparison>;

export const QuotePage = z.object({
  quote: Quote, provider: ProviderSummary, title: z.string(), area: z.string().nullish(), bookingId: z.string().nullish(),
  others: z.array(z.object({ quoteId: z.string(), providerName: z.string(), totalCents: z.number(), state: z.string() })),
});
export type QuotePage = z.infer<typeof QuotePage>;

export const Requested = z.object({ requestId: z.string(), ref: z.string(), respondBy: z.string(), expiresAt: z.string(), providers: z.number().int() });

export const Acceptance = z.object({
  quoteId: z.string(), bookingId: z.string(), amountCents: z.number(), taxCents: z.number(), totalCents: z.number(), status: z.string(),
  paymentIntent: z.string().nullish(), clientSecret: z.string().nullish(), provider: z.enum(['stripe', 'fake']).catch('fake'), publishableKey: z.string().nullish(),
});
export type Acceptance = z.infer<typeof Acceptance>;

export interface QuoteAsk {
  category: string; providers: string[]; description: string;
  vehicle?: { year: string; make: string; model: string };
  eventDate?: string; guests?: number; budget?: string; note?: string; area?: string; preferredDate?: string;
}

export interface Visit { addressLine?: string; unit?: string; accessNote?: string; contactPhone?: string }

export const comparisonQuery = (requestId: string, locale: Locale) => queryOptions({
  queryKey: ['quotes', 'request', requestId, locale],
  queryFn: () => http(`/api/v1/me/quote-requests/${encodeURIComponent(requestId)}?lang=${locale}`, {}, Comparison),
  refetchInterval: 60_000,
});

export const quoteQuery = (quoteId: string, locale: Locale) => queryOptions({
  queryKey: ['quotes', 'quote', quoteId, locale],
  queryFn: () => http(`/api/v1/me/quotes/${encodeURIComponent(quoteId)}?lang=${locale}`, {}, QuotePage),
});

export const requestQuotes = (body: QuoteAsk) => http('/api/v1/me/quote-requests', { method: 'POST', body }, Requested);

export const declineQuote = (quoteId: string) => http(`/api/v1/me/quotes/${encodeURIComponent(quoteId)}/decline`, { method: 'POST' });

export const acceptQuote = (quoteId: string, visit: Visit, key: string, stepUp?: string) =>
  http(`/api/v1/me/quotes/${encodeURIComponent(quoteId)}/accept`, { method: 'POST', body: visit, idempotencyKey: key, headers: stepUp ? { 'x-step-up': stepUp } : undefined }, Acceptance);

export const confirmAcceptance = (quoteId: string, visit: Visit, key: string) =>
  http(`/api/v1/me/quotes/${encodeURIComponent(quoteId)}/accept/confirm`, { method: 'POST', body: visit, idempotencyKey: key }, Confirmation);
