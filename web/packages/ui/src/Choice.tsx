import { useEffect, useLayoutEffect, useRef, type ButtonHTMLAttributes, type HTMLAttributes, type KeyboardEvent, type ReactNode } from 'react';
import clsx from 'clsx';
import { Check, Minus } from '@phosphor-icons/react';
import { defineMessages } from './i18n';

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
/**
 * Selectable row: MFA method pickers, business type, verification options. A toggle button (aria-pressed) by default;
 * given a `role` (radio in a radiogroup, option…) it states `aria-checked` instead — aria-pressed is not allowed there
 * (S-109: axe critical aria-allowed-attr on the sign-up factor picker).
 */
export function OptionCard({ selected, title, description, trailing, className, ...p }: OptionCardProps) {
  const checkable = p.role === 'radio' || p.role === 'menuitemradio' || p.role === 'checkbox' || p.role === 'switch';
  return (
    <button type="button" aria-pressed={p.role ? undefined : selected} aria-checked={checkable ? selected : undefined} className={clsx('nl-option', className)} {...p}>
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
export function tablistKeyDown<V extends string>(values: readonly V[], value: V, onChange: (v: V) => void) {
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
export const rovingIndex = <V extends string>(options: readonly { value: V }[], value: V, i: number) =>
  (options[i]!.value === value || (i === 0 && !options.some(o => o.value === value)) ? 0 : -1);

export interface ChipGroupProps<V extends string> { options: readonly { value: V; label: ReactNode }[]; value: V; onChange: (v: V) => void; 'aria-label': string }
/** Single-select chip row (settings tabs, Phone/Web preview toggle). */
export function ChipTabs<V extends string>({ options, value, onChange, ...aria }: ChipGroupProps<V>) {
  return <div className="nl-chips" role="tablist" aria-label={aria['aria-label']} onKeyDown={tablistKeyDown(options.map(o => o.value), value, onChange)}>{options.map((o, i) => <Chip key={o.value} role="tab" aria-selected={o.value === value} tabIndex={rovingIndex(options, value, i)} selected={o.value === value} onClick={() => onChange(o.value)}>{o.label}</Chip>)}</div>;
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
  return <div className="nl-utabs" role="tablist" aria-label={aria['aria-label']} onKeyDown={tablistKeyDown(options.map(o => o.value), value, onChange)}>{options.map((o, i) => <button key={o.value} type="button" role="tab" aria-selected={o.value === value} tabIndex={rovingIndex(options, value, i)} className="nl-utab" onClick={() => onChange(o.value)}>{o.label}</button>)}</div>;
}

const useIsoLayoutEffect = typeof window === 'undefined' ? useEffect : useLayoutEffect;

/** The group's own radios (not those of a group nested inside it). */
const radiosOf = (group: HTMLElement) =>
  [...group.querySelectorAll<HTMLElement>('[role="radio"]')].filter(r => r.closest('[role="radiogroup"]') === group);
const usable = (r: HTMLElement) => !(r as HTMLButtonElement).disabled && r.getAttribute('aria-disabled') !== 'true';

export interface RadioGroupProps extends HTMLAttributes<HTMLDivElement> {
  /**
   * The ARIA radio pattern selects as the arrows move (default). `false` for a group whose radios start an action
   * (a confirmation dialog, a save): the arrows then only move focus and Space selects.
   */
  selectOnMove?: boolean;
}

/**
 * `role="radiogroup"` for radios that are not native inputs — OptionCards, chips, swatches (`role="radio"` +
 * `aria-checked`). S-140 (WCAG 2.1.1): one Tab stop per group (the checked radio, else the first usable one),
 * ←/→/↑/↓ move and select (wrapping), Home/End jump to the ends. The children keep their own onClick; moving clicks
 * the radio, so a screen needs nothing but this wrapper. Native radio inputs (Segmented) do all this themselves.
 */
export function RadioGroup({ selectOnMove = true, onKeyDown, children, ...p }: RadioGroupProps) {
  const ref = useRef<HTMLDivElement>(null);
  const sync = () => {
    const group = ref.current;
    if (!group) return;
    const radios = radiosOf(group);
    const stop = radios.find(r => r.getAttribute('aria-checked') === 'true' && usable(r)) ?? radios.find(usable);
    for (const r of radios) { const want = r === stop ? '0' : '-1'; if (r.getAttribute('tabindex') !== want) r.setAttribute('tabindex', want); }
  };
  useIsoLayoutEffect(sync);
  useEffect(() => {
    const group = ref.current;
    if (!group || typeof MutationObserver === 'undefined') return;
    // a radio's own re-render (aria-checked, disabled) does not re-render the group
    const observer = new MutationObserver(sync);
    observer.observe(group, { subtree: true, childList: true, attributes: true, attributeFilter: ['aria-checked', 'disabled', 'aria-disabled'] });
    return () => observer.disconnect();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const keyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    onKeyDown?.(e);
    const group = ref.current;
    const from = (e.target as HTMLElement).closest<HTMLElement>('[role="radio"]');
    if (e.defaultPrevented || !group || !from || e.altKey || e.ctrlKey || e.metaKey) return;
    const radios = radiosOf(group).filter(r => r === from || usable(r));
    const i = radios.indexOf(from);
    if (i < 0) return;
    const step = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : 0;
    const next = step ? radios[(i + step + radios.length) % radios.length] : e.key === 'Home' ? radios[0] : e.key === 'End' ? radios[radios.length - 1] : undefined;
    if (!next) return;
    e.preventDefault();
    if (next === from) return;
    for (const r of radios) r.setAttribute('tabindex', r === next ? '0' : '-1');
    next.focus();
    if (selectOnMove && next.getAttribute('aria-checked') !== 'true') next.click();
  };
  return <div ref={ref} role="radiogroup" {...p} onKeyDown={keyDown}>{children}</div>;
}

const useStepT = defineMessages({ en: { step: 'Step {n} of {total}' }, fr: { step: 'Étape {n} sur {total}' } });

/**
 * Thin progress bars (auth: 3 steps). S-144 (WCAG 1.4.11): the steps still to do are neutral-600 (≥ 3:1 on the page and
 * on panels; they were neutral-300 at 1.4:1), and the bar reads "Step 2 of 3" to screen readers.
 */
export const StepBars = ({ total, done, label }: { total: number; done: number; label?: string }) => {
  const t = useStepT();
  return (
    <div className="nl-steps" role="progressbar" aria-valuemin={0} aria-valuemax={total} aria-valuenow={done + 1} aria-valuetext={t('step', { n: done + 1, total })} aria-label={label}>
      {Array.from({ length: total }, (_, i) => <span key={i} data-done={i <= done} />)}
    </div>
  );
};
