import { formatNumber, useLocale } from '@northline/ui';
import { useShellT } from './messages';

/** "12 min", "4 h", "1.2 d" since an instant (design 03 queues' "Waiting" / "Age"); `now` defaults to the clock. */
export function useAge(): (since: string | null | undefined, now?: number) => string {
  const t = useShellT();
  const { locale } = useLocale();
  return (since, now = Date.now()) => {
    if (!since) return '—';
    const minutes = Math.max(0, (now - Date.parse(since)) / 60_000);
    if (minutes < 60) return t('ageMin', { n: Math.round(minutes) });
    if (minutes < 24 * 60) return t('ageH', { n: Math.round(minutes / 60) });
    return t('ageD', { n: formatNumber(minutes / 1440, locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) });
  };
}
