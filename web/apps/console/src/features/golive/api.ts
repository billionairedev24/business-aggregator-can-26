import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/**
 * Go-live (S-118, api `GoLiveChecklist` / `GoLiveSwitch`):
 *   GET  /api/v1/console/go-live                                      {items: [MarketSummary]}
 *   GET  /api/v1/console/go-live/{marketId}                           Checklist
 *   POST /api/v1/console/go-live/{marketId}/gates/{gate}              {status, evidence, evidenceUrl?}
 *   POST /api/v1/console/go-live/{marketId}/launch-requests            {note?, overrideReason?}
 *   POST /api/v1/console/go-live/{marketId}/launch-requests/{id}/approve | /close   {confirm} | {reason?}
 *   POST /api/v1/console/go-live/{marketId}/rollback                   {reason, confirm}
 *   POST /api/v1/console/go-live/{marketId}/hypercare                  {startsOn?, primaries, secondaries, businessContacts}
 */
export const MarketSummary = z.object({
  id: z.string(), city: z.string(), province: z.string(), stage: z.string(), requestPending: z.boolean(), launchedAt: z.string().nullish(),
});
export type MarketSummary = z.infer<typeof MarketSummary>;
const Person = z.object({ id: z.string(), name: z.string() });
export const Gate = z.object({
  key: z.string(), kind: z.enum(['auto', 'manual', 'auto_or_manual']), owner: z.string(), required: z.boolean(),
  status: z.enum(['pass', 'fail', 'pending', 'not_applicable']), code: z.string(), params: z.record(z.string(), z.string()).default({}),
  evidence: z.string().nullish(), evidenceUrl: z.string().nullish(), source: z.string().nullish(), recordedBy: Person.nullish(),
  recordedAt: z.string().nullish(), recordable: z.boolean(), runbook: z.string(),
});
export type Gate = z.infer<typeof Gate>;
export const Request = z.object({
  id: z.string(), state: z.string(), requestedBy: Person, requestedAt: z.string(), expiresAt: z.string(), note: z.string().nullish(),
  override: z.boolean(), overrideReason: z.string().nullish(), blocking: z.array(z.string()), decidedBy: Person.nullish(),
  decidedAt: z.string().nullish(), decisionNote: z.string().nullish(),
});
export type Request = z.infer<typeof Request>;
export const Checklist = z.object({
  market: z.object({ id: z.string(), city: z.string(), province: z.string(), stage: z.string(), frenchFirst: z.boolean(), zone: z.string() }),
  generatedAt: z.string(), gates: z.array(Gate), blocking: z.array(z.string()), ready: z.boolean(), request: Request.nullish(),
  requests: z.array(Request), events: z.array(z.object({ kind: z.string(), by: Person, at: z.string(), reason: z.string().nullish(), requestId: z.string().nullish() })),
  hypercare: z.object({
    startsOn: z.string(), endsOn: z.string(),
    days: z.array(z.object({ date: z.string(), primary: Person, secondary: Person, business: Person })),
  }).nullish(),
});
export type Checklist = z.infer<typeof Checklist>;

const BASE = '/api/v1/console/go-live';
const enc = encodeURIComponent;
export const marketsQuery = queryOptions({
  queryKey: ['console', 'go-live', 'markets'],
  queryFn: async () => (await http(BASE, {}, z.object({ items: z.array(MarketSummary) }))).items,
});
export const checklistQuery = (market: string) => queryOptions({
  queryKey: ['console', 'go-live', 'checklist', market],
  queryFn: () => http(`${BASE}/${enc(market)}`, {}, Checklist),
});

/** Every write answers with the market's checklist: it replaces the cached one, and the market list is read again. */
function useGoLiveWrite<V>(market: string, run: (v: V) => Promise<Checklist>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: run,
    onSuccess: c => {
      qc.setQueryData(checklistQuery(market).queryKey, c);
      void qc.invalidateQueries({ queryKey: ['console', 'go-live', 'markets'] });
    },
  });
}
const post = <V>(market: string, path: (v: V) => string, body: (v: V) => unknown) => (v: V) =>
  http(`${BASE}/${enc(market)}${path(v)}`, { method: 'POST', body: body(v) }, Checklist);

export interface RecordInput { gate: string; status: 'pass' | 'fail' | 'not_applicable'; evidence: string; evidenceUrl?: string }
export const useRecordGate = (m: string) =>
  useGoLiveWrite(m, post(m, (v: RecordInput) => `/gates/${enc(v.gate)}`, ({ gate: _, ...rest }) => rest));
export const useRequestLaunch = (m: string) =>
  useGoLiveWrite(m, post(m, () => '/launch-requests', (v: { note?: string; overrideReason?: string }) => v));
export const useApprove = (m: string) =>
  useGoLiveWrite(m, post(m, (v: { id: string; confirm: string }) => `/launch-requests/${enc(v.id)}/approve`, v => ({ confirm: v.confirm })));
export const useCloseRequest = (m: string) =>
  useGoLiveWrite(m, post(m, (v: { id: string; reason?: string }) => `/launch-requests/${enc(v.id)}/close`, v => ({ reason: v.reason })));
export const useRollback = (m: string) =>
  useGoLiveWrite(m, post(m, () => '/rollback', (v: { reason: string; confirm: string }) => v));
export interface HypercareInput { startsOn?: string; primaries: string[]; secondaries: string[]; businessContacts: string[] }
export const useHypercare = (m: string) => useGoLiveWrite(m, post(m, () => '/hypercare', (v: HypercareInput) => v));
