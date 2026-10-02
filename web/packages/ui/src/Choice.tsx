import { useEffect, useRef, type ButtonHTMLAttributes, type KeyboardEvent, type ReactNode } from 'react';
import clsx from 'clsx';
import { Check, Minus } from '@phosphor-icons/react';

export interface CheckboxProps { checked: boolean; onChange: (checked: boolean) => void; label?: ReactNode; indeterminate?: boolean; disabled?: boolean; className?: string; 'aria-label'?: string; name?: string }
export function Checkbox({ checked, onChange, label, indeterminate, disabled, className, name, ...aria }: CheckboxProps) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => { if (ref.current) ref.current.indeterminate = !!indeterminate && !checked; }, [indeterminate, checked]);
  return (
    <label className={clsx('nl-check', className)}>
      <input ref={ref} type="checkbox" name={name} checked={checked} disabled={disabled} onChange={e => onChange(e.target.checked)} aria-label={aria['aria-label']} />
      <span className="nl-box" aria-hidden>{checked ? <Check size={12} weight="bold" /> : indeterminate ? <Minus size={12} weight="bold" /> : null}</span>
      {label ? <span>{label}</span> : null}
    </label>
  );
}

export interface SwitchProps { checked: boolean; onChange: (checked: boolean) => void; label: string; disabled?: boolean }
/** role="switch" toggle used by the page builder and settings. `label` is the accessible name. */
export function Switch({ checked, onChange, label, disabled }: SwitchProps) {
  return <button type="button" role="switch" aria-checked={checked} aria-label={label} disabled={disabled} className="nl-switch" onClick={() => onChange(!checked)}><span className="nl-switch-knob" /></button>;
}

export interface OptionCardProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'title'> { selected: boolean; title: ReactNode; description?: ReactNode; trailing?: ReactNode }
/** Selectable row: MFA method pickers, business type, verification options. */
export function OptionCard({ selected, title, description, trailing, className, ...p }: OptionCardProps) {
  return (
    <button type="button" aria-pressed={selected} className={clsx('nl-option', className)} {...p}>
      <span style={{ flex: 1, minWidth: 0 }}><span className="nl-option-title">{title}</span>{description ? <span className="nl-option-desc">{description}</span> : null}</span>
      {trailing}
    </button>
  );
}

export interface ChipProps extends ButtonHTMLAttributes<HTMLButtonElement> { selected?: boolean }
// A chip inside a tablist carries aria-selected instead; aria-pressed is not allowed on role="tab".
export const Chip = ({ selected = false, className, ...p }: ChipProps) => <button type="button" aria-pressed={p.role === 'tab' ? undefined : selected} className={clsx('nl-chip', className)} {...p} />;

/**
 * ARIA tabs keyboard model (S-109): one tab stop for the row (the selected tab), ←/→ (and ↑/↓) move and select,
 * Home/End jump to the ends.
 */
function tabKeys<V extends string>(values: readonly V[], value: V, onChange: (v: V) => void) {
  return (e: KeyboardEvent<HTMLElement>) => {
    const i = values.indexOf(value);
    const next = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? (i + 1) % values.length
      : e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? (i - 1 + values.length) % values.length
        : e.key === 'Home' ? 0 : e.key === 'End' ? values.length - 1 : -1;
    if (next < 0) return;
    e.preventDefault();
    onChange(values[next]!);
    const tabs = e.currentTarget.querySelectorAll<HTMLElement>('[role="tab"]');
    tabs[next]?.focus();
  };
}

/** The selected tab is the row's tab stop; with none selected, the first one. */
const rovingIndex = <V extends string>(options: readonly { value: V }[], value: V, i: number) =>
  (options[i]!.value === value || (i === 0 && !options.some(o => o.value === value)) ? 0 : -1);

export interface ChipGroupProps<V extends string> { options: readonly { value: V; label: ReactNode }[]; value: V; onChange: (v: V) => void; 'aria-label': string }
/** Single-select chip row (settings tabs, Phone/Web preview toggle). */
export function ChipTabs<V extends string>({ options, value, onChange, ...aria }: ChipGroupProps<V>) {
  return <div className="nl-chips" role="tablist" aria-label={aria['aria-label']} onKeyDown={tabKeys(options.map(o => o.value), value, onChange)}>{options.map((o, i) => <Chip key={o.value} role="tab" aria-selected={o.value === value} tabIndex={rovingIndex(options, value, i)} selected={o.value === value} onClick={() => onChange(o.value)}>{o.label}</Chip>)}</div>;
}

export interface SegmentedProps<V extends string> { name: string; options: readonly { value: V; label: ReactNode }[]; value: V; onChange: (v: V) => void; 'aria-label'?: string }
export function Segmented<V extends string>({ name, options, value, onChange, ...aria }: SegmentedProps<V>) {
  return (
    <div className="seg" role="radiogroup" aria-label={aria['aria-label']}>
      {options.map(o => <label key={o.value} className="seg-opt"><input type="radio" name={name} checked={o.value === value} onChange={() => onChange(o.value)} /><span>{o.label}</span></label>)}
    </div>
  );
}

export interface UnderlineTabsProps<V extends string> { options: readonly { value: V; label: ReactNode }[]; value: V; onChange: (v: V) => void; 'aria-label': string }
export function UnderlineTabs<V extends string>({ options, value, onChange, ...aria }: UnderlineTabsProps<V>) {
  return <div className="nl-utabs" role="tablist" aria-label={aria['aria-label']} onKeyDown={tabKeys(options.map(o => o.value), value, onChange)}>{options.map((o, i) => <button key={o.value} type="button" role="tab" aria-selected={o.value === value} tabIndex={rovingIndex(options, value, i)} className="nl-utab" onClick={() => onChange(o.value)}>{o.label}</button>)}</div>;
}

/** Thin progress bars (auth: 3 steps). */
export const StepBars = ({ total, done, label }: { total: number; done: number; label?: string }) => (
  <div className="nl-steps" role="progressbar" aria-valuemin={0} aria-valuemax={total} aria-valuenow={done + 1} aria-label={label}>{Array.from({ length: total }, (_, i) => <span key={i} data-done={i <= done} />)}</div>
);
