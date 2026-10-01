import { Select, useLocale } from '@northline/ui';
import { useShellT } from '../shell/messages';
import { useRegions } from '../shell/api';

export interface Place { province?: string; market?: string }

/**
 * Province and market from the region model (S-134), as on the overview (S-91): place names come from the model, never
 * from code. `onChange` gets the new pair (a province change clears the market).
 */
export function PlaceSelect({ filter, onChange }: { filter: Place; onChange: (next: Place) => void }) {
  const t = useShellT();
  const { locale } = useLocale();
  const regions = useRegions(locale).data;
  const provinces = (regions?.provinces ?? []).filter(p => p.status !== 'off');
  const markets = (regions?.markets ?? []).filter(m => m.status !== 'off' && (!filter.province || m.province === filter.province));
  return (
    <div className="nl-or-filters">
      <Select aria-label={t('filterProvince')} value={filter.province ?? ''} placeholder={t('allProvinces')}
        options={provinces.map(p => ({ value: p.code, label: p.name }))} onChange={e => onChange({ province: e.target.value || undefined, market: undefined })} />
      <Select aria-label={t('filterMarket')} value={filter.market ?? ''} placeholder={t('allMarkets')}
        options={markets.map(m => ({ value: m.id, label: m.city }))} onChange={e => onChange({ province: filter.province, market: e.target.value || undefined })} />
    </div>
  );
}
