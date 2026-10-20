import { keepPreviousData, queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

export const VIEWS = ['attention', 'live', 'escrow', 'late', 'failed', 'all'] as const;
export type View = (typeof VIEWS)[number];

export const STATUSES = ['new', 'live', 'escrow', 'escrow_48h', 'late', 'stuck', 'issue', 'delivered', 'done', 'cancelled', 'delivery_failed'] as const;
export const Status = z.enum(STATUSES);
export type Status = z.infer<typeof Status>;

/** One row of `GET /api/v1/console/orders` (S-81; api `MonitorOrders.Row`). */
export const Row = z.object({
  id: z.string(), ref: z.string().nullish(), kind: z.enum(['order', 'booking']), type: z.enum(['goods', 'food', 'service']),
  customer: z.string().nullish(), sellers: z.array(z.string()), amountCents: z.number(), state: z.string(), status: Status,
  attention: z.boolean(), at: z.string(), since: z.string().nullish(),
});
export type Row = z.infer<typeof Row>;

export const Monitor = z.object({
  asOf: z.string(), week: z.number(),
  counts: z.object({ attention: z.number(), live: z.number(), escrow: z.number(), late: z.number(), failed: z.number().default(0), all: z.number() }),
  items: z.array(Row), truncated: z.boolean(),
});
export type Monitor = z.infer<typeof Monitor>;

export interface MonitorFilter { view: View; q?: string; province?: string; market?: string }

export const monitorQuery = ({ view, q, province, market }: MonitorFilter) => queryOptions({
  queryKey: ['console', 'orders', view, q ?? null, province ?? null, market ?? null],
  queryFn: () => {
    const p = new URLSearchParams({ view });
    if (q) p.set('q', q);
    if (province) p.set('province', province);
    if (market) p.set('market', market);
    return http(`/api/v1/console/orders?${p.toString()}`, {}, Monitor);
  },
  refetchInterval: 60_000,
  placeholderData: keepPreviousData,
});

const Pickup = z.object({ merchantId: z.string(), name: z.string().nullish(), packedAt: z.string().nullish(), pickedUpAt: z.string().nullish() });
const RunRef = z.object({ id: z.string(), label: z.string().nullish(), state: z.string(), courier: z.object({ name: z.string().nullish() }).nullish(), late: z.boolean() });

/** `GET /api/v1/console/fulfilment/orders/{orderId}` (S-86): an order's delivery. 404 = no delivery (pickup, not handed over yet). */
export const Delivery = z.object({
  orderId: z.string(), orderRef: z.string().nullish(), kind: z.string(), market: z.string(), state: z.string(),
  orderBy: z.string().nullish(), packBy: z.string().nullish(), run: RunRef.nullish(), pickups: z.array(Pickup),
  dropoffEta: z.string().nullish(), deliveredAt: z.string().nullish(), proofKind: z.string().nullish(),
});
export type Delivery = z.infer<typeof Delivery>;

export const deliveryQuery = (orderId: string) => queryOptions({
  queryKey: ['console', 'orders', 'delivery', orderId],
  queryFn: () => http(`/api/v1/console/fulfilment/orders/${encodeURIComponent(orderId)}`, {}, Delivery),
  retry: false,
});

/** S-151, `GET /api/v1/console/orders/{orderId}/after-sale`: the order's cancellations, failed delivery and returns. */
export const AfterSale = z.object({
  orderId: z.string(), state: z.string(),
  cancellations: z.array(z.object({
    id: z.string(), merchantId: z.string().nullish(), merchantName: z.string().nullish(), by: z.string(), actorId: z.string().nullish(),
    reason: z.string(), note: z.string().nullish(), restock: z.boolean(), whole: z.boolean(), lines: z.number(), at: z.string(),
  })),
  deliveryFailure: z.object({
    reason: z.string(), failedAt: z.string(), decideBy: z.string(), attempts: z.number(), choice: z.string().nullish(), chosenAt: z.string().nullish(), feeCents: z.number(),
  }).nullish(),
  returns: z.array(z.object({ id: z.string(), ref: z.string(), merchantName: z.string().nullish(), state: z.string(), amountCents: z.number() })),
});
export type AfterSale = z.infer<typeof AfterSale>;
export const afterSaleQuery = (orderId: string) => queryOptions({
  queryKey: ['console', 'orders', 'after-sale', orderId],
  queryFn: () => http(`/api/v1/console/orders/${encodeURIComponent(orderId)}/after-sale`, {}, AfterSale),
  retry: false,
});

export const STAFF_CANCEL_REASONS = ['customer_request', 'merchant_request', 'fraud', 'payment_issue', 'delivery_issue', 'other'] as const;
/** S-151, `POST /api/v1/console/orders/{orderId}/cancel` (orders screen + the support action; audited). */
export function useStaffCancel(orderId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { reason: string; note?: string; restock: boolean }) =>
      http(`/api/v1/console/orders/${encodeURIComponent(orderId)}/cancel`, { method: 'POST', body }),
    onSettled: () => { void qc.invalidateQueries({ queryKey: ['console', 'orders'] }); },
  });
}
