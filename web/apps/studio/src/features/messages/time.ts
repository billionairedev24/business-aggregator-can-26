import { useCallback } from 'react';
import { defineMessages, formatDate, useLocale, timeZone } from '@northline/ui';

/** Compact relative times used by the design: "now", "1 h", "Yesterday", "2 d", "1 w" (older → "Sep 5"). */
const useT = defineMessages({
  en: { now: 'now', min: '{n} min', h: '{n} h', yesterday: 'Yesterday', d: '{n} d', w: '{n} w' },
  fr: { now: 'maintenant', min: '{n} min', h: '{n} h', yesterday: 'Hier', d: '{n} j', w: '{n} sem.' },
});

const dayKey = (d: Date) => new Intl.DateTimeFormat('en-CA', { timeZone: timeZone(), year: 'numeric', month: '2-digit', day: '2-digit' }).format(d);

export type Ago = { unit: 'now' | 'min' | 'h' | 'yesterday' | 'd' | 'w' | 'date'; n: number };

export function agoParts(iso: string, now: number = Date.now()): Ago {
  const at = new Date(iso).getTime();
  const min = Math.max(0, Math.floor((now - at) / 60_000));
  if (min < 1) return { unit: 'now', n: 0 };
  if (min < 60) return { unit: 'min', n: min };
  const h = Math.floor(min / 60);
  const yesterday = dayKey(new Date(now - 86_400_000)) === dayKey(new Date(at));
  if (h < 24 && !yesterday) return { unit: 'h', n: h };
  if (yesterday) return { unit: 'yesterday', n: 1 };
  const d = Math.max(1, Math.round(min / 1440));
  if (d < 7) return { unit: 'd', n: d };
  if (d < 56) return { unit: 'w', n: Math.floor(d / 7) };
  return { unit: 'date', n: 0 };
}

export function useAgo() {
  const t = useT();
  const { locale } = useLocale();
  return useCallback((iso: string | null | undefined, now?: number) => {
    if (!iso) return '';
    const p = agoParts(iso, now);
    return p.unit === 'date' ? formatDate(iso, locale, 'date') : t(p.unit, { n: p.n });
  }, [t, locale]);
}
