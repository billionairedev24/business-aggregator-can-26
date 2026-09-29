import { cloneElement, isValidElement, useId, type InputHTMLAttributes, type ReactElement, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import clsx from 'clsx';

export interface FieldProps {
  label: ReactNode;
  /** Muted text after the label, e.g. "· optional" or "· 3 of 10 selected". */
  note?: ReactNode;
  hint?: ReactNode;
  /** Validation message; rendered with role="alert" and wires aria-invalid on the control. */
  error?: string | null | false;
  span?: boolean;
  className?: string;
  children: ReactElement<Record<string, unknown>>;
}

/** Label + control + hint + inline error, per validation-rules.md (rosehip text, role="alert"). */
export function Field({ label, note, hint, error, span, className, children }: FieldProps) {
  const id = useId();
  const controlId = (children.props.id as string | undefined) ?? id;
  const hintId = hint ? `${controlId}-hint` : undefined;
  const errId = error ? `${controlId}-err` : undefined;
  const control = isValidElement(children)
    ? cloneElement(children, {
        id: controlId,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': [children.props['aria-describedby'], hintId, errId].filter(Boolean).join(' ') || undefined,
      })
    : children;
  return (
    <div className={clsx('nl-field', span && 'nl-span-all', className)}>
      <label className="nl-label" htmlFor={controlId}>{label}{note ? <span className="nl-label-note"> {note}</span> : null}</label>
      {control}
      {error ? <div id={errId} role="alert" className="nl-error">{error}</div> : null}
      {hint ? <div id={hintId} className="nl-hint">{hint}</div> : null}
    </div>
  );
}

export const TextInput = ({ className, ...p }: InputHTMLAttributes<HTMLInputElement>) => <input className={clsx('input', className)} {...p} />;
export const TextArea = ({ className, ...p }: TextareaHTMLAttributes<HTMLTextAreaElement>) => <textarea className={clsx('input', className)} {...p} />;

export interface SelectOption { value: string; label: string; disabled?: boolean }
export interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'children'> { options: readonly (SelectOption | string)[]; placeholder?: string }
export function Select({ options, placeholder, className, ...p }: SelectProps) {
  return (
    <select className={clsx('input', className)} {...p}>
      {placeholder !== undefined && <option value="">{placeholder}</option>}
      {options.map(o => typeof o === 'string' ? <option key={o} value={o}>{o}</option> : <option key={o.value} value={o.value} disabled={o.disabled}>{o.label}</option>)}
    </select>
  );
}

/** Grid that collapses to one column: repeat(auto-fit, minmax(min(100%, min), 1fr)). */
export function FormGrid({ min = 240, className, children, style }: { min?: number; className?: string; children: ReactNode; style?: React.CSSProperties }) {
  return <div className={clsx('nl-grid', className)} style={{ ['--nl-min' as string]: `${min}px`, ...style }}>{children}</div>;
}
