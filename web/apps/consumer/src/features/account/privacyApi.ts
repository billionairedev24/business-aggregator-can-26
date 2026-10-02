import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http } from '@northline/client';
import { accountSummaryQuery } from './api';
import { profileQuery } from './settingsApi';

/**
 * Privacy requests (S-105) — access (a copy of one's data), correction, erasure ("Delete my account"):
 *   GET  /api/v1/me/privacy-requests                     the person's requests
 *   GET  /api/v1/me/privacy-requests/correctable-fields  what staff can correct on request
 *   POST /api/v1/me/privacy-requests {type, corrections?, note?}   → awaiting_verification + a texted code
 *   POST /api/v1/me/privacy-requests/{id}/verify {code} | X-Step-Up (passkey / authenticator proof)
 *   POST /api/v1/me/privacy-requests/{id}/verification-code       a new code
 *   POST /api/v1/me/privacy-requests/{id}/withdraw                before an erasure starts
 *   POST /api/v1/me/privacy-requests/{id}/download-link           a ready export: {url, summaryUrl, expiresAt}
 * The law, its deadline and the regulator come from the region model (the person's province), never from the page.
 */
export const PrivacyRequest = z.object({
  id: z.string(), reference: z.string(),
  type: z.enum(['access', 'correction', 'erasure']),
  state: z.enum(['awaiting_verification', 'verified', 'in_progress', 'completed', 'rejected', 'withdrawn']),
  law: z.object({ code: z.string(), name: z.string(), shortName: z.string(), authority: z.string(), authorityUrl: z.string() }),
  receivedAt: z.string(), dueAt: z.string(), extendedTo: z.string().nullish(), scheduledFor: z.string().nullish(),
  completedAt: z.string().nullish(), codeSentTo: z.string().nullish(), decision: z.string().nullish(),
  export: z.object({ ready: z.boolean(), expiresAt: z.string().nullish() }).nullish(),
  corrections: z.array(z.object({ field: z.string(), value: z.string() })).catch([]),
  holdsOpen: z.number().catch(0),
});
export type PrivacyRequest = z.infer<typeof PrivacyRequest>;
export type RequestType = PrivacyRequest['type'];

export const privacyRequestsQuery = queryOptions({
  queryKey: ['me', 'privacy-requests'],
  queryFn: async () => (await http('/api/v1/me/privacy-requests', {}, z.object({ items: z.array(PrivacyRequest) }))).items,
});
export const correctableQuery = queryOptions({
  queryKey: ['me', 'privacy-requests', 'fields'],
  queryFn: async () => (await http('/api/v1/me/privacy-requests/correctable-fields', {}, z.object({ items: z.array(z.string()) }))).items,
  staleTime: Infinity,
});

const path = (id: string, action = '') => `/api/v1/me/privacy-requests/${encodeURIComponent(id)}${action}`;
const stepUp = (proof?: string) => (proof ? { 'x-step-up': proof } : undefined);

/** A mutation that refreshes the list (and the profile, whose "Deletion requested" line follows an erasure). */
function usePrivacyMutation<A>(fn: (a: A) => Promise<PrivacyRequest>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: async () => {
      await Promise.all([privacyRequestsQuery.queryKey, profileQuery.queryKey, accountSummaryQuery.queryKey]
        .map(k => qc.invalidateQueries({ queryKey: k })));
    },
  });
}

export interface OpenRequest { type: RequestType; corrections?: { field: string; value: string }[]; note?: string }
export const useOpenRequest = () => usePrivacyMutation((r: OpenRequest) =>
  http('/api/v1/me/privacy-requests', { method: 'POST', body: r }, PrivacyRequest));
export const useVerify = () => usePrivacyMutation(({ id, code, proof }: { id: string; code?: string; proof?: string }) =>
  http(path(id, '/verify'), { method: 'POST', body: code ? { code } : undefined, headers: stepUp(proof) }, PrivacyRequest));
export const useResendCode = () => usePrivacyMutation((id: string) => http(path(id, '/verification-code'), { method: 'POST' }, PrivacyRequest));
export const useWithdraw = () => usePrivacyMutation((id: string) => http(path(id, '/withdraw'), { method: 'POST' }, PrivacyRequest));

export const DownloadLink = z.object({ url: z.string(), summaryUrl: z.string(), expiresAt: z.string() });
export const downloadLink = (id: string) => http(path(id, '/download-link'), { method: 'POST' }, DownloadLink);

/** The API's ProblemDetail code of a refusal (403 step_up_required, 409 request_open, …). */
export const problemCode = (e: unknown): string | undefined =>
  e instanceof ApiError && e.body && typeof e.body === 'object' && 'code' in e.body ? String((e.body as { code: unknown }).code) : undefined;
