import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { useLocale, WorkspaceSwitcher, type MenuEntry } from '@northline/ui';
import { useRegions, type Regions } from './api';
import { useShellT, type ShellT } from './messages';

/** The console's scope (S-177): every market, one province, or one market of it — ids from the region model. */
export interface Scope { province?: string; market?: string }

const KEY = 'nl.console.scope';
const read = (): Scope => {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) ?? '{}') as Scope;
    return { province: typeof v.province === 'string' ? v.province : undefined, market: typeof v.market === 'string' ? v.market : undefined };
  } catch { return {}; }
};
const write = (s: Scope) => { try { localStorage.setItem(KEY, JSON.stringify(s)); } catch { /* private mode: the scope lasts for the visit */ } };

const ScopeContext = createContext<{ scope: Scope; setScope: (s: Scope) => void }>({ scope: {}, setScope: () => {} });

/** Holds the scope for the shell; the viewer's last choice is remembered on this device (a convenience only). */
export function ScopeProvider({ children }: { children: ReactNode }) {
  const [scope, set] = useState<Scope>(read);
  const setScope = useCallback((s: Scope) => { set(s); write(s); }, []);
  const value = useMemo(() => ({ scope, setScope }), [scope, setScope]);
  return <ScopeContext.Provider value={value}>{children}</ScopeContext.Provider>;
}

export const useScope = () => useContext(ScopeContext);

/**
 * The place a screen filters by: a link's own `?province=&market=` (a deep link, a KPI that opens a view) wins, else
 * the top bar's scope. Screens send it to the api as before; no screen draws its own province and market selects.
 */
export function usePlace(): Scope {
  const search = useSearch({ strict: false }) as Scope;
  const { scope } = useScope();
  if (search.province || search.market) return { province: search.province, market: search.market };
  return { province: scope.province, market: scope.market };
}

/** The scope's name and place for the top bar: "Calgary · Alberta", "Alberta · every market", "All markets · Alberta + BC pilot". */
export function scopeLabel(scope: Scope, regions: Regions | undefined, t: ShellT): { name: string; meta: string } {
  const market = scope.market ? regions?.markets.find(m => m.id === scope.market) : undefined;
  const province = regions?.provinces.find(p => p.code === (market?.province ?? scope.province));
  if (market) return { name: market.city, meta: province?.name ?? market.province };
  if (province) return { name: province.name, meta: t('everyMarket') };
  const live = (regions?.provinces ?? []).filter(p => p.status === 'live').map(p => p.name);
  const pilot = (regions?.provinces ?? []).filter(p => p.status === 'pilot').map(p => p.code);
  return { name: t('allMarkets'), meta: [...live, ...(pilot.length ? [t('pilotIn', { codes: pilot.join(', ') })] : [])].join(' + ') };
}

/**
 * The top bar's market / province scope (design/revamp/a.html `bo-biz`: "Calgary Alberta ▾"): all markets, then each
 * open province with its markets indented, from the region model. Choosing one rescopes every screen; a screen's own
 * `?province=&market=` is dropped so the new scope shows at once.
 */
export function ScopeSwitcher() {
  const t = useShellT();
  const { locale } = useLocale();
  const regions = useRegions(locale).data;
  const { scope, setScope } = useScope();
  const navigate = useNavigate();
  const choose = (next: Scope) => {
    setScope(next);
    void navigate({ to: '.', search: ((prev: Record<string, unknown>) => ({ ...prev, province: undefined, market: undefined })) as never, replace: true });
  };
  const provinces = (regions?.provinces ?? []).filter(p => p.status !== 'off');
  const items: MenuEntry[] = [
    { label: t('allMarkets'), checked: !scope.province && !scope.market, onSelect: () => choose({}) },
    ...provinces.flatMap((p): MenuEntry[] => [
      { label: p.name, meta: p.status === 'live' ? undefined : t(`launch_${p.status}` as Parameters<ShellT>[0]), checked: scope.province === p.code && !scope.market, onSelect: () => choose({ province: p.code }) },
      ...(regions?.markets ?? []).filter(m => m.province === p.code && m.status !== 'off')
        .map((m): MenuEntry => ({ label: m.city, indent: true, checked: scope.market === m.id, onSelect: () => choose({ province: p.code, market: m.id }) })),
    ]),
  ];
  const { name, meta } = scopeLabel(scope, regions, t);
  return <WorkspaceSwitcher label={t('scopeLabel')} name={name} meta={meta} items={items} />;
}

/** The place a screen shows, by name ("Calgary", "Alberta", "All markets"), for its head's sub line. */
export function usePlaceName(): string {
  const t = useShellT();
  const { locale } = useLocale();
  const regions = useRegions(locale).data;
  return scopeLabel(usePlace(), regions, t).name;
}
