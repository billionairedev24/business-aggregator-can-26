import { useEffect } from 'react';
import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';
import { CourierProgress } from '../tracking/courier';

/**
 * The customer's order (S-52): `GET /api/v1/me/orders/{id}` and its live stream `…/events` (text/event-stream, event
 * `order` with the same JSON on every change). Personal data: loaded in the browser only.
 */
/** S-151: what was cancelled — the whole order, or one shop's part (`merchantName`). */
export const Cancellation = z.object({ by: z.string(), reason: z.string(), at: z.string(), whole: z.boolean(), merchantName: z.string().nullish() });
export type Cancellation = z.infer<typeof Cancellation>;
/** S-151: the courier couldn't hand it over; the customer chooses by `decideBy` (else it is refunded). */
export const DeliveryFailure = z.object({
  reason: z.string(), failedAt: z.string(), decideBy: z.string(), attempts: z.number().int(),
  choice: z.enum(['redeliver', 'refund', 'auto_refund']).nullish(), chosenAt: z.string().nullish(), feeCents: z.number().int(),
});
export type DeliveryFailure = z.infer<typeof DeliveryFailure>;
/** S-151: a delivered line still in its return window. */
export const Returnable = z.object({
  lineId: z.string(), merchantId: z.string(), title: z.string(), qty: z.number().int(), amountCents: z.number().int(), returnUntil: z.string(),
});
export type Returnable = z.infer<typeof Returnable>;
export const ReturnView = z.object({
  id: z.string(), ref: z.string(), orderId: z.string(), orderRef: z.string().nullish(), merchantId: z.string(), merchantName: z.string().nullish(),
  state: z.enum(['requested', 'approved', 'declined', 'escalated', 'collected', 'received', 'refunded', 'withdrawn']),
  reason: z.string(), note: z.string().nullish(), hasPhoto: z.boolean(), method: z.enum(['pickup', 'dropoff']).nullish(),
  restock: z.boolean(), declineReason: z.string().nullish(), amountCents: z.number().int(),
  lines: z.array(z.object({ lineId: z.string(), title: z.string(), qty: z.number().int(), amountCents: z.number().int() })),
  createdAt: z.string(), decidedAt: z.string().nullish(), collectedAt: z.string().nullish(), receivedAt: z.string().nullish(),
  refundedAt: z.string().nullish(), canWithdraw: z.boolean(), canEscalate: z.boolean(),
});
export type ReturnView = z.infer<typeof ReturnView>;

export const Step = z.object({ key: z.enum(['paid', 'packing', 'pickup', 'delivered']), state: z.enum(['done', 'current', 'todo']) });
export const OrderTracking = z.object({
  orderId: z.string(), ref: z.string().nullish(), type: z.string(), state: z.string(), placedAt: z.string(),
  subtotalCents: z.number().int(), deliveryFeeCents: z.number().int(), taxCents: z.number().int(), totalCents: z.number().int(),
  delivery: z.object({
    kind: z.enum(['pooled', 'direct', 'pickup']), runLabel: z.string().nullish(), day: z.enum(['today', 'tomorrow', 'later']).nullish(),
    startsAt: z.string().nullish(), endsAt: z.string().nullish(), households: z.number().int(), etaAt: z.string().nullish(),
  }),
  shops: z.array(z.object({ merchantId: z.string(), name: z.string(), items: z.number().int(), packed: z.boolean(), cancelled: z.boolean().optional() })),
  steps: z.array(Step),
  deliveredAt: z.string().nullish(),
  // S-78: the courier's proof, the customer's confirmation and when the shops are paid without it
  deliveryProof: z.enum(['photo', 'signature', 'pin']).nullish(),
  confirmedAt: z.string().nullish(),
  canConfirm: z.boolean().optional(),
  paysShopsAt: z.string().nullish(),
  // S-88: the courier bringing it, live
  courier: CourierProgress.nullish(),
  // S-151: cancelling, a failed delivery's choice, returns
  canCancel: z.boolean().optional(),
  cancellations: z.array(Cancellation).optional(),
  deliveryFailure: DeliveryFailure.nullish(),
  returnable: z.array(Returnable).optional(),
  returns: z.array(ReturnView).optional(),
});
export type OrderTracking = z.infer<typeof OrderTracking>;

export const orderQuery = (orderId: string) => queryOptions({
  queryKey: ['order', orderId],
  queryFn: () => http(`/api/v1/me/orders/${encodeURIComponent(orderId)}`, {}, OrderTracking),
  // the stream keeps it fresh; if the stream can't open, poll
  refetchInterval: 30_000,
});

/**
 * Live tracking: an EventSource on `…/events` puts every `order` event into the query cache. The browser reconnects it
 * on its own; while it's down the query keeps polling every 30 s.
 */
export function useOrderStream(orderId: string, enabled: boolean) {
  const qc = useQueryClient();
  useEffect(() => {
    if (!enabled || typeof EventSource === 'undefined') return;
    const source = new EventSource(`/api/v1/me/orders/${encodeURIComponent(orderId)}/events`, { withCredentials: true });
    source.addEventListener('order', e => {
      const parsed = OrderTracking.safeParse(JSON.parse((e as MessageEvent<string>).data));
      if (parsed.success) qc.setQueryData(orderQuery(orderId).queryKey, parsed.data);
    });
    return () => source.close();
  }, [orderId, enabled, qc]);
}

export const useOrder = (orderId: string, enabled: boolean) => useQuery({ ...orderQuery(orderId), enabled });

/**
 * The courier's door photo (mobile gaps part 1): `GET …/proof-photo` → a signed URL that works for 5 minutes (object
 * storage's presigned GET; the api's own link under `local`, relative to this origin and relayed by the bff). 404 = none
 * to show (a PIN or signature, retention, or a delivery with an ID check — never shown): no photo, no retry.
 */
export const ProofPhoto = z.object({ url: z.string(), expiresAt: z.string() });
export const useProofPhoto = (orderId: string, enabled: boolean) => useQuery({
  queryKey: ['order', orderId, 'proof-photo'],
  queryFn: () => http(`/api/v1/me/orders/${encodeURIComponent(orderId)}/proof-photo`, {}, ProofPhoto),
  enabled,
  retry: false,
  // read a fresh link before the old one lapses while the page stays open
  staleTime: 4 * 60_000,
  refetchInterval: 4 * 60_000,
});

/** "Got everything" (S-78): `POST …/confirm` releases the shops' escrow at once; the answer is the updated order. */
export function useConfirmDelivery(orderId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => http(`/api/v1/me/orders/${encodeURIComponent(orderId)}/confirm`, { method: 'POST' }, OrderTracking),
    onSuccess: data => qc.setQueryData(orderQuery(orderId).queryKey, data),
  });
}

/**
 * S-151, the customer's after-sale:
 *   POST /api/v1/me/orders/{id}/cancel            {reason, note?} → the order (before any shop packed)
 *   POST /api/v1/me/orders/{id}/delivery-failure  {choice: redeliver | refund} → the order
 *   POST /api/v1/me/orders/{id}/returns           multipart lineIds, reason, note?, photo → the return
 *   POST /api/v1/me/returns/{id}/withdraw | escalate {statement}
 * Each answer refreshes the order.
 */
export function useCancelOrder(orderId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { reason: string; note?: string }) =>
      http(`/api/v1/me/orders/${encodeURIComponent(orderId)}/cancel`, { method: 'POST', body }, OrderTracking),
    onSuccess: data => qc.setQueryData(orderQuery(orderId).queryKey, data),
  });
}

export function useFailureChoice(orderId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (choice: 'redeliver' | 'refund') =>
      http(`/api/v1/me/orders/${encodeURIComponent(orderId)}/delivery-failure`, { method: 'POST', body: { choice } }, OrderTracking),
    onSuccess: data => qc.setQueryData(orderQuery(orderId).queryKey, data),
  });
}

export function useRequestReturn(orderId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (r: { lineIds: string[]; reason: string; note?: string; photo?: File }) => {
      const form = new FormData();
      r.lineIds.forEach(l => form.append('lineIds', l));
      form.append('reason', r.reason);
      if (r.note) form.append('note', r.note);
      if (r.photo) form.append('photo', r.photo);
      return http(`/api/v1/me/orders/${encodeURIComponent(orderId)}/returns`, { method: 'POST', body: form }, ReturnView);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: orderQuery(orderId).queryKey }),
  });
}

export function useReturnAction(orderId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (a: { returnId: string; action: 'withdraw' } | { returnId: string; action: 'escalate'; statement: string }) =>
      http(`/api/v1/me/returns/${encodeURIComponent(a.returnId)}/${a.action}`,
        { method: 'POST', ...(a.action === 'escalate' ? { body: { statement: a.statement } } : {}) }, ReturnView),
    onSuccess: () => qc.invalidateQueries({ queryKey: orderQuery(orderId).queryKey }),
  });
}
