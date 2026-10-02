import { useMutation, useQueryClient, queryOptions } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/**
 * CASL proof of consent (S-108, api `ConsentDeskController`), on the privacy screen:
 *   GET  /api/v1/console/consents?userId=…|contact=…            every grant and withdrawal, newest first
 *   POST /api/v1/console/consents/withdrawals {userId, category}  withdraw for a person who asked by phone, mail or email
 * A contact (email or phone) is matched by its hash, so the proof is found after the account was erased.
 */
export const CATEGORIES = ['marketing_email', 'marketing_sms', 'marketing_push'] as const;
export const ConsentRecord = z.object({
  id: z.string(), userId: z.string(), category: z.enum(CATEGORIES), action: z.enum(['granted', 'withdrawn']), at: z.string(), source: z.string(),
  wordingVersion: z.string().nullish(), language: z.string().nullish(), ipPrefix: z.string().nullish(), addressKnown: z.boolean(), actorId: z.string().nullish(),
});
export type ConsentRecord = z.infer<typeof ConsentRecord>;

const BASE = '/api/v1/console/consents';
const looksLikeId = (q: string) => /^[0-9A-Z]{26}$/.test(q);

export const consentLookupQuery = (q: string) => queryOptions({
  queryKey: ['console', 'consents', q],
  queryFn: async () => (await http(`${BASE}?${looksLikeId(q) ? 'userId' : 'contact'}=${encodeURIComponent(q)}`, {}, z.object({ items: z.array(ConsentRecord) }))).items,
  enabled: q.length > 0,
});

export function useWithdrawConsent() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (w: { userId: string; category: string }) => http(`${BASE}/withdrawals`, { method: 'POST', body: w }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['console', 'consents'] }),
  });
}

/** The categories granted now, per account: the newest record of each (records come newest first). */
export function grantedNow(records: ConsentRecord[]): { userId: string; category: string }[] {
  const seen = new Set<string>();
  const out: { userId: string; category: string }[] = [];
  for (const r of records) {
    const key = `${r.userId}|${r.category}`;
    if (seen.has(key)) continue;
    seen.add(key);
    if (r.action === 'granted') out.push({ userId: r.userId, category: r.category });
  }
  return out;
}
