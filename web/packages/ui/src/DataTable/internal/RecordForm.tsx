import { WarningCircle } from '@phosphor-icons/react';
import { revalidateLogic, useForm } from '@tanstack/react-form';
import { useId, useMemo, useState } from 'react';
import { z } from 'zod';
import type { DataTableT } from '../messages';
import { facetOptions, isBlank, valueOf, type NormalizedColumn } from '../model';
import { ChipRadioGroup } from './primitives';

/** One editable input generated from a column (a column with `sub` yields two). */
export interface FieldSpec {
  name: string;
  label: string;
  required: boolean;
  kind: 'choice' | 'number' | 'text';
  /** How to convert the input back: money → cents, num → number. */
  numeric: 'money' | 'num' | null;
  options: string[];
}

/** Derives the form fields from column definitions (editable / required / type / options / sub). */
export function buildFieldSpecs<T>(columns: readonly NormalizedColumn<T>[], rows: readonly T[], detailLabel: (c: NormalizedColumn<T>) => string): FieldSpec[] {
  const out: FieldSpec[] = [];
  columns
    .filter((c) => c.editable)
    .forEach((c) => {
      const options = c.options ? [...c.options] : c.type === 'tag' ? facetOptions(c, rows).filter((v) => v !== '—') : [];
      const numericData = c.type === 'money' || (c.type === 'num' && rows.some((r) => typeof valueOf(r, c.key) === 'number'));
      out.push({
        name: c.key,
        label: c.label,
        required: c.primary || !!c.required,
        kind: options.length ? 'choice' : numericData ? 'number' : 'text',
        numeric: options.length || !numericData ? null : c.type === 'money' ? 'money' : 'num',
        options,
      });
      if (c.sub) out.push({ name: c.sub, label: c.subLabel ?? detailLabel(c), required: false, kind: 'text', numeric: null, options: [] });
    });
  return out;
}

const toNumber = (s: string) => Number(s.trim().replace(/\s/g, '').replace(',', '.'));

export function initialValues<T>(fields: readonly FieldSpec[], row: T | null): Record<string, string> {
  return Object.fromEntries(
    fields.map((f) => {
      if (!row) return [f.name, f.kind === 'choice' ? (f.options[0] ?? '') : ''];
      const v = valueOf(row, f.name);
      if (isBlank(v)) return [f.name, ''];
      if (f.numeric === 'money' && typeof v === 'number') return [f.name, (v / 100).toFixed(2)];
      return [f.name, String(v)];
    }),
  );
}

/** Form strings → typed values (money dollars → cents, numbers → number, blanks → null). */
export function convertValues(fields: readonly FieldSpec[], values: Record<string, string>): Record<string, unknown> {
  return Object.fromEntries(
    fields.map((f) => {
      const raw = (values[f.name] ?? '').trim();
      if (f.numeric) {
        if (raw === '') return [f.name, null];
        const n = toNumber(raw);
        return [f.name, f.numeric === 'money' ? Math.round(n * 100) : n];
      }
      return [f.name, raw];
    }),
  );
}

export function buildSchema(fields: readonly FieldSpec[], t: DataTableT) {
  return z.object(
    Object.fromEntries(
      fields.map((f) => {
        let s = z.string();
        if (f.required) s = s.refine((v) => v.trim() !== '', t('required', { label: f.label }));
        if (f.numeric) s = s.refine((v) => v.trim() === '' || Number.isFinite(toNumber(v)), t('notNumber'));
        return [f.name, s];
      }),
    ),
  );
}

const errorText = (e: unknown): string => (typeof e === 'string' ? e : e && typeof e === 'object' && 'message' in e ? String((e as { message: unknown }).message) : '');

export interface RecordFormProps {
  fields: readonly FieldSpec[];
  initial: Record<string, string>;
  submitLabel: string;
  t: DataTableT;
  onCancel: () => void;
  onSubmit: (values: Record<string, unknown>) => Promise<void>;
  onBusyChange: (busy: boolean) => void;
}

export function RecordForm({ fields, initial, submitLabel, t, onCancel, onSubmit, onBusyChange }: RecordFormProps) {
  const schema = useMemo(() => buildSchema(fields, t), [fields, t]);
  const [submitError, setSubmitError] = useState('');
  const uid = useId();
  const [defaults] = useState(initial);
  const form = useForm({
    defaultValues: defaults,
    validationLogic: revalidateLogic({ mode: 'submit', modeAfterSubmission: 'change' }),
    validators: { onDynamic: schema },
    onSubmit: async ({ value }) => {
      setSubmitError('');
      onBusyChange(true);
      try {
        await onSubmit(convertValues(fields, value));
      } catch (e) {
        setSubmitError(errorText(e) || t('genericError'));
      } finally {
        onBusyChange(false);
      }
    },
  });

  return (
    <form
      noValidate
      className="nl-dt-form"
      onSubmit={(e) => {
        e.preventDefault();
        e.stopPropagation();
        void form.handleSubmit();
      }}
    >
      <div className="nl-dt-form-grid">
        {fields.map((f) => (
          <form.Field key={f.name} name={f.name}>
            {(field) => {
              const id = `${uid}-${f.name}`;
              const errs = field.state.meta.errors.map(errorText).filter(Boolean);
              const err = errs[0];
              const errId = err ? `${id}-err` : undefined;
              return (
                <div className="field nl-dt-field">
                  {f.kind === 'choice' ? (
                    <span className="nl-dt-field-label" id={`${id}-label`}>
                      {f.label}
                      {f.required && <RequiredMark t={t} />}
                    </span>
                  ) : (
                    <label htmlFor={id}>
                      {f.label}
                      {f.required && <RequiredMark t={t} />}
                    </label>
                  )}
                  {f.kind === 'choice' ? (
                    <ChipRadioGroup
                      label={f.label}
                      name={id}
                      value={field.state.value}
                      options={f.options.map((o) => ({ value: o, label: o }))}
                      onChange={(v) => field.handleChange(v)}
                      invalid={!!err}
                      describedBy={errId}
                    />
                  ) : (
                    <input
                      id={id}
                      className="input nl-dt-input"
                      inputMode={f.kind === 'number' ? 'decimal' : undefined}
                      value={field.state.value}
                      onChange={(e) => field.handleChange(e.target.value)}
                      onBlur={field.handleBlur}
                      aria-invalid={!!err}
                      aria-required={f.required || undefined}
                      aria-describedby={errId}
                    />
                  )}
                  {err && (
                    <div id={errId} className="nl-dt-field-error">
                      {err}
                    </div>
                  )}
                </div>
              );
            }}
          </form.Field>
        ))}
      </div>
      {submitError && (
        <div className="nl-dt-inline-error" role="alert">
          <WarningCircle size={16} weight="duotone" aria-hidden="true" />
          {submitError}
        </div>
      )}
      <form.Subscribe selector={(s) => s.isSubmitting}>
        {(submitting) => (
          <div className="dialog-actions nl-dt-dialog-actions">
            <button type="button" className="btn btn-secondary" onClick={onCancel} disabled={submitting}>
              {t('cancel')}
            </button>
            <button type="submit" className="btn btn-primary" disabled={submitting} aria-busy={submitting || undefined}>
              {submitting ? t('saving') : submitLabel}
            </button>
          </div>
        )}
      </form.Subscribe>
    </form>
  );
}

function RequiredMark({ t }: { t: DataTableT }) {
  return (
    <span className="nl-dt-req">
      <span aria-hidden="true"> *</span>
      <span className="nl-dt-sr"> ({t('requiredMark')})</span>
    </span>
  );
}
