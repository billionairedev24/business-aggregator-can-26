import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import clsx from 'clsx';
import { CaretLeft, CaretRight, CheckCircle, Info, WarningCircle, X } from '@phosphor-icons/react';
import { defineMessages, formatNumber, useLocale } from './i18n';

/**
 * Toasts and pagination in the locked E-15 direction (A · Plainsight): a toast is solid ink with white text (one line,
 * an optional action, a close), announced politely; pagination is a row of 3 px boxes with the current page solid ink.
 */
const useT = defineMessages({
  en: {
    close: 'Dismiss', notifications: 'Notifications',
    pages: 'Pages', previous: 'Previous', next: 'Next', page: 'Page {n}', current: 'Page {n}, current page',
    showing: '{from}–{to} of {total}',
  },
  fr: {
    close: 'Fermer', notifications: 'Notifications',
    pages: 'Pages', previous: 'Précédente', next: 'Suivante', page: 'Page {n}', current: 'Page {n}, page actuelle',
    showing: '{from} à {to} sur {total}',
  },
});

// ── Toast ───────────────────────────────────────────────────────────────────────────────────────────────────────

export type ToastTone = 'neutral' | 'success' | 'danger';
export interface ToastProps { tone?: ToastTone; children: ReactNode; action?: ReactNode; onClose?: () => void }

const ICON = { neutral: Info, success: CheckCircle, danger: WarningCircle } as const;

/** One toast: ink, an icon of its tone, the message, an optional action (a tertiary button), a close. */
export function Toast({ tone = 'neutral', children, action, onClose }: ToastProps) {
  const t = useT();
  const I = ICON[tone];
  return (
    <div className="nl-toast" data-tone={tone}>
      <I size={18} weight="fill" className="nl-toast-icon" aria-hidden />
      <span className="nl-toast-text">{children}</span>
      {action}
      {onClose ? <button type="button" className="btn btn-tertiary btn-icon btn-sm" aria-label={t('close')} onClick={onClose}><X size={16} aria-hidden /></button> : null}
    </div>
  );
}

interface ToastItem extends Omit<ToastProps, 'onClose'> { id: number }
interface ToastApi { show: (toast: Omit<ToastProps, 'onClose'> & { duration?: number }) => void }
const ToastContext = createContext<ToastApi | null>(null);

/**
 * The toast region of an app: `useToast().show({ children: 'Saved' })`. Toasts sit bottom centre above everything,
 * are read out politely (role="status"; danger ones assertively) and leave after 5 s (an action keeps one 10 s).
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const t = useT();
  const [items, setItems] = useState<ToastItem[]>([]);
  const next = useRef(0);
  const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>());
  const close = useCallback((id: number) => {
    setItems(list => list.filter(i => i.id !== id));
    clearTimeout(timers.current.get(id));
    timers.current.delete(id);
  }, []);
  const show = useCallback<ToastApi['show']>(({ duration, ...toast }) => {
    const id = ++next.current;
    setItems(list => [...list.slice(-2), { ...toast, id }]);
    timers.current.set(id, setTimeout(() => close(id), duration ?? (toast.action ? 10_000 : 5_000)));
  }, [close]);
  useEffect(() => { const all = timers.current; return () => all.forEach(clearTimeout); }, []);
  const api = useMemo(() => ({ show }), [show]);
  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="nl-toasts" role="region" aria-label={t('notifications')}>
        <div role="status" aria-live="polite">{items.filter(i => i.tone !== 'danger').map(i => <Toast key={i.id} {...i} onClose={() => close(i.id)} />)}</div>
        <div role="alert">{items.filter(i => i.tone === 'danger').map(i => <Toast key={i.id} {...i} onClose={() => close(i.id)} />)}</div>
      </div>
    </ToastContext.Provider>
  );
}

/** The app's toasts (a no-op outside a ToastProvider, so a component can toast in tests and stories). */
export function useToast(): ToastApi {
  return useContext(ToastContext) ?? { show: () => undefined };
}

// ── Pagination ──────────────────────────────────────────────────────────────────────────────────────────────────

/** The pages to show: the first, the last, the current one and its neighbours, "gap" between runs. */
export function pageItems(page: number, pages: number): (number | 'gap')[] {
  if (pages <= 7) return Array.from({ length: pages }, (_, i) => i + 1);
  const keep = new Set([1, pages, page - 1, page, page + 1].filter(n => n >= 1 && n <= pages));
  if (page <= 3) [2, 3, 4].forEach(n => keep.add(n));
  if (page >= pages - 2) [pages - 3, pages - 2, pages - 1].forEach(n => keep.add(n));
  const sorted = [...keep].sort((a, b) => a - b);
  return sorted.flatMap((n, i) => (i > 0 && n - sorted[i - 1]! > 1 ? ['gap' as const, n] : [n]));
}

export interface PaginationProps {
  /** 1-based. */
  page: number;
  pages: number;
  onChange: (page: number) => void;
  /** "1–24 of 86" under the row: the items on this page and the total. */
  range?: { from: number; to: number; total: number };
  className?: string;
}

/** Pages as 3 px boxes (the current one solid ink, aria-current="page"), Previous / Next at the ends. */
export function Pagination({ page, pages, onChange, range, className }: PaginationProps) {
  const t = useT();
  const { locale } = useLocale();
  if (pages <= 1 && !range) return null;
  const n = (v: number) => formatNumber(v, locale);
  return (
    <nav className={clsx('nl-pager', className)} aria-label={t('pages')}>
      <button type="button" className="nl-pager-item" disabled={page <= 1} onClick={() => onChange(page - 1)}><CaretLeft size={14} aria-hidden />{t('previous')}</button>
      {pageItems(page, pages).map((p, i) => p === 'gap'
        ? <span key={`gap-${i}`} className="nl-pager-gap" aria-hidden>…</span>
        : <button key={p} type="button" className="nl-pager-item" aria-current={p === page ? 'page' : undefined} aria-label={t(p === page ? 'current' : 'page', { n: p })} onClick={() => p !== page && onChange(p)}>{n(p)}</button>)}
      <button type="button" className="nl-pager-item" disabled={page >= pages} onClick={() => onChange(page + 1)}>{t('next')}<CaretRight size={14} aria-hidden /></button>
      {range ? <span className="nl-pager-summary">{t('showing', { from: n(range.from), to: n(range.to), total: n(range.total) })}</span> : null}
    </nav>
  );
}
