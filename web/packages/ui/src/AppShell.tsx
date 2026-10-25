import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { CaretDown, List, SidebarSimple, X, type Icon } from '@phosphor-icons/react';
import { defineMessages } from './i18n';
import { SkipLink, useStickyOffset } from './A11y';
import { Menu, type MenuEntry } from './Overlay';

const useT = defineMessages({
  en: { collapse: 'Collapse sidebar', expand: 'Expand sidebar', open: 'Open menu', close: 'Close menu', nav: 'Main navigation', badge: '{label}, {badge}' },
  fr: { collapse: 'Réduire la barre latérale', expand: 'Déplier la barre latérale', open: 'Ouvrir le menu', close: 'Fermer le menu', nav: 'Navigation principale', badge: '{label}, {badge}' },
});

export interface NavItem { key: string; label: string; icon: Icon; badge?: string; href: string }
export interface NavGroup { label: string; icon: Icon; items: readonly NavItem[] }
export interface AppShellProps {
  brand: ReactNode;
  /** The workspace control next to the logo (a business or a region: `WorkspaceSwitcher`). In the menu on phones. */
  headerStart?: ReactNode;
  /** The global search (`ShellSearch`), right-aligned before `headerEnd`. In the menu on phones. */
  search?: ReactNode;
  headerEnd?: ReactNode;
  pinned: readonly NavItem[];
  groups: readonly NavGroup[];
  currentKey: string;
  onNavigate: (item: NavItem) => void;
  children: ReactNode;
  /** Persisted rail preference (desktops). */
  rail?: boolean;
  onRailChange?: (rail: boolean) => void;
}

/** The back-office breakpoints: phones < 768px (a disclosure menu), tablets 768–1023px (the icon rail), desktops ≥ 1024px. */
const NARROW = '(max-width: 767.98px)';
const MEDIUM = '(max-width: 1023.98px)';
function useMedia(query: string) {
  const [hit, set] = useState(() => typeof window !== 'undefined' && window.matchMedia(query).matches);
  useEffect(() => { const m = window.matchMedia(query); const on = () => set(m.matches); on(); m.addEventListener('change', on); return () => m.removeEventListener('change', on); }, [query]);
  return hit;
}

/**
 * Studio / Console shell (E-15 A · Plainsight, design/revamp/a.html screens 7–9): one white top bar (logo and portal
 * name, the workspace switcher, the global search, then the account controls) over a white sidebar whose groups are
 * caps headings, every item always visible, the current one on the grey tint with an ink bar.
 * - ≥ 1024px: the full sidebar, which the person may collapse to the rail (remembered through `rail`).
 * - 768–1023px: the 72px icon rail (labels as tooltips and accessible names); its toggle expands it for the moment.
 * - < 768px: a disclosure menu under the bar's menu button — the workspace switcher, the search and the navigation in
 *   one panel. Not modal: Escape or choosing an item closes it and focus goes back to the button; Tab out of it
 *   closes it too (S-180).
 * The top bar's height is published as `--nl-shell-top` (and as scroll padding, WCAG 2.4.11).
 */
export function AppShell({ brand, headerStart, search, headerEnd, pinned, groups, currentKey, onNavigate, children, rail: railProp, onRailChange }: AppShellProps) {
  const t = useT();
  const narrow = useMedia(NARROW);
  const medium = useMedia(MEDIUM) && !narrow;
  const [railState, setRailState] = useState(false);
  // tablets start on the rail and expand it for the moment; desktops keep the person's choice
  const [mediumOpen, setMediumOpen] = useState(false);
  useEffect(() => { if (!medium) setMediumOpen(false); }, [medium]);
  const rail = medium ? !mediumOpen : (railProp ?? railState);
  const setRail = (r: boolean) => { if (medium) { setMediumOpen(!r); return; } setRailState(r); onRailChange?.(r); };
  const [menu, setMenu] = useState(false);
  useEffect(() => { if (!narrow) setMenu(false); }, [narrow]);
  const menuId = useId();
  const menuButton = useRef<HTMLButtonElement>(null);
  const shellRef = useRef<HTMLDivElement>(null);
  const headerRef = useRef<HTMLElement>(null);
  useStickyOffset(headerRef, h => shellRef.current?.style.setProperty('--nl-shell-top', `${h}px`));
  const closeMenu = (refocus: boolean) => { setMenu(false); if (refocus) menuButton.current?.focus(); };

  const go = (i: NavItem) => { setMenu(false); setMediumOpen(false); onNavigate(i); };
  const named = (i: NavItem) => (i.badge ? t('badge', { label: i.label, badge: i.badge }) : i.label);
  const itemEl = (i: NavItem, mode: 'full' | 'rail') => {
    const on = i.key === currentKey;
    const onClick = (e: React.MouseEvent) => { if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return; e.preventDefault(); go(i); };
    const I = i.icon;
    return (
      <li key={i.key}>
        {mode === 'rail'
          ? <a href={i.href} onClick={onClick} className="nl-rail-item" aria-current={on ? 'page' : undefined} title={i.label} aria-label={named(i)}><I size={20} weight={on ? 'fill' : 'regular'} aria-hidden />{i.badge ? <span className="nl-rail-dot" aria-hidden /> : null}</a>
          : <a href={i.href} onClick={onClick} className="nl-side-item" aria-current={on ? 'page' : undefined}><I size={18} weight={on ? 'fill' : 'regular'} className="nl-side-icon" aria-hidden /><span className="nl-side-label">{i.label}</span>{i.badge ? <span className="nl-side-badge">{i.badge}</span> : null}</a>}
      </li>
    );
  };
  const shown = groups.filter(g => g.items.length);
  const fullNav = (prefix: string) => (
    <>
      {pinned.length ? <ul className="nl-side-list">{pinned.map(i => itemEl(i, 'full'))}</ul> : null}
      {shown.map((g, n) => (
        <div key={g.label} className="nl-side-group">
          <span className="nl-side-head" id={`${prefix}-g${n}`}>{g.label}</span>
          <ul className="nl-side-list" aria-labelledby={`${prefix}-g${n}`}>{g.items.map(i => itemEl(i, 'full'))}</ul>
        </div>
      ))}
    </>
  );

  return (
    <div className="nl-shell" ref={shellRef}>
      <SkipLink />
      <header ref={headerRef} className="nav nl-topbar">
        {narrow ? (
          <button ref={menuButton} type="button" className="nl-topbar-menu" aria-label={menu ? t('close') : t('open')} aria-expanded={menu} aria-controls={menuId} onClick={() => setMenu(m => !m)}>
            {menu ? <X size={22} aria-hidden /> : <List size={22} aria-hidden />}
          </button>
        ) : null}
        <span className="nl-topbar-brand">{brand}</span>
        {!narrow && headerStart ? <div className="nl-topbar-start">{headerStart}</div> : null}
        {!narrow && search ? <div className="nl-topbar-search">{search}</div> : null}
        {headerEnd ? <div className="nl-topbar-end">{headerEnd}</div> : null}
      </header>
      {narrow ? (
        <div id={menuId} className="nl-shell-menu" hidden={!menu} onKeyDown={e => { if (e.key === 'Escape') { e.stopPropagation(); closeMenu(true); } }}
          // S-180 (WCAG 2.4.11): Tab past the panel's last link closes it, so focus never sits on the page hidden under it
          onBlur={e => { const to = e.relatedTarget as Node | null; if (to && !e.currentTarget.contains(to) && to !== menuButton.current) closeMenu(false); }}>
          {headerStart || search ? <div className="nl-shell-menu-tools">{headerStart}{search}</div> : null}
          <nav aria-label={t('nav')} className="nl-shell-menu-nav">{fullNav('m')}</nav>
        </div>
      ) : null}
      {narrow && menu ? <div className="nl-shell-menu-backdrop" aria-hidden onClick={() => closeMenu(false)} /> : null}
      <div className="nl-shell-body">
        {!narrow && !rail && (<div className="nl-side-col">
          <nav aria-label={t('nav')} className="nl-side">
            <div className="nl-side-scroll">{fullNav('s')}</div>
            <div className="nl-side-foot"><button type="button" className="nl-side-toggle" aria-label={t('collapse')} title={t('collapse')} onClick={() => setRail(true)}><SidebarSimple size={20} aria-hidden /></button></div>
          </nav>
        </div>)}
        {!narrow && rail && (<div className="nl-side-col nl-rail-col">
          <nav aria-label={t('nav')} className="nl-side nl-rail">
            <div className="nl-rail-top"><button type="button" className="nl-side-toggle" aria-label={t('expand')} title={t('expand')} onClick={() => setRail(false)}><SidebarSimple size={20} aria-hidden /></button></div>
            <div className="nl-rail-scroll">
              {pinned.length ? <ul className="nl-rail-list">{pinned.map(i => itemEl(i, 'rail'))}</ul> : null}
              {shown.map(g => <ul key={g.label} className="nl-rail-list nl-rail-group" aria-label={g.label}>{g.items.map(i => itemEl(i, 'rail'))}</ul>)}
            </div>
          </nav>
        </div>)}
        <main className="nl-main" id="main" tabIndex={-1}>{children}</main>
      </div>
    </div>
  );
}

export interface WorkspaceSwitcherProps {
  /** What the control does ("Switch business"): its menu's name and the start of the button's accessible name. */
  label: string;
  /** The current workspace ("Prairie Wrench Parts") and its place ("Calgary"). */
  name: string;
  meta?: string | null;
  items: readonly MenuEntry[];
}

/**
 * The top bar's workspace control (design/revamp/a.html `bo-biz`): the business (or region) and its place in a bordered
 * box with a caret, opening a menu of the others (the current one checked). On phones it sits in the shell's menu.
 */
export function WorkspaceSwitcher({ label, name, meta, items }: WorkspaceSwitcherProps) {
  return (
    <Menu label={label} align="start" width={300} items={items} trigger={({ props }) => (
      <button type="button" className="nl-switcher" aria-label={`${label}: ${[name, meta].filter(Boolean).join(', ')}`} {...props}>
        <strong className="nl-switcher-name">{name}</strong>
        {meta ? <span className="nl-switcher-meta">{meta}</span> : null}
        <CaretDown size={14} aria-hidden className="nl-switcher-caret" />
      </button>
    )} />
  );
}
