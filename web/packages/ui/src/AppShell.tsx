import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { CaretDown, CaretUp, List, SidebarSimple, X, type Icon } from '@phosphor-icons/react';
import { defineMessages } from './i18n';

const useT = defineMessages({
  en: { menu: 'Menu', collapse: 'Collapse sidebar', expand: 'Expand sidebar', open: 'Open menu', close: 'Close menu', nav: 'Main navigation', here: 'You are in this section' },
  fr: { menu: 'Menu', collapse: 'Réduire la barre latérale', expand: 'Déplier la barre latérale', open: 'Ouvrir le menu', close: 'Fermer le menu', nav: 'Navigation principale', here: 'Vous êtes dans cette section' },
});

export interface NavItem { key: string; label: string; icon: Icon; badge?: string; href: string }
export interface NavGroup { label: string; icon: Icon; items: readonly NavItem[] }
export interface AppShellProps {
  brand: ReactNode;
  /** Business name, tier tag, etc. — wraps on narrow screens. */
  headerStart?: ReactNode;
  headerEnd?: ReactNode;
  pinned: readonly NavItem[];
  groups: readonly NavGroup[];
  currentKey: string;
  onNavigate: (item: NavItem) => void;
  children: ReactNode;
  /** Persisted rail preference. */
  rail?: boolean;
  onRailChange?: (rail: boolean) => void;
}

const NARROW = '(max-width: 899.98px)';
function useNarrow() {
  const [narrow, set] = useState(() => typeof window !== 'undefined' && window.matchMedia(NARROW).matches);
  useEffect(() => { const m = window.matchMedia(NARROW); const on = () => set(m.matches); on(); m.addEventListener('change', on); return () => m.removeEventListener('change', on); }, []);
  return narrow;
}

/**
 * Studio / Console shell. Sticky top bar; light sidebar (surface + border + soft shadow) with Dashboard pinned first
 * and accordion groups (one open at a time, the group holding the current page opens by default, a dot marks it when
 * collapsed); collapse-to-rail toggle at the top; below 900px the sidebar becomes an off-canvas drawer.
 */
export function AppShell({ brand, headerStart, headerEnd, pinned, groups, currentKey, onNavigate, children, rail: railProp, onRailChange }: AppShellProps) {
  const t = useT();
  const narrow = useNarrow();
  const [railState, setRailState] = useState(false);
  const rail = railProp ?? railState;
  const setRail = (r: boolean) => { setRailState(r); onRailChange?.(r); };
  const [drawer, setDrawer] = useState(false);
  const activeGroup = groups.find(g => g.items.some(i => i.key === currentKey))?.label;
  const [openGroup, setOpenGroup] = useState<string | null | undefined>(undefined);
  useEffect(() => { setOpenGroup(undefined); }, [activeGroup]);
  const open = openGroup === undefined ? (activeGroup ?? groups[0]?.label) : openGroup;
  const headerRef = useRef<HTMLElement>(null);
  const [top, setTop] = useState(72);
  useLayoutEffect(() => {
    const el = headerRef.current; if (!el) return;
    const ro = new ResizeObserver(() => setTop(Math.round(el.getBoundingClientRect().height)));
    ro.observe(el); return () => ro.disconnect();
  }, []);
  useEffect(() => { if (!narrow) setDrawer(false); }, [narrow]);

  const go = (i: NavItem) => { setDrawer(false); onNavigate(i); };
  const itemEl = (i: NavItem, mode: 'full' | 'rail') => {
    const on = i.key === currentKey;
    const onClick = (e: React.MouseEvent) => { if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return; e.preventDefault(); go(i); };
    const I = i.icon;
    return mode === 'rail'
      ? <a key={i.key} href={i.href} onClick={onClick} className="nl-rail-item" aria-current={on ? 'page' : undefined} title={i.label} aria-label={i.label}><I size={20} weight="duotone" /></a>
      : <a key={i.key} href={i.href} onClick={onClick} className="nl-side-item" aria-current={on ? 'page' : undefined}><I size={18} weight="duotone" className="nl-side-icon" /><span className="nl-side-label">{i.label}</span>{i.badge ? <span className="nl-side-badge">{i.badge}</span> : null}</a>;
  };
  const fullNav = (
    <div className="nl-side-scroll">
      {pinned.map(i => itemEl(i, 'full'))}
      {groups.filter(g => g.items.length).map(g => {
        const isOpen = g.label === open, G = g.icon;
        return (
          <div key={g.label} className="nl-side-group">
            <button type="button" className="nl-side-head" data-active={g.label === activeGroup} aria-expanded={isOpen} onClick={() => setOpenGroup(isOpen ? null : g.label)}>
              <G size={18} weight="duotone" className="nl-side-head-icon" /><span style={{ flex: 1 }}>{g.label}</span>
              {g.label === activeGroup && !isOpen ? <span className="nl-side-dot" title={t('here')} /> : null}
              {isOpen ? <CaretUp size={13} /> : <CaretDown size={13} />}
            </button>
            {isOpen ? <div role="group" aria-label={g.label} className="nl-side-items">{g.items.map(i => itemEl(i, 'full'))}</div> : null}
          </div>
        );
      })}
    </div>
  );

  return (
    <div className="nl-shell">
      <header ref={headerRef} className="nav nl-topbar">
        {narrow ? <button type="button" className="btn btn-secondary btn-icon nl-topbar-menu" aria-label={t('open')} onClick={() => setDrawer(true)}><List size={20} weight="duotone" /></button> : null}
        {brand}
        {headerStart}
        {headerEnd ? <div className="nl-topbar-end">{headerEnd}</div> : null}
      </header>
      <div className="nl-shell-body">
        {!narrow && !rail && (
          <nav aria-label={t('nav')} className="nl-side" style={{ top, height: `calc(100vh - ${top}px)` }}>
            <div className="nl-side-top"><span className="nl-side-caption">{t('menu')}</span><button type="button" className="nl-side-toggle" aria-label={t('collapse')} title={t('collapse')} onClick={() => setRail(true)}><SidebarSimple size={20} weight="duotone" /></button></div>
            {fullNav}
          </nav>
        )}
        {!narrow && rail && (
          <nav aria-label={t('nav')} className="nl-side nl-rail" style={{ top, height: `calc(100vh - ${top}px)` }}>
            <div className="nl-rail-top"><button type="button" className="nl-side-toggle" aria-label={t('expand')} title={t('expand')} onClick={() => setRail(false)}><SidebarSimple size={20} weight="duotone" /></button></div>
            <div className="nl-rail-scroll">
              {pinned.map(i => itemEl(i, 'rail'))}
              {groups.filter(g => g.items.length).map(g => <div key={g.label} className="nl-rail-group"><span className="nl-rail-sep" />{g.items.map(i => itemEl(i, 'rail'))}</div>)}
            </div>
          </nav>
        )}
        {narrow && drawer && <>
          <div className="nl-drawer-backdrop" onClick={() => setDrawer(false)} />
          <nav aria-label={t('nav')} className="nl-side-drawer" onKeyDown={e => e.key === 'Escape' && setDrawer(false)}>
            <div className="nl-side-top">{brand}<button type="button" className="nl-side-toggle" aria-label={t('close')} onClick={() => setDrawer(false)} autoFocus><X size={18} /></button></div>
            {fullNav}
          </nav>
        </>}
        <main className="nl-main" id="main">{children}</main>
      </div>
    </div>
  );
}
