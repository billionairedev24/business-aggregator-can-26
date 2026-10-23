import { useLocale } from '@northline/ui';
import { useRegions, type Regions } from './api';
import { useShellT } from './messages';
import './queues.css';

/** A screen's place: province and market ids of the region model (S-134); S-177: the top bar's scope (`usePlace`). */
export interface PlaceFilter { province?: string; market?: string }

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
