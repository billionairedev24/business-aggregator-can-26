import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { CaretDown, type Icon } from '@phosphor-icons/react';
import { defineMessages } from './i18n';
import { SiteLink } from './SiteLink';

const useT = defineMessages({ en: { open: 'Account menu' }, fr: { open: 'Menu du compte' } });

export interface AccountMenuItem {
  key: string;
  label: string;
  icon: Icon;
  /** Right-hand value ("3 active", "Visa ··4471"). */
  value?: string;
  href?: string;
  onSelect?: () => void;
}
export interface AccountMenuSection { heading?: string; items: readonly AccountMenuItem[] }
export interface AccountMenuProps {
  user: { name: string; initials: string; detail?: string };
  /** A button next to the name (the design's "Add photo"). */
  headerAction?: { label: string; href: string };
  /** Shown under the name (points and Plus status). */
  card?: ReactNode;
  sections: readonly AccountMenuSection[];
}

/**
 * The consumer header's account menu (design 06): avatar button → panel with the person, an optional card, grouped
 * items with a Phosphor duotone icon and a value. Orders & bookings belong here, never in the top navigation.
 * Arrow keys move between items, Escape closes and returns focus, clicking outside closes.
 */
export function AccountMenu({ user, headerAction, card, sections }: AccountMenuProps) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const id = useId();
  useEffect(() => {
    if (!open) return;
    const outside = (e: MouseEvent) => { if (!wrap.current?.contains(e.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', outside);
    wrap.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    return () => document.removeEventListener('mousedown', outside);
  }, [open]);
  const close = () => { setOpen(false); button.current?.focus(); };
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Escape' && open) { e.preventDefault(); close(); return; }
    if (!open || !['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(e.key)) return;
    e.preventDefault();
    const items = [...(wrap.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])];
    const i = items.indexOf(document.activeElement as HTMLElement);
    const next = e.key === 'Home' ? 0 : e.key === 'End' ? items.length - 1 : (i + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
    items[next]?.focus();
  };
  return (
    <div className="nl-account" ref={wrap} onKeyDown={onKeyDown}>
      <button ref={button} type="button" className="nl-account-button" aria-label={t('open')} aria-haspopup="menu" aria-expanded={open}
        aria-controls={open ? id : undefined} onClick={() => setOpen(o => !o)}>
        <span className="nl-account-avatar" aria-hidden>{user.initials}</span>
        <CaretDown size={13} aria-hidden />
      </button>
      {open && (
        <div id={id} role="menu" aria-label={t('open')} className="nl-account-panel">
          <div className="nl-account-head" role="none">
            <span className="nl-account-avatar nl-account-avatar-lg" aria-hidden>{user.initials}</span>
            <span className="nl-account-who">
              <span className="nl-account-name">{user.name}</span>
              {user.detail && <span className="nl-account-detail">{user.detail}</span>}
            </span>
            {headerAction && <SiteLink role="menuitem" href={headerAction.href} className="btn btn-ghost nl-account-photo" onClick={() => setOpen(false)}>{headerAction.label}</SiteLink>}
          </div>
          {card && <div className="nl-account-card" role="none">{card}</div>}
          {sections.map((section, s) => (
            <div key={s} role="group" aria-label={section.heading || undefined}>
              {section.heading !== undefined && <div className="nl-account-heading" role="presentation">{section.heading}</div>}
              {section.items.map(item => {
                const body = (
                  <>
                    <span className="nl-account-label"><item.icon weight="duotone" size={20} className="nl-account-icon" aria-hidden />{item.label}</span>
                    {item.value && <span className="nl-account-value">{item.value}</span>}
                  </>
                );
                const select = () => { setOpen(false); item.onSelect?.(); };
                return item.href
                  ? <SiteLink key={item.key} role="menuitem" href={item.href} className="nl-account-item" onClick={select}>{body}</SiteLink>
                  : <button key={item.key} type="button" role="menuitem" className="nl-account-item" onClick={select}>{body}</button>;
              })}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
