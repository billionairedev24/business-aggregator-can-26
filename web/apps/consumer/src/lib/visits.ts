import { useEffect } from 'react';
import { http } from '@northline/client';

/**
 * S-75: tells the api that someone opened a business's public page, once per tab session (sessionStorage — no cookie,
 * nothing that follows the visitor elsewhere). The api keeps only a daily count. Failures are ignored.
 */
export function useStorefrontVisit(slug: string | undefined) {
  useEffect(() => {
    if (!slug) return;
    const key = `nl.visit.${slug}`;
    try {
      if (sessionStorage.getItem(key)) return;
      sessionStorage.setItem(key, '1');
    } catch { /* storage blocked: still count this view */ }
    http(`/api/v1/public/storefronts/${encodeURIComponent(slug)}/visits`, { method: 'POST' }).catch(() => undefined);
  }, [slug]);
}
