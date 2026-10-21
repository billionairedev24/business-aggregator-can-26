import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/**
 * S-157, the console's offers (`/api/v1/console/offers`): Northline's own offer by email or text to the customers of a
 * market who agreed to that channel — preview, a test to oneself, the count, and the send (throttled by the worker,
 * which checks each consent again and adds the sender and the unsubscribe link).
 */
export const Campaign = z.object({
  id: z.string(), market: z.string(), channel: z.enum(['email', 'sms']), titleEn: z.string(), titleFr: z.string(), path: z.string(),
  recipients: z.number().int(), pending: z.number().int(), givenUp: z.number().int(), sentBy: z.string(), sentAt: z.string(), finishesAt: z.string(),
});
export type Campaign = z.infer<typeof Campaign>;
export const Overview = z.object({ markets: z.array(z.string()), perMinute: z.number().int(), campaigns: z.array(Campaign) });
export type Overview = z.infer<typeof Overview>;
const Rendered = z.object({ subject: z.string(), html: z.string(), text: z.string(), sms: z.string(), smsSegments: z.number().int() });
export const Preview = z.object({ en: Rendered, fr: Rendered, audience: z.object({ market: z.string(), email: z.number().int(), sms: z.number().int() }) });
export type Preview = z.infer<typeof Preview>;

export interface Draft { market: string; channel: 'email' | 'sms'; titleEn: string; bodyEn: string; titleFr: string; bodyFr: string; path: string }
export const EMPTY: Draft = { market: '', channel: 'email', titleEn: '', bodyEn: '', titleFr: '', bodyFr: '', path: '/shop' };

const PATH = '/api/v1/console/offers';
export const offersQuery = queryOptions({ queryKey: ['console', 'offers'], queryFn: () => http(PATH, {}, Overview) });

export const previewOffer = (d: Draft) => http(`${PATH}/preview`, { method: 'POST', body: d }, Preview);
export const testOffer = (d: Draft) => http(`${PATH}/test`, { method: 'POST', body: d }).then(() => true);

export function useSendOffer() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (d: Draft) => http(PATH, { method: 'POST', body: d }, Campaign),
    onSuccess: () => void qc.invalidateQueries({ queryKey: offersQuery.queryKey }),
  });
}
