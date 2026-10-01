import { useNavigate } from '@tanstack/react-router';
import { Select, useLocale } from '@northline/ui';
import { useRegions, type Regions } from './api';
import { useShellT } from './messages';
import './queues.css';

export interface PlaceFilter { province?: string; market?: string }

/**
 * Province and market from the region model (S-134), written to the screen's `?province=&market=` (S-91 overview,
 * S-79… queues). Place names come from the region model, never from code.
 */
export function PlaceFilters({ to, filter, extra }: { to: string; filter: PlaceFilter; extra?: Record<string, unknown> }) {
  const t = useShellT();
  const { locale } = useLocale();
  const regions = useRegions(locale).data;
  const navigate = useNavigate();
  const provinces = (regions?.provinces ?? []).filter(p => p.status !== 'off');
  const markets = (regions?.markets ?? []).filter(m => m.status !== 'off' && (!filter.province || m.province === filter.province));
  const go = (next: PlaceFilter) => void navigate({ to, search: { ...extra, province: next.province || undefined, market: next.market || undefined } as never });
  return (
    <div className="nl-q-filters">
      <Select aria-label={t('filterProvince')} value={filter.province ?? ''} placeholder={t('allProvinces')}
        options={provinces.map(p => ({ value: p.code, label: p.name }))} onChange={e => go({ province: e.target.value })} />
      <Select aria-label={t('filterMarket')} value={filter.market ?? ''} placeholder={t('allMarkets')}
        options={markets.map(m => ({ value: m.id, label: m.city }))} onChange={e => go({ province: filter.province, market: e.target.value })} />
    </div>
  );
}

/** "Alberta", "BC pilot": a province as the console names it (live by name, pilot by code), from the region model. */
export function useRegionName(): (code: string | null | undefined) => string {
  const t = useShellT();
  const { locale } = useLocale();
  const regions: Regions | undefined = useRegions(locale).data;
  return code => {
    if (!code) return '—';
    const p = regions?.provinces.find(x => x.code === code);
    if (!p) return code;
    return p.status === 'pilot' ? t('regionPilot', { code: p.code }) : p.name;
  };
}
