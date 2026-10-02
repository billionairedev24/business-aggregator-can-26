import { useCallback, useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { X } from '@phosphor-icons/react';
import { defineMessages } from './i18n';

const useT = defineMessages({ en: { close: 'Close' }, fr: { close: 'Fermer' } });
const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

/**
 * Focus trap + Escape + focus restore shared by Dialog and Drawer. With nothing focusable inside (a read-only notice),
 * focus goes to the dialog itself (tabIndex -1), so Tab cannot leave it for the page behind (S-109).
 */
function useModal(open: boolean, onClose: () => void) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const prev = document.activeElement as HTMLElement | null;
    const first = ref.current?.querySelector<HTMLElement>('[data-autofocus]') ?? ref.current?.querySelector<HTMLElement>(FOCUSABLE) ?? ref.current;
    first?.focus();
    const { overflow } = document.body.style;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = overflow; prev?.focus?.(); };
  }, [open]);
  const onKeyDown = useCallback((e: KeyboardEvent) => {
    if (e.key === 'Escape') { e.stopPropagation(); onClose(); return; }
    if (e.key !== 'Tab' || !ref.current) return;
    const els = [...ref.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
    if (!els.length) { e.preventDefault(); return; }
    const [a, z] = [els[0]!, els[els.length - 1]!];
    if (e.shiftKey && document.activeElement === a) { e.preventDefault(); z.focus(); }
    else if (!e.shiftKey && document.activeElement === z) { e.preventDefault(); a.focus(); }
  }, [onClose]);
  return { ref, onKeyDown };
}

export interface DialogProps { open: boolean; onClose: () => void; title: ReactNode; children?: ReactNode; actions?: ReactNode; width?: number; role?: 'dialog' | 'alertdialog' }
export function Dialog({ open, onClose, title, children, actions, width, role = 'dialog' }: DialogProps) {
  const { ref, onKeyDown } = useModal(open, onClose);
  const id = useId();
  if (!open || typeof document === 'undefined') return null;
  return createPortal(
    <div className="nl-backdrop" onMouseDown={e => e.target === e.currentTarget && onClose()}>
      <div ref={ref} role={role} aria-modal="true" aria-labelledby={id} tabIndex={-1} className="nl-dialog" style={width ? { ['--nl-dialog-w' as string]: `${width}px` } : undefined} onKeyDown={onKeyDown}>
        <h2 id={id} className="nl-dialog-title">{title}</h2>
        <div>{children}</div>
        {actions ? <div className="nl-dialog-actions">{actions}</div> : null}
      </div>
    </div>, document.body);
}

export interface DrawerProps { open: boolean; onClose: () => void; title: ReactNode; children?: ReactNode; footer?: ReactNode; side?: 'right' | 'left'; width?: number }
export function Drawer({ open, onClose, title, children, footer, side = 'right', width }: DrawerProps) {
  const { ref, onKeyDown } = useModal(open, onClose);
  const t = useT();
  const id = useId();
  if (!open || typeof document === 'undefined') return null;
  return createPortal(<>
    <div className="nl-drawer-backdrop" onClick={onClose} />
    <div ref={ref} role="dialog" aria-modal="true" aria-labelledby={id} tabIndex={-1} className="nl-drawer" data-side={side} style={width ? { ['--nl-drawer-w' as string]: `${width}px` } : undefined} onKeyDown={onKeyDown}>
      <div className="nl-drawer-head"><h2 id={id} style={{ fontSize: 22, margin: 0 }}>{title}</h2><button type="button" className="btn btn-ghost btn-icon" onClick={onClose} aria-label={t('close')}><X size={18} /></button></div>
      <div className="nl-drawer-body">{children}</div>
      {footer ? <div className="nl-drawer-foot">{footer}</div> : null}
    </div>
  </>, document.body);
}

export type MenuEntry =
  | { kind?: 'item'; label: ReactNode; meta?: ReactNode; onSelect: () => void; checked?: boolean; indent?: boolean; href?: string; target?: string }
  | { kind: 'separator' }
  | { kind: 'custom'; render: ReactNode };

export interface MenuProps { trigger: (p: { open: boolean; toggle: () => void; props: Record<string, unknown> }) => ReactNode; items: readonly MenuEntry[]; align?: 'start' | 'end'; width?: number; label: string }
/** Anchored dropdown menu: ↑/↓/Home/End move, Escape closes and returns focus to the trigger, Tab closes (S-109); outside click or selection closes. */
export function Menu({ trigger, items, align = 'end', width, label }: MenuProps) {
  const [open, setOpen] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const menuId = useId();
  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => { if (!wrap.current?.contains(e.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', onDoc);
    wrap.current?.querySelector<HTMLElement>('[role^="menuitem"]')?.focus();
    return () => document.removeEventListener('mousedown', onDoc);
  }, [open]);
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (!open) return;
    if (e.key === 'Escape') { setOpen(false); wrap.current?.querySelector<HTMLElement>('[aria-haspopup]')?.focus(); return; }
    if (e.key === 'Tab') { setOpen(false); return; }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(e.key)) return;
    e.preventDefault();
    const els = [...(wrap.current?.querySelectorAll<HTMLElement>('[role^="menuitem"]') ?? [])];
    const i = els.indexOf(document.activeElement as HTMLElement);
    const next = e.key === 'Home' ? 0 : e.key === 'End' ? els.length - 1 : (i + (e.key === 'ArrowDown' ? 1 : -1) + els.length) % els.length;
    els[next]?.focus();
  };
  const toggle = () => setOpen(o => !o);
  return (
    <div className="nl-menu-anchor" ref={wrap} onKeyDown={onKeyDown}>
      {trigger({ open, toggle, props: { 'aria-haspopup': 'menu', 'aria-expanded': open, 'aria-controls': open ? menuId : undefined, onClick: toggle } })}
      {open && (
        <div id={menuId} role="menu" aria-label={label} className="nl-menu" data-align={align} style={width ? { width } : undefined}>
          {items.map((it, i) => {
            if (it.kind === 'separator') return <div key={i} className="nl-menu-sep" role="separator" />;
            if (it.kind === 'custom') return <div key={i}>{it.render}</div>;
            const cls = `nl-menu-item${it.indent ? ' nl-menu-sub' : ''}`;
            const role = it.checked === undefined ? 'menuitem' : 'menuitemradio';
            const body = <><span>{it.label}</span>{it.meta ? <span className="nl-menu-meta">{it.meta}</span> : null}</>;
            return it.href
              ? <a key={i} role={role} aria-checked={it.checked} className={cls} href={it.href} target={it.target} rel={it.target ? 'noopener' : undefined} onClick={() => { setOpen(false); it.onSelect(); }}>{body}</a>
              : <button key={i} type="button" role={role} aria-checked={it.checked} className={cls} onClick={() => { setOpen(false); it.onSelect(); }}>{body}</button>;
          })}
        </div>
      )}
    </div>
  );
}
