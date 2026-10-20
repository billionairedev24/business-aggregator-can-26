import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

export const SellerStatus = z.enum(['to_pack', 'awaiting_pickup', 'out_for_delivery', 'delivered', 'issue', 'cancelled']);
export type SellerStatus = z.infer<typeof SellerStatus>;

export const Order = z.object({
  id: z.string(), ref: z.string().nullish(), customerName: z.string().nullish(), area: z.string().nullish(),
  lines: z.array(z.object({ id: z.string(), title: z.string(), qty: z.number(), unitCents: z.number(), state: z.string() })),
  totalCents: z.number(), windowStartsAt: z.string().nullish(), cutoffAt: z.string().nullish(), runLabel: z.string().nullish(),
  placedAt: z.string().nullish(), deliveredAt: z.string().nullish(), status: SellerStatus, issueNote: z.string().nullish(),
  // S-86: the courier's pickup at this shop once the order is on a run
  courierPickup: z.object({
    courierAssigned: z.boolean(), runLabel: z.string().nullish(), eta: z.string().nullish(), arrivedAt: z.string().nullish(), pickedUpAt: z.string().nullish(),
  }).nullish(),
});
export type Order = z.infer<typeof Order>;

export const OrderBoard = z.object({
  items: z.array(Order),
  counts: z.object({ toPack: z.number(), awaitingPickup: z.number(), deliveredToday: z.number(), issues: z.number(), nextCutoff: z.string().nullish(), nextRunLabel: z.string().nullish() }),
});
export type OrderBoard = z.infer<typeof OrderBoard>;

const base = (merchantId: string) => `/api/v1/merchants/${merchantId}/orders`;
export const ordersQuery = (merchantId: string) => queryOptions({ queryKey: ['merchant', merchantId, 'orders'], queryFn: () => http(base(merchantId), {}, OrderBoard) });

/** "Mark packed" — optimistic: the row moves to Awaiting pickup at once, rolled back on error. */
export function usePackOrder(merchantId: string) {
  const qc = useQueryClient();
  const key = ordersQuery(merchantId).queryKey;
  return useMutation({
    mutationFn: (orderId: string) => http(`${base(merchantId)}/${orderId}/pack`, { method: 'POST' }, Order),
    onMutate: async orderId => {
      await qc.cancelQueries({ queryKey: key });
      const prev = qc.getQueryData(key);
      qc.setQueryData(key, b => b && {
        ...b,
        items: b.items.map(o => (o.id === orderId ? { ...o, status: 'awaiting_pickup' as const } : o)),
        counts: { ...b.counts, toPack: Math.max(0, b.counts.toPack - 1), awaitingPickup: b.counts.awaitingPickup + 1 },
      });
      return { prev };
    },
    onError: (_e, _id, ctx) => { if (ctx?.prev) qc.setQueryData(key, ctx.prev); },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: key });
      void qc.invalidateQueries({ queryKey: ['merchant', merchantId, 'nav-badges'] });
      void qc.invalidateQueries({ queryKey: ['merchant', merchantId, 'dashboard'] });
    },
  });
}

/**
 * S-151: the seller cancels its part of an order until the courier picks it up — a reason, an optional note, and
 * whether the units go back on sale. 409 `too_late` / `nothing_to_cancel` / `order_closed` carry the server's words.
 */
export const SELLER_CANCEL_REASONS = ['out_of_stock', 'cannot_fulfil', 'customer_request', 'suspected_fraud', 'other'] as const;
export function useCancelOrder(merchantId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ orderId, ...body }: { orderId: string; reason: string; note?: string; restock: boolean }) =>
      http(`${base(merchantId)}/${encodeURIComponent(orderId)}/cancel`, { method: 'POST', body }, Order),
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: ordersQuery(merchantId).queryKey });
      void qc.invalidateQueries({ queryKey: ['merchant', merchantId, 'nav-badges'] });
      void qc.invalidateQueries({ queryKey: ['merchant', merchantId, 'dashboard'] });
    },
  });
}

/** S-151: a customer's return of this shop's items — the shop approves (pickup or drop-off) or declines it. */
export const SellerReturn = z.object({
  id: z.string(), ref: z.string(), orderId: z.string(), orderRef: z.string().nullish(), merchantId: z.string(), merchantName: z.string().nullish(),
  state: z.enum(['requested', 'approved', 'declined', 'escalated', 'collected', 'received', 'refunded', 'withdrawn']),
  reason: z.string(), note: z.string().nullish(), hasPhoto: z.boolean(), method: z.enum(['pickup', 'dropoff']).nullish(),
  restock: z.boolean(), declineReason: z.string().nullish(), amountCents: z.number().int(),
  lines: z.array(z.object({ lineId: z.string(), title: z.string(), qty: z.number().int(), amountCents: z.number().int() })),
  createdAt: z.string(), decidedAt: z.string().nullish(), collectedAt: z.string().nullish(), receivedAt: z.string().nullish(),
  refundedAt: z.string().nullish(),
});
export type SellerReturn = z.infer<typeof SellerReturn>;
const returnsBase = (merchantId: string) => `/api/v1/merchants/${merchantId}/returns`;
export const returnsQuery = (merchantId: string) => queryOptions({
  queryKey: ['merchant', merchantId, 'returns'],
  queryFn: () => http(returnsBase(merchantId), {}, z.object({ items: z.array(SellerReturn) })).then(r => r.items),
});
export const returnPhotoUrl = (merchantId: string, returnId: string) => `${returnsBase(merchantId)}/${encodeURIComponent(returnId)}/photo`;

export type ReturnDecision =
  | { returnId: string; action: 'approve'; method: 'pickup' | 'dropoff'; restock: boolean }
  | { returnId: string; action: 'decline'; reason: string }
  | { returnId: string; action: 'received' };
export function useReturnDecision(merchantId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (d: ReturnDecision) => {
      const url = `${returnsBase(merchantId)}/${encodeURIComponent(d.returnId)}/${d.action}`;
      const body = d.action === 'approve' ? { method: d.method, restock: d.restock } : d.action === 'decline' ? { reason: d.reason } : undefined;
      return http(url, { method: 'POST', ...(body ? { body } : {}) }, SellerReturn);
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: returnsQuery(merchantId).queryKey });
      void qc.invalidateQueries({ queryKey: ordersQuery(merchantId).queryKey });
    },
  });
}
