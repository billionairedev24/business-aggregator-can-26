import { useEffect } from 'react';
import { useQueryClient, type QueryKey } from '@tanstack/react-query';
import { z } from 'zod';

/**
 * The courier part of a customer's tracking (S-88): who is bringing it, where they are, when they'll be at the door and
 * the drop-off PIN. Goods (`/me/orders/{id}`) and food (`/me/food-orders/{id}`) answer the same shape.
 */
export const CourierProgress = z.object({
  state: z.string(), runLabel: z.string().nullish(), courierName: z.string().nullish(), eta: z.string().nullish(),
  stopsBefore: z.number().int(), lat: z.number().nullish(), lng: z.number().nullish(), positionAt: z.string().nullish(),
  pin: z.string().nullish(),
});
export type CourierProgress = z.infer<typeof CourierProgress>;

/**
 * Live tracking over server-sent events (through the consumer-bff): every `event` puts the parsed JSON into the query
 * cache. The browser reconnects on its own; while the stream is down the query's own polling keeps the page current.
 */
export function useTrackingStream<T>(url: string, event: string, schema: z.ZodType<T>, queryKey: QueryKey, enabled: boolean) {
  const qc = useQueryClient();
  const key = JSON.stringify(queryKey);
  useEffect(() => {
    if (!enabled || typeof EventSource === 'undefined') return;
    const source = new EventSource(url, { withCredentials: true });
    source.addEventListener(event, e => {
      const parsed = schema.safeParse(JSON.parse((e as MessageEvent<string>).data));
      if (parsed.success) qc.setQueryData(JSON.parse(key) as QueryKey, parsed.data);
    });
    return () => source.close();
  }, [url, event, schema, key, enabled, qc]);
}
