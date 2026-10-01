import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/** `RunSummary` (S-86, docs/CONSOLE_PLAN.md § Delivery and dispatch). */
export const Run = z.object({
  id: z.string(), label: z.string().nullish(), part: z.number(), market: z.string(), kind: z.string(),
  state: z.enum(['planned', 'loading', 'en_route', 'done']), startsAt: z.string().nullish(), endsAt: z.string().nullish(), packBy: z.string().nullish(),
  courier: z.object({ id: z.string(), userId: z.string(), name: z.string().nullish() }).nullish(),
  orders: z.number(), stopsDone: z.number(), stopsTotal: z.number(), nextEta: z.string().nullish(), late: z.boolean(), heuristic: z.string(),
});
export type Run = z.infer<typeof Run>;

const Position = z.object({ lat: z.number(), lng: z.number(), heading: z.number().nullish(), at: z.string() });
const Shift = z.object({ id: z.string(), startsAt: z.string(), endsAt: z.string(), state: z.string() });
export const Courier = z.object({
  id: z.string(), userId: z.string(), name: z.string().nullish(), market: z.string().nullish(), vehicle: z.string().nullish(),
  status: z.enum(['offline', 'available', 'on_run']), active: z.boolean(), shift: Shift.nullish(), runId: z.string().nullish(), position: Position.nullish(),
});
export type Courier = z.infer<typeof Courier>;

const Point = z.object({ lat: z.number(), lng: z.number() });
export const Zone = z.object({
  id: z.string(), marketId: z.string(), name: z.string(), ring: z.array(Point), runsPerDay: z.number().nullish(),
  feeStdCents: z.number().nullish(), feePlusCents: z.number().nullish(), minBasketCents: z.number().nullish(),
});
export type Zone = z.infer<typeof Zone>;
/** `GET /api/v1/console/delivery/map?market=` (S-81). */
export const DeliveryMap = z.object({
  market: z.object({ id: z.string(), city: z.string(), province: z.string(), lat: z.number().nullish(), lng: z.number().nullish() }),
  zones: z.array(Zone),
  basemap: z.object({ tiles: z.string(), attribution: z.string() }).nullish(),
});
export type DeliveryMap = z.infer<typeof DeliveryMap>;

const items = <T extends z.ZodTypeAny>(item: T) => z.object({ items: z.array(item) });

/** Fulfilment's markets are the city as the region model names it (S-86). */
export const runsQuery = (city: string) => queryOptions({
  queryKey: ['console', 'delivery', 'runs', city],
  queryFn: () => http(`/api/v1/console/fulfilment/runs?market=${encodeURIComponent(city)}`, {}, items(Run)).then(r => r.items),
  refetchInterval: 30_000,
});
export const couriersQuery = (city: string) => queryOptions({
  queryKey: ['console', 'delivery', 'couriers', city],
  queryFn: () => http(`/api/v1/console/fulfilment/couriers?market=${encodeURIComponent(city)}`, {}, items(Courier)).then(r => r.items),
  refetchInterval: 15_000,
});
export const mapQuery = (marketId: string) => queryOptions({
  queryKey: ['console', 'delivery', 'map', marketId],
  queryFn: () => http(`/api/v1/console/delivery/map?market=${encodeURIComponent(marketId)}`, {}, DeliveryMap),
  staleTime: 5 * 60_000,
});

function useDeliveryMutation<V>(fn: (v: V) => Promise<unknown>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'delivery'] }) });
}

/** Reassign a run that hasn't started (409 `courier_busy` / `run_started`; audit `fulfilment.run_assigned`). */
export const useAssignRun = () => useDeliveryMutation(({ runId, courierId }: { runId: string; courierId: string }) =>
  http(`/api/v1/console/fulfilment/runs/${encodeURIComponent(runId)}/assign`, { method: 'POST', body: { courierId } }, Run));
/** Pause a courier: no new runs (audit `fulfilment.courier_paused` with the reason). */
export const usePauseCourier = () => useDeliveryMutation(({ courierId, reason }: { courierId: string; reason: string }) =>
  http(`/api/v1/console/fulfilment/couriers/${encodeURIComponent(courierId)}/pause`, { method: 'POST', body: { reason } }, Courier));
export const useResumeCourier = () => useDeliveryMutation((courierId: string) =>
  http(`/api/v1/console/fulfilment/couriers/${encodeURIComponent(courierId)}/resume`, { method: 'POST' }, Courier));
