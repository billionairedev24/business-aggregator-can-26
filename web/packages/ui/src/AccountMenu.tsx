import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import clsx from 'clsx';
import { CaretDown, User, type Icon } from '@phosphor-icons/react';
import { defineMessages } from './i18n';
import { SiteLink } from './SiteLink';

const useT = defineMessages({ en: { open: 'Account menu', guest: 'Welcome, guest' }, fr: { open: 'Menu du compte', guest: 'Bienvenue' } });

export interface AccountMenuItem {
  key: string;
  label: string;
  icon?: Icon;
  /** Right-hand value ("3 active", "Visa ··4471"). */
  value?: string;
  /** The label's language when it is in the other one (the "Français" / "English" switch, WCAG 3.1.2). */
  lang?: string;
  href?: string;
  onSelect?: () => void;
}
export interface AccountMenuSection { heading?: string; items: readonly AccountMenuItem[] }
/**
 * S-192 (Amazon's account flyout, signed out): the primary "Sign in" button at the top and, under it, "New customer?
 * Start here." (`prompt` then the link).
 */
export interface AccountMenuSignIn { label: string; href: string; prompt?: string; start?: { label: string; href: string } }
export interface AccountMenuProps {
  /** The person signed in; absent for a guest (a grey avatar, "Welcome, guest", Sign in / Create account items). */
  user?: { name: string; initials: string; detail?: string } | null;
  /** A button next to the name (the design's "Add photo"). */
  headerAction?: { label: string; href: string };
  /** Shown under the name (points and Plus status). */
  card?: ReactNode;
  sections: readonly AccountMenuSection[];
  /**
   * The mockups' trigger (E-15): two lines, "Hello, Amara" over "Account & orders ▾", in place of the avatar; on phones
   * the user icon alone, the two lines then read by screen readers only. The visible words name the button.
   */
  greeting?: { line1: string; line2: string };
  /** S-192: signed out, the flyout's "Sign in" button and "New customer? Start here." above the columns. */
  signIn?: AccountMenuSignIn;
  /**
   * S-192: `columns` lays the sections side by side (Amazon's "Your lists" | "Your account"; one column under 600 px),
   * each with its heading; `stack` (default) one under the other.
   */
  layout?: 'stack' | 'columns';
  /** S-192: the flyout closes whenever this changes (the consumer passes the route, so a navigation closes it). */
  closeKey?: unknown;
}

/** Hover intent (S-192): open after the pointer rests this long on the trigger, close this long after it leaves both. */
const HOVER_OPEN_MS = 150;
const HOVER_CLOSE_MS = 250;
const canHover = () => typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(hover: hover) and (pointer: fine)').matches;
const useIsoLayoutEffect = typeof window === 'undefined' ? useEffect : useLayoutEffect;

/**
 * The consumer header's account menu (AfroTransact Shop:60, redesign § 1.3; S-192 Amazon's flyout). The trigger is a
 * 36px avatar or the two-line greeting. The panel is drawn in a portal on <body>, fixed under the trigger and above
 * everything (the sticky header and its department strip included — S-192: inside the header the strip and the header's
 * clip hid it): the person (avatar, name, email) or, signed out, the "Sign in" button and "New customer? Start here.",
 * an optional card, then the sections — stacked, or side by side with `layout="columns"`. Orders, bookings and returns
 * live here only, never in the top navigation. A click opens it (focus to its first item); on a desktop pointer it also
 * opens on hover after a short pause and closes when the pointer has left. Arrow keys move between items; Escape, a
 * click outside, choosing an item or `closeKey` changing close it, and focus returns to the trigger.
 */
export function AccountMenu({ user, headerAction, card, sections, greeting, signIn, layout = 'stack', closeKey }: AccountMenuProps) {
  const t = useT();
  const [open, setOpen] = useState<false | 'click' | 'hover'>(false);
  const wrap = useRef<HTMLDivElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [pos, setPos] = useState<{ top: number; right: number } | null>(null);
  const id = useId();
  const clearTimer = () => { if (timer.current) { clearTimeout(timer.current); timer.current = null; } };

  const close = useCallback((focusTrigger: boolean) => {
    clearTimer();
    setOpen(false);
    if (focusTrigger) button.current?.focus();
  }, []);

  // where the panel hangs: under the trigger, its right edge on the trigger's (never past the window's gutter)
  const place = useCallback(() => {
    const r = button.current?.getBoundingClientRect();
    if (!r) return;
    const gutter = Math.min(16, window.innerWidth * 0.04);
    setPos({ top: Math.round(r.bottom + 4), right: Math.round(Math.max(gutter, window.innerWidth - r.right)) });
  }, []);
  useIsoLayoutEffect(() => {
    if (!open) return;
    place();
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    return () => { window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true); };
  }, [open, place]);

  useEffect(() => {
    if (!open) return;
    const outside = (e: globalThis.PointerEvent) => {
      const target = e.target as Node;
      if (!wrap.current?.contains(target) && !panel.current?.contains(target)) close(false);
    };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [open, close]);
  // a click or a key opened it: focus its first item (a hover only shows it, focus stays where it is)
  useEffect(() => {
    if (open === 'click') panel.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
  }, [open]);
  // a navigation (or anything the caller keys on) closes it; focus inside the panel goes back to the trigger
  const firstKey = useRef(true);
  useEffect(() => {
    if (firstKey.current) { firstKey.current = false; return; }
    const inside = !!panel.current?.contains(document.activeElement);
    close(inside);
  }, [closeKey]); // eslint-disable-line react-hooks/exhaustive-deps -- only on a change of closeKey
  useEffect(() => clearTimer, []);

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Escape' && open) { e.preventDefault(); e.stopPropagation(); close(true); return; }
    if (!open || !['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(e.key)) return;
    e.preventDefault();
    const items = [...(panel.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])];
    const i = items.indexOf(document.activeElement as HTMLElement);
    const next = e.key === 'Home' ? 0 : e.key === 'End' ? items.length - 1 : (i + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
    items[next]?.focus();
  };
  // hover intent, desktop pointers only (a finger's tap is a click)
  const enter = (e: PointerEvent) => {
    if (e.pointerType !== 'mouse' || !canHover()) return;
    clearTimer();
    if (!open) timer.current = setTimeout(() => setOpen('hover'), HOVER_OPEN_MS);
  };
  const leave = (e: PointerEvent) => {
    if (e.pointerType !== 'mouse' || open !== 'hover') { if (!open) clearTimer(); return; }
    clearTimer();
    timer.current = setTimeout(() => setOpen(o => (o === 'hover' ? false : o)), HOVER_CLOSE_MS);
  };
  const toggle = () => {
    clearTimer();
    // shown by a hover: the click keeps it open (and gives it the focus); opened by a click: the click closes it
    setOpen(o => (o === 'click' ? false : 'click'));
  };

  const item = (it: AccountMenuItem) => {
    const body = (
      <>
        <span className="nl-account-label">{it.icon ? <it.icon size={20} className="nl-account-icon" aria-hidden /> : null}<span lang={it.lang}>{it.label}</span></span>
        {it.value && <span className="nl-account-value">{it.value}</span>}
      </>
    );
    const select = () => { close(!it.href); it.onSelect?.(); };
    return it.href
      ? <SiteLink key={it.key} role="menuitem" href={it.href} className="nl-account-item" onClick={select}>{body}</SiteLink>
      : <button key={it.key} type="button" role="menuitem" className="nl-account-item" onClick={select}>{body}</button>;
  };

  const flyout = open && typeof document !== 'undefined' ? createPortal(
    <div id={id} ref={panel} role="menu" aria-label={t('open')} className={clsx('nl-account-panel', layout === 'columns' && 'nl-account-panel-columns')}
      style={pos ? { ['--nl-flyout-top' as string]: `${pos.top}px`, ['--nl-flyout-right' as string]: `${pos.right}px` } : undefined}
      onPointerEnter={enter} onPointerLeave={leave}>
      {/* its key presses reach the wrapper's onKeyDown through the React tree (a portal keeps its parent there) */}
      {signIn ? (
        <div className="nl-account-signin" role="none">
          <SiteLink role="menuitem" href={signIn.href} className="btn btn-primary nl-account-signin-btn" onClick={() => close(false)}>{signIn.label}</SiteLink>
          {signIn.start ? (
            <p className="nl-account-start">{signIn.prompt ? <>{signIn.prompt} </> : null}<SiteLink role="menuitem" href={signIn.start.href} onClick={() => close(false)}>{signIn.start.label}</SiteLink></p>
          ) : null}
        </div>
      ) : (
        <div className="nl-account-head" role="none">
          {user ? <span className="nl-account-avatar nl-account-avatar-lg" aria-hidden>{user.initials}</span> : <span className="nl-account-avatar nl-account-avatar-lg nl-account-guest" aria-hidden><User size={24} /></span>}
          <span className="nl-account-who">
            <span className="nl-account-name">{user ? user.name : t('guest')}</span>
            {user?.detail && <span className="nl-account-detail">{user.detail}</span>}
          </span>
          {headerAction && <SiteLink role="menuitem" href={headerAction.href} className="btn btn-ghost nl-account-photo" onClick={() => close(false)}>{headerAction.label}</SiteLink>}
        </div>
      )}
      {card && <div className="nl-account-card" role="none">{card}</div>}
      <div className="nl-account-sections" role="none">
        {sections.map((section, s) => (
          <div key={s} role="group" className="nl-account-section" aria-label={section.heading || undefined}>
            {section.heading !== undefined && <div className="nl-account-heading" role="presentation">{section.heading}</div>}
            {section.items.map(item)}
          </div>
        ))}
      </div>
    </div>,
    document.body,
  ) : null;

  return (
    <div className="nl-account" ref={wrap} onKeyDown={onKeyDown} onPointerEnter={enter} onPointerLeave={leave}>
      <button ref={button} type="button" className={clsx('nl-account-button', greeting && 'nl-account-button-greeting')} aria-label={greeting ? undefined : t('open')} aria-haspopup="menu" aria-expanded={!!open}
        aria-controls={open ? id : undefined} onClick={toggle}>
        {greeting ? (
          <>
            <User size={26} className="nl-account-icon-phone" aria-hidden />
            <span className="nl-account-greeting">
              <span className="nl-account-line1">{greeting.line1}</span>{' '}
              <span className="nl-account-line2">{greeting.line2}<CaretDown size={13} aria-hidden /></span>
            </span>
          </>
        ) : (
          <>
            {user ? <span className="nl-account-avatar" aria-hidden>{user.initials}</span> : <span className="nl-account-avatar nl-account-guest" aria-hidden><User size={20} /></span>}
            <CaretDown size={13} aria-hidden />
          </>
        )}
      </button>
      {flyout}
    </div>
  );
}
