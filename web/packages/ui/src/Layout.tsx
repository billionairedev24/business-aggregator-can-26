import { createContext, useContext, useEffect, useId, useLayoutEffect, useRef, useState, type ButtonHTMLAttributes, type CSSProperties, type ReactNode } from 'react';
import clsx from 'clsx';
import { WarningCircle } from '@phosphor-icons/react';
import { defineMessages } from './i18n';

const useT = defineMessages({ en: { retry: 'Retry', loading: 'Loading…' }, fr: { retry: 'Réessayer', loading: 'Chargement…' } });

export const Panel = ({ children, tight, className, style, as: As = 'div', ...rest }: { children: ReactNode; tight?: boolean; className?: string; style?: CSSProperties; as?: 'div' | 'section' | 'article'; 'aria-label'?: string }) =>
  <As className={clsx('nl-panel', tight && 'nl-panel-tight', className)} style={style} {...rest}>{children}</As>;

export interface PageHeaderProps { kicker?: ReactNode; title: ReactNode; lede?: ReactNode; actions?: ReactNode }
/** Kicker (uppercase, 13px) + serif h1 + optional actions — the header of every Studio screen. */
export function PageHeader({ kicker, title, lede, actions }: PageHeaderProps) {
  return (
    <div className="nl-page-head">
      <div style={{ minWidth: 0 }}>
        {kicker ? <span className="nl-kicker">{kicker}</span> : null}
        <h1 className="nl-page-title">{title}</h1>
        {lede ? <p className="nl-lede">{lede}</p> : null}
      </div>
      {actions ? <div className="nl-page-actions">{actions}</div> : null}
    </div>
  );
}

const PageHeadingContext = createContext<string | undefined>(undefined);
/**
 * The current screen's name (the shell's nav label), for loading and error states that replace a whole screen (S-142,
 * WCAG 1.3.1 / 2.4.6): `PageSkeleton` and `ErrorState` then show it as the page's h1 when the page has none.
 */
export const PageHeadingProvider = PageHeadingContext.Provider;

const useIsoLayoutEffect = typeof window === 'undefined' ? useEffect : useLayoutEffect;
const CLAIM = 'data-nl-fallback-h1';

/**
 * The screen's h1 while the screen itself cannot show its own (loading, failed): only when a PageHeadingProvider names
 * the screen and nothing else in the page's `main` is an h1 — an error state inside a tab under the screen's heading
 * adds none. Decided before paint (a layout effect); one per page (the first to claim `main`).
 */
function FallbackHeading({ placeholder }: { placeholder?: ReactNode }) {
  const title = useContext(PageHeadingContext);
  const probe = useRef<HTMLSpanElement>(null);
  const [show, setShow] = useState(false);
  useIsoLayoutEffect(() => {
    const main = probe.current?.closest('main') ?? probe.current?.ownerDocument.body;
    if (!title || !main || main.querySelector('h1') || main.hasAttribute(CLAIM)) return;
    main.setAttribute(CLAIM, '');
    setShow(true);
    return () => main.removeAttribute(CLAIM);
  }, [title]);
  if (show && title) return <h1 className="nl-page-title">{title}</h1>;
  return <><span ref={probe} hidden />{placeholder}</>;
}

/**
 * A whole-page loading or error state outside the app shell (a route's pendingComponent / errorComponent): a `main`
 * whose `PageSkeleton` / `ErrorState` show `title` as the page's h1 (S-142).
 */
export const PageState = ({ title, children }: { title: string; children: ReactNode }) =>
  <main id="main" style={{ padding: 32 }}><PageHeadingProvider value={title}>{children}</PageHeadingProvider></main>;

export const Kpi = ({ value, label }: { value: ReactNode; label: ReactNode }) => <div><div className="nl-kpi-value">{value}</div><div className="nl-kpi-label">{label}</div></div>;
export const KpiRow = ({ children }: { children: ReactNode }) => <div className="nl-kpis">{children}</div>;

export type AlertTone = 'info' | 'error' | 'highlight' | 'neutral';
export function Alert({ tone = 'info', title, children, actions, role }: { tone?: AlertTone; title?: ReactNode; children?: ReactNode; actions?: ReactNode; role?: 'alert' | 'status' }) {
  return <div className={`nl-alert nl-alert-${tone}`} role={role ?? (tone === 'error' ? 'alert' : undefined)}>{title ? <strong>{title} </strong> : null}{children}{actions ? <div className="nl-alert-actions">{actions}</div> : null}</div>;
}

export interface LinkRowProps extends ButtonHTMLAttributes<HTMLButtonElement> { children: ReactNode }
/** Full-width actionable row with a trailing arrow ("Needs you" list). */
export const LinkRow = ({ children, className, ...p }: LinkRowProps) => <button type="button" className={clsx('nl-linkrow', className)} {...p}><span>{children}</span><span className="nl-linkrow-arrow" aria-hidden>→</span></button>;

/** A 0–100 bar. The visible label names it (aria-labelledby), whatever the label's markup (S-109: a non-string label left it unnamed). */
export function Meter({ label, value, display, floor = 0 }: { label: ReactNode; value: number; display?: ReactNode; floor?: number }) {
  const pct = Math.max(0, Math.min(100, value));
  const id = useId();
  return (
    <div className="nl-meter">
      <span className="nl-meter-label" id={`${id}-label`}>{label}</span>
      <div className="nl-meter-track" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-labelledby={`${id}-label`}
        aria-valuetext={typeof display === 'string' || typeof display === 'number' ? String(display) : undefined}><div className="nl-meter-fill" data-low={pct < floor} style={{ width: `${pct}%` }} /></div>
      <span className="nl-meter-value">{display ?? pct}</span>
    </div>
  );
}

export const Avatar = ({ initials, size = 34, tone = 'accent' }: { initials: string; size?: number; tone?: 'accent' | 'dark' }) =>
  <span className="nl-avatar" aria-hidden style={{ width: size, height: size, fontSize: Math.round(size * 0.38), background: tone === 'dark' ? 'var(--color-accent-700)' : undefined }}>{initials}</span>;

export const Skeleton = ({ width = '100%', height = 14, radius, style }: { width?: number | string; height?: number | string; radius?: number | string; style?: CSSProperties }) =>
  <span className="nl-skel" aria-hidden style={{ display: 'block', width, height, borderRadius: radius, ...style }} />;

/** Loading placeholder shaped like a page: header + KPI row + two columns of rows. */
export function PageSkeleton({ kpis = 4, rows = 5 }: { kpis?: number; rows?: number }) {
  const t = useT();
  return (
    <div aria-busy="true" aria-live="polite">
      <span className="nl-sr-only">{t('loading')}</span>
      <Skeleton width={180} height={12} style={{ marginBottom: 14 }} />
      <FallbackHeading placeholder={<Skeleton width="min(420px, 80%)" height={38} style={{ marginBottom: 28 }} />} />
      {kpis > 0 && <div className="nl-kpis" style={{ marginBottom: 36 }}>{Array.from({ length: kpis }, (_, i) => <div key={i}><Skeleton width="60%" height={34} /><Skeleton width="80%" height={12} style={{ marginTop: 8 }} /></div>)}</div>}
      {Array.from({ length: rows }, (_, i) => <Skeleton key={i} height={44} style={{ marginBottom: 8 }} />)}
    </div>
  );
}

export function EmptyState({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return <div className="nl-empty"><span>{children}</span>{action}</div>;
}

export function ErrorState({ message, onRetry }: { message: ReactNode; onRetry?: () => void }) {
  const t = useT();
  return (
    <>
      <FallbackHeading />
      <div className="nl-errorstate" role="alert">
        <span style={{ display: 'flex', gap: 8, alignItems: 'center' }}><WarningCircle size={18} weight="duotone" aria-hidden />{message}</span>
        {onRetry ? <button type="button" className="btn btn-secondary" onClick={onRetry}>{t('retry')}</button> : null}
      </div>
    </>
  );
}
