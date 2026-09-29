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
