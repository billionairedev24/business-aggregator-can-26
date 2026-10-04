/**
 * Small private primitives for the Data Table. Deliberately not exported from the package root —
 * the general Dialog / Checkbox / Menu primitives live elsewhere in packages/ui.
 */
import { Check, Minus, X } from '@phosphor-icons/react';
import clsx from 'clsx';
import { useDataTableMessages } from '../messages';
import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type ButtonHTMLAttributes, type ReactNode } from 'react';

// ── Checkbox ────────────────────────────────────────────────────────────────
export interface DataTableCheckboxProps {
  checked: boolean;
  indeterminate?: boolean;
  onChange: (checked: boolean) => void;
  label: string;
  disabled?: boolean;
  className?: string;
  children?: ReactNode;
}

/** Native checkbox (keyboard, `aria-checked="mixed"`) drawn as the design's 20px box inside a 44px hit area. */
export function DataTableCheckbox({ checked, indeterminate, onChange, label, disabled, className, children }: DataTableCheckboxProps) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = !!indeterminate;
  }, [indeterminate]);
  const on = checked || !!indeterminate;
  return (
    <label className={clsx('nl-dt-check', on && 'is-on', children != null && 'has-text', className)} onClick={(e) => e.stopPropagation()}>
      <input ref={ref} type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} aria-label={children == null ? label : undefined} />
      <span className="nl-dt-check-box" aria-hidden="true">
        {indeterminate ? <Minus size={13} weight="bold" /> : <Check size={13} weight="bold" />}
      </span>
      {children}
    </label>
  );
}

// ── Chips ───────────────────────────────────────────────────────────────────
export interface ChipProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  on: boolean;
  size?: 'md' | 'sm';
}

/** Toggle chip (`aria-pressed`) — the design's pill filter / column / page-size button. */
export function Chip({ on, size = 'md', className, ...rest }: ChipProps) {
  return <button type="button" aria-pressed={on} className={clsx('nl-dt-chip', size === 'sm' && 'nl-dt-chip-sm', on && 'is-on', className)} {...rest} />;
}

export interface ChipRadioGroupProps<V extends string> {
  label: string;
  value: V | '';
  options: readonly { value: V; label: ReactNode; disabled?: boolean }[];
  onChange: (v: V) => void;
  name?: string;
  invalid?: boolean;
  describedBy?: string;
}

/** Single choice as a row of chips, built on native radios (arrow-key navigation for free). */
export function ChipRadioGroup<V extends string>({ label, value, options, onChange, name, invalid, describedBy }: ChipRadioGroupProps<V>) {
  const auto = useId();
  return (
    <div role="radiogroup" aria-label={label} aria-invalid={invalid || undefined} aria-describedby={describedBy} className="nl-dt-chips">
      {options.map((o) => (
        <label key={o.value} className={clsx('nl-dt-chip', value === o.value && 'is-on', o.disabled && 'is-disabled')}>
          <input type="radio" name={name ?? auto} value={o.value} checked={value === o.value} disabled={o.disabled} onChange={() => onChange(o.value)} />
          {o.label}
        </label>
      ))}
    </div>
  );
}

// ── Dialog ──────────────────────────────────────────────────────────────────
export interface DataTableDialogProps {
  title: string;
  onClose: () => void;
  children: ReactNode;
  /** Prevents closing (Escape / backdrop / ×) while a mutation is in flight. */
  busy?: boolean;
  wide?: boolean;
}

/** Modal built on native `<dialog>`: focus trap, Escape, inert background; restores focus on close. Mount to open. */
export function DataTableDialog({ title, onClose, children, busy, wide }: DataTableDialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const d = ref.current;
    const prev = document.activeElement as HTMLElement | null;
    if (d && !d.open) {
      if (typeof d.showModal === 'function') d.showModal();
      else d.setAttribute('open', '');
    }
    return () => {
      if (d?.open && typeof d.close === 'function') d.close();
      if (prev && prev.isConnected) prev.focus();
    };
  }, []);
  const close = () => {
    if (!busy) onClose();
  };
  return (
    <dialog
      ref={ref}
      className={clsx('nl-dt-dialog', wide && 'is-wide')}
      aria-labelledby={titleId}
      aria-busy={busy || undefined}
      onCancel={(e) => {
        e.preventDefault();
        close();
      }}
      onClick={(e) => {
        if (e.target === e.currentTarget) close();
      }}
    >
      <div className="nl-dt-dialog-inner">
        <div className="nl-dt-dialog-head">
          <h2 id={titleId} className="dialog-title">
            {title}
          </h2>
          <CloseButton onClick={close} disabled={busy} />
        </div>
        {children}
      </div>
    </dialog>
  );
}

function CloseButton(props: ButtonHTMLAttributes<HTMLButtonElement>) {
  const label = useCloseLabel();
  return (
    <button type="button" className="btn btn-ghost btn-icon nl-dt-icon-btn" aria-label={label} title={label} {...props}>
      <X size={18} aria-hidden="true" />
    </button>
  );
}

const useCloseLabel = () => useDataTableMessages()('close');

// ── Toast ───────────────────────────────────────────────────────────────────
export interface ToastState {
  text: string;
  tone: 'ok' | 'error';
  n: number;
}

export function useToast(ms = 2800) {
  const [toast, setToast] = useState<ToastState | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  const flash = useCallback(
    (text: string, tone: ToastState['tone'] = 'ok') => {
      setToast((t) => ({ text, tone, n: (t?.n ?? 0) + 1 }));
      clearTimeout(timer.current);
      timer.current = setTimeout(() => setToast(null), tone === 'error' ? ms * 2 : ms);
    },
    [ms],
  );
  return [toast, flash] as const;
}

// ── Container width ─────────────────────────────────────────────────────────
const useIsoLayoutEffect = typeof window === 'undefined' ? useEffect : useLayoutEffect;

/** Width of the element, tracked with ResizeObserver (ignores sub-7px jitter, like the design). */
export function useContainerWidth<E extends HTMLElement>(initial = 1000) {
  const ref = useRef<E>(null);
  const [width, setWidth] = useState(initial);
  useIsoLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const measure = (w: number) => setWidth((cur) => (Math.abs(Math.round(w) - cur) > 6 ? Math.round(w) : cur));
    measure(el.getBoundingClientRect().width);
    if (typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver((entries) => {
      const e = entries[0];
      if (e) measure(e.contentRect.width);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, width] as const;
}

/**
 * S-141 (WCAG 1.4.12): the column budget is an estimate from the design's text metrics; user text spacing, a larger
 * font or long words can still make the rendered table wider than its box. Measures the table after each render and
 * when it resizes, and returns how many more columns to drop for this container width (reset when the width changes).
 */
export function useOverflowSqueeze<E extends HTMLElement>(width: number) {
  const ref = useRef<E>(null);
  const [state, setState] = useState({ width, n: 0 });
  useIsoLayoutEffect(() => {
    const el = ref.current;
    const box = el?.parentElement;
    if (!el || !box) return;
    const check = () => {
      if (el.scrollWidth > box.clientWidth + 1) setState((s) => ({ width, n: (s.width === width ? s.n : 0) + 1 }));
    };
    check();
    if (typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(check);
    ro.observe(el);
    return () => ro.disconnect();
  });
  return [ref, state.width === width ? state.n : 0] as const;
}
