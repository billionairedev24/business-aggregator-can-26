import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http, newIdempotencyKey, ValidationError, type FieldError } from '../../lib/http';

/**
 * Finance screens (Earnings, Reports, Payouts, Refunds & disputes) — payments module of the api.
 * Money-moving POSTs send an Idempotency-Key (one per user action, reused on retry) and, for payouts and bank changes,
 * the step-up proof from `stepUp.ts` as X-Step-Up.
 */
const base = (merchantId: string) => `/api/v1/merchants/${merchantId}`;

// ── schemas ───────────────────────────────────────────────────────────────────────────────────────────────────────

export const Frequency = z.enum(['weekly', 'daily', 'monthly', 'manual']);
export type Frequency = z.infer<typeof Frequency>;
export const MonthlyAnchor = z.enum(['first', 'fifteenth', 'last']);
export type MonthlyAnchor = z.infer<typeof MonthlyAnchor>;
export const Reserve = z.enum(['none', 'keep_500', 'percent_10']);
export type Reserve = z.infer<typeof Reserve>;
export const Tier = z.enum(['registered', 'trusted', 'master']);
export type Tier = z.infer<typeof Tier>;
const Kind = z.enum(['service', 'goods', 'food']);

export const EarningsOverview = z.object({
  headlineCents: z.number(), availableCents: z.number(), escrowNetCents: z.number(), escrowCount: z.number(),
  onHoldCents: z.number(), onHoldDisputes: z.number(), onHoldRefunds: z.number(), nextPayoutAt: z.string().nullish(),
  frequency: Frequency, tier: Tier, takeRateBps: z.number(), takeRates: z.record(z.string(), z.number()),
});
export type EarningsOverview = z.infer<typeof EarningsOverview>;

export const LedgerLine = z.object({
  id: z.string(), kind: Kind, label: z.string(), orderNumber: z.string().nullish(), occurredAt: z.string(), customerName: z.string().nullish(),
  grossCents: z.number(), feeCents: z.number(), netCents: z.number(), state: z.enum(['held', 'released', 'refunded', 'disputed']),
  releaseAt: z.string().nullish(), releasedAt: z.string().nullish(),
});
export type LedgerLine = z.infer<typeof LedgerLine>;
const Items = <T extends z.ZodType>(item: T) => z.object({ items: z.array(item) });

export const Period = z.enum(['30d', '90d', '12mo']);
export type Period = z.infer<typeof Period>;
export const Report = z.object({
  period: Period, from: z.string(), to: z.string(), grossCents: z.number(), grossChangePct: z.number().nullish(), count: z.number(),
  averageTicketCents: z.number(), repeatCustomerPct: z.number().nullish(), refundRateBps: z.number(), benchmarkRefundRateBps: z.number().nullish(),
  granularity: z.enum(['day', 'week', 'month']),
  series: z.array(z.object({ start: z.string(), currentCents: z.number(), previousCents: z.number() })),
  byListing: z.array(z.object({ name: z.string().nullish(), grossCents: z.number() })),
  sources: z.array(z.object({ source: z.enum(['search', 'repeat', 'embed', 'referral']), pct: z.number() })),
});
export type Report = z.infer<typeof Report>;

export const Schedule = z.object({ frequency: Frequency, weekday: z.number().nullish(), monthlyAnchor: MonthlyAnchor.nullish(), reserve: Reserve });
export type Schedule = z.infer<typeof Schedule>;
export const Account = z.object({
  id: z.string(), method: z.enum(['instant', 'manual']), institutionName: z.string(), last4: z.string(), holderName: z.string(), label: z.string(),
  state: z.enum(['draft', 'pending', 'active', 'replaced', 'discarded']), effectiveAt: z.string().nullish(),
});
export type Account = z.infer<typeof Account>;
export const PayoutOverview = z.object({
  availableCents: z.number(), payableCents: z.number(), reserveCents: z.number(), nextPayoutAt: z.string().nullish(), schedule: Schedule,
  account: Account.nullish(), pendingAccount: Account.nullish(), pausedUntil: z.string().nullish(),
  instant: z.object({ eligible: z.boolean(), feeBps: z.number(), minFeeCents: z.number(), minAmountCents: z.number() }),
});
export type PayoutOverview = z.infer<typeof PayoutOverview>;
export const PayoutLine = z.object({
  id: z.string(), reference: z.string().nullish(), kind: z.enum(['scheduled', 'instant']), state: z.enum(['pending', 'in_transit', 'paid', 'failed', 'canceled']),
  createdAt: z.string(), arrivesAt: z.string(), amountCents: z.number(), feeCents: z.number(), netCents: z.number(), itemCount: z.number(), destination: z.string().nullish(),
});
export type PayoutLine = z.infer<typeof PayoutLine>;
export const Preview = z.object({ nextPayoutAt: z.string().nullish(), amountCents: z.number() });
export const LinkSession = z.object({ mode: z.enum(['stripe', 'fake']), clientSecret: z.string().nullish(), publishableKey: z.string().nullish() });

export const Evidence = z.object({ id: z.string(), kind: z.enum(['photo', 'report', 'gps', 'document']), name: z.string(), contentType: z.string().nullish(), size: z.number(), by: z.string(), at: z.string(), downloadable: z.boolean() });
export type Evidence = z.infer<typeof Evidence>;
export const CaseDetail = z.object({
  id: z.string(), type: z.enum(['dispute', 'refund']), caseNumber: z.string(), subject: z.string(), amountCents: z.number(),
  customerName: z.string().nullish(), customerStatement: z.string().nullish(), response: z.string().nullish(), responseUpdatedAt: z.string().nullish(),
  evidence: z.array(Evidence), state: z.string(), offer: z.object({ amountCents: z.number(), state: z.string(), expiresAt: z.string().nullish() }).nullish(),
  dueBy: z.string().nullish(), auto: z.boolean(), openedAt: z.string(),
});
export type CaseDetail = z.infer<typeof CaseDetail>;
export const CaseRow = z.object({ id: z.string(), type: z.enum(['dispute', 'refund']), caseNumber: z.string(), what: z.string(), amountCents: z.number(), kind: z.enum(['refund', 'credit']), outcome: z.string(), openedAt: z.string() });
export type CaseRow = z.infer<typeof CaseRow>;
export const CasesOverview = z.object({
  openDisputes: z.number(), refundsLast30Days: z.number(), disputeRateBps: z.number(), disputeRateFloorBps: z.number(),
  open: z.array(CaseDetail), history: z.array(CaseRow),
});
export type CasesOverview = z.infer<typeof CasesOverview>;

// ── queries ───────────────────────────────────────────────────────────────────────────────────────────────────────

export const earningsQuery = (m: string) => queryOptions({ queryKey: ['finance', m, 'earnings'], queryFn: () => http(`${base(m)}/earnings`, {}, EarningsOverview) });
export const ledgerQuery = (m: string) => queryOptions({ queryKey: ['finance', m, 'ledger'], queryFn: async () => (await http(`${base(m)}/earnings/ledger?limit=500`, {}, Items(LedgerLine))).items });
export const reportQuery = (m: string, period: Period) => queryOptions({ queryKey: ['finance', m, 'report', period], queryFn: () => http(`${base(m)}/reports?period=${period}`, {}, Report) });
export const payoutOverviewQuery = (m: string) => queryOptions({ queryKey: ['finance', m, 'payouts', 'overview'], queryFn: () => http(`${base(m)}/payouts/overview`, {}, PayoutOverview) });
export const payoutHistoryQuery = (m: string) => queryOptions({ queryKey: ['finance', m, 'payouts', 'history'], queryFn: async () => (await http(`${base(m)}/payouts?limit=200`, {}, Items(PayoutLine))).items });
export const schedulePreviewQuery = (m: string, s: Schedule) => queryOptions({
  queryKey: ['finance', m, 'payouts', 'preview', s],
  queryFn: () => http(`${base(m)}/payouts/schedule/preview?${new URLSearchParams({ frequency: s.frequency, reserve: s.reserve, ...(s.weekday ? { weekday: String(s.weekday) } : {}), ...(s.monthlyAnchor ? { monthlyAnchor: s.monthlyAnchor } : {}) })}`, {}, Preview),
});
export const casesQuery = (m: string) => queryOptions({ queryKey: ['finance', m, 'cases'], queryFn: () => http(`${base(m)}/refunds`, {}, CasesOverview) });

/** Download links (same-origin GETs through the BFF; the browser saves the CSV). */
export const downloads = {
  export: (m: string, period: Period) => `${base(m)}/reports/export.csv?period=${period}`,
  gst: (m: string, year: number) => `${base(m)}/reports/gst-summary.csv?year=${year}`,
  annual: (m: string, year: number) => `${base(m)}/reports/annual-statement.csv?year=${year}`,
  evidence: (m: string, disputeId: string, evidenceId: string) => `${base(m)}/disputes/${disputeId}/evidence/${evidenceId}`,
};

// ── mutations ─────────────────────────────────────────────────────────────────────────────────────────────────────

/** An Idempotency-Key per user action: kept while the same action is retried, renewed for the next one. */
export function actionKey() {
  let key = newIdempotencyKey();
  return { get: () => key, renew: () => { key = newIdempotencyKey(); } };
}

const invalidate = (qc: ReturnType<typeof useQueryClient>, m: string) => qc.invalidateQueries({ queryKey: ['finance', m] }).then(() => qc.invalidateQueries({ queryKey: ['merchant', m, 'nav-badges'] }));

export function useInstantPayout(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { amountCents: number; key: string; proof: string }) => http(`${base(m)}/payouts/instant`, { method: 'POST', body: { amountCents: v.amountCents }, idempotencyKey: v.key, headers: { 'x-step-up': v.proof } }, PayoutLine),
    onSuccess: () => invalidate(qc, m),
  });
}

export function useSaveSchedule(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (s: Schedule) => http(`${base(m)}/payouts/schedule`, { method: 'PUT', body: s }, PayoutOverview),
    onSuccess: data => { qc.setQueryData(payoutOverviewQuery(m).queryKey, data); return invalidate(qc, m); },
  });
}

export const useLinkSession = (m: string) => useMutation({ mutationFn: () => http(`${base(m)}/payouts/bank-accounts/link-session`, { method: 'POST' }, LinkSession) });

export interface BankInput { method: 'instant' | 'manual'; linkedAccount?: string; institution?: string; transit?: string; accountNumber?: string; holderName?: string }
export const usePrepareBank = (m: string) => useMutation({ mutationFn: (b: BankInput) => http(`${base(m)}/payouts/bank-accounts`, { method: 'POST', body: b }, Account) });

export function useConfirmBank(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { accountId: string; key: string; proof: string }) => http(`${base(m)}/payouts/bank-accounts/${v.accountId}/confirm`, { method: 'POST', idempotencyKey: v.key, headers: { 'x-step-up': v.proof } }, Account),
    onSuccess: () => invalidate(qc, m),
  });
}

function useCaseMutation<V>(m: string, fn: (v: V) => Promise<CaseDetail>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => invalidate(qc, m) });
}
export const useSaveResponse = (m: string) => useCaseMutation(m, (v: { disputeId: string; response: string }) => http(`${base(m)}/disputes/${v.disputeId}/response`, { method: 'PUT', body: { response: v.response } }, CaseDetail));
export const useUploadEvidence = (m: string) => useCaseMutation(m, (v: { disputeId: string; file: File }) => uploadEvidence(m, v.disputeId, v.file));

/** Evidence goes up as the raw file (Content-Type + X-File-Name), not multipart — see RefundCaseController. */
async function uploadEvidence(m: string, disputeId: string, file: File): Promise<CaseDetail> {
  const xsrf = document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='))?.slice('XSRF-TOKEN='.length);
  const res = await fetch(`${base(m)}/disputes/${disputeId}/evidence`, {
    method: 'POST', credentials: 'include', body: file,
    headers: { accept: 'application/json', 'content-type': file.type || 'application/octet-stream', 'x-file-name': encodeURIComponent(file.name), ...(xsrf ? { 'x-xsrf-token': decodeURIComponent(xsrf) } : {}) },
  });
  const text = await res.text();
  const data: unknown = text ? JSON.parse(text) : undefined;
  if (res.status === 422 && data && typeof data === 'object' && 'errors' in data) throw new ValidationError((data as { errors: FieldError[] }).errors);
  if (!res.ok) throw new ApiError(res.status, res.statusText || `Upload failed (${res.status})`, data);
  return CaseDetail.parse(data);
}
export const useGoodwillOffer = (m: string) => useCaseMutation(m, (v: { disputeId: string; amountCents: number; key: string }) => http(`${base(m)}/disputes/${v.disputeId}/goodwill-offer`, { method: 'POST', body: { amountCents: v.amountCents }, idempotencyKey: v.key }, CaseDetail));
export const useFullRefund = (m: string) => useCaseMutation(m, (v: { disputeId: string; key: string }) => http(`${base(m)}/disputes/${v.disputeId}/full-refund`, { method: 'POST', idempotencyKey: v.key }, CaseDetail));
export const useContestDispute = (m: string) => useCaseMutation(m, (v: { disputeId: string }) => http(`${base(m)}/disputes/${v.disputeId}/contest`, { method: 'POST' }, CaseDetail));
export const useAcceptRefund = (m: string) => useCaseMutation(m, (v: { refundId: string; key: string }) => http(`${base(m)}/refunds/${v.refundId}/accept`, { method: 'POST', idempotencyKey: v.key }, CaseDetail));
export const useContestRefund = (m: string) => useCaseMutation(m, (v: { refundId: string; reason: string }) => http(`${base(m)}/refunds/${v.refundId}/contest`, { method: 'POST', body: { reason: v.reason } }, CaseDetail));
