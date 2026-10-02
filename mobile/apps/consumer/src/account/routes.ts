import type { ActivityItem } from '../api/account';
import { config } from '../config';

/**
 * Where a row of Orders & bookings opens. Journey D links into the other journeys' screens by route and rebuilds none
 * of them (MOBILE_PLAN § D):
 *   - an open case → the case (`/cases/<number>`, the S-102 deep link's path);
 *   - "Something's wrong" → B's report form (`/problem/<kind>/<id>`, orders, food and bookings);
 *   - a shop order → B's tracking, or "Delivered · confirm" once delivered;
 *   - a booking → C's screens: the day-of ETA while it is on, sign-off once the job is completed, the review once it
 *     is done, the provider to re-book;
 *   - a quote → the quote (`/quotes/<id>`), when the request has one open quote.
 * What the app has no screen for (a food order, comparing several quotes) opens the consumer site in the browser.
 */
export type Target = { route: string } | { site: string } | null;

export function targetOf(item: ActivityItem): Target {
  if (item.caseRef?.open) return { route: `/cases/${encodeURIComponent(item.caseRef.number)}` };
  if (item.action === 'report' && (item.kind === 'order' || item.kind === 'food' || item.kind === 'booking')) {
    return { route: `/problem/${item.kind}/${encodeURIComponent(item.id)}` };
  }
  const id = encodeURIComponent(item.id);
  switch (item.kind) {
    case 'order':
      return { route: item.status === 'delivered' ? `/orders/${id}/delivered` : `/orders/${id}/track` };
    case 'food':
      return { site: `${config.siteOrigin}/food/orders/${id}` };
    case 'booking': {
      const slug = /^\/providers\/([^/?]+)$/.exec(item.href ?? '')?.[1];
      if (item.action === 'rebook' && slug) return { route: `/providers/${slug}` };
      if (item.status === 'completed') return { route: `/bookings/${id}/sign-off` };
      if (item.status === 'done') return { route: `/bookings/${id}/review` };
      return { route: `/bookings/${id}/eta` };
    }
    case 'quote': {
      const quote = /^\/quotes\/(?!requests\/)([^/?]+)$/.exec(item.href ?? '')?.[1];
      if (quote) return { route: `/quotes/${quote}` };
      return item.href ? { site: `${config.siteOrigin}${item.href}` } : null;
    }
    default:
      return null;
  }
}
