import { useState } from 'react';
import { useRegions } from '../shell/place';
import { Field, FileButton, Select, TextInput, useLocale } from '@northline/ui';
import { uploadDocument } from '../storefront/api';
import { ValidationError } from '../../lib/http';
import type { LegalFieldDef, LegalFormDef, PrincipalRole } from './api';
import { useOnboardingT } from './messages';
import { OWNERS, fieldHelp, fieldLabel, fieldPlaceholder, isRequired, requirementNote, toLegalDetails, type LegalValues, type OwnerRow } from './legal';

export interface LegalBlockProps {
  merchantId: string;
  /** The structure's legal-details form in the business's province (served by the api). */
  form: LegalFormDef;
  values: LegalValues;
  onChange: (values: LegalValues) => void;
  /** Uploaded document names by id (for "{file} · replace"). */
  documents: Record<string, string>;
  onDocument: (id: string, fileName: string) => void;
  owners: OwnerRow[];
  onOwners: (rows: OwnerRow[]) => void;
  errors: Record<string, string | undefined>;
  onTouch: (field: string) => void;
}

/**
 * "Legal details · <structure>" with the fields of the served form and the owners / principals table (design 02
 * `bsDefs`). Labels, formats, help and the registry are the province's (region data); the generic copy fills the rest.
 */
export function LegalBlock({ merchantId, form, values, onChange, documents, onDocument, owners, onOwners, errors, onTouch }: LegalBlockProps) {
  const t = useOnboardingT();
  const { locale } = useLocale();
  const regions = useRegions();
  const structure = form.structure;
  const provinceName = (code: string) => regions?.provinces.find(p => p.code === code)?.name ?? code;
  const [uploading, setUploading] = useState<string | null>(null);
  const [uploadError, setUploadError] = useState<Record<string, string>>({});
  const spec = OWNERS[structure];
  const set = (key: string, v: LegalValues[string]) => onChange({ ...values, [key]: v });
  const err = (key: string) => errors[`legalDetails.${key}`];
  const details = toLegalDetails(form, values);
  const registry = (locale === 'fr' ? form.registry?.fr : form.registry?.en) ?? t('legalRegistryNone');
  const law = locale === 'fr' ? form.law?.fr : form.law?.en;
  const label = (f: LegalFieldDef) => fieldLabel(f, structure, t, locale);
  const note = (f: LegalFieldDef) => (isRequired(f, details) ? t('req_required') : requirementNote(f, t));
  const optionLabel = (f: LegalFieldDef, value: string) => {
    if (f.kind === 'yes_no') return t(value === 'true' ? 'lf_annual_yes' : 'lf_annual_no');
    return /^[A-Z]{2}$/.test(value) ? provinceName(value) : t(`jur_${value}` as Parameters<typeof t>[0]);
  };

  const upload = async (key: string, file: File) => {
    setUploading(key);
    setUploadError(e => ({ ...e, [key]: '' }));
    try {
      const doc = await uploadDocument(merchantId, file, 'legal');
      onDocument(doc.id, doc.fileName);
      set(key, doc.id);
    } catch (e) {
      setUploadError(x => ({ ...x, [key]: e instanceof ValidationError ? e.errors[0]?.message ?? t('saveError') : t('saveError') }));
    } finally { setUploading(null); }
  };

  const textField = (f: LegalFieldDef, value: string, onValue: (v: string) => void, errorKey: string, span?: boolean, withNote = true) => (
    <Field key={errorKey} label={label(f)} note={withNote ? note(f) : undefined} hint={fieldHelp(f, locale)} error={err(errorKey)} span={span}>
      <TextInput
        value={value}
        placeholder={fieldPlaceholder(f, t)}
        inputMode={f.pattern && /^\^?\\d/.test(f.pattern) ? 'numeric' : undefined}
        autoCapitalize={f.compact ? 'characters' : undefined}
        onChange={e => onValue(e.target.value)}
        onBlur={() => onTouch(`legalDetails.${errorKey}`)}
      />
    </Field>
  );

  return (
    <section className="nl-ob-legal" aria-labelledby="nl-ob-legal-title">
      <div className="nl-ob-legal-head">
        <strong id="nl-ob-legal-title" style={{ fontSize: 15 }}>{t('legalDetails', { name: t(`bs_${structure}_name`) })}</strong>
        {law ? <span className="nl-small nl-muted">{law}</span> : null}
      </div>
      <p className="nl-ob-legal-why">
        {t(`bs_${structure}_why`, { registry })}
        {structure === 'sole' && form.tradeNameRegistration ? <> {t('bs_sole_trade', { registry })}</> : null}
      </p>
      <p className="nl-small nl-muted nl-ob-legal-registry">
        {form.registryUrl ? <a href={form.registryUrl} target="_blank" rel="noopener noreferrer">{t('legalRegistry', { registry })}</a> : t('legalRegistry', { registry })}
      </p>
      <div className="nl-grid" style={{ ['--nl-min' as string]: '220px' }}>
        {form.fields.map(f => {
          if (f.kind === 'group') {
            const g = (values[f.key] && typeof values[f.key] === 'object' ? values[f.key] : {}) as Record<string, string>;
            return (
              <fieldset key={f.key} className="nl-span-all nl-ob-group" aria-describedby={fieldHelp(f, locale) ? `nl-ob-${f.key}-help` : undefined}>
                <legend className="nl-label">{label(f)} <span className="nl-label-note">{note(f)}</span></legend>
                {fieldHelp(f, locale) ? <div id={`nl-ob-${f.key}-help`} className="nl-hint">{fieldHelp(f, locale)}</div> : null}
                <div className="nl-grid" style={{ ['--nl-min' as string]: '220px' }}>
                  {/* the parts carry a note only while the group is required; otherwise the legend's note says it */}
                  {f.parts.map(p => textField({ ...p, required: p.required }, g[p.key] ?? '', v => set(f.key, { ...g, [p.key]: v }), `${f.key}.${p.key}`, false, isRequired(f, details)))}
                </div>
                {err(f.key) ? <div role="alert" className="nl-error">{err(f.key)}</div> : null}
              </fieldset>
            );
          }
          if (f.kind === 'document') {
            const id = typeof values[f.key] === 'string' ? (values[f.key] as string) : undefined;
            const e = uploadError[f.key] || err(f.key);
            return (
              <div key={f.key} className="nl-field">
                <span className="nl-label">{label(f)} <span className="nl-label-note">{note(f)}</span></span>
                <FileButton className="nl-ob-doc-btn" accept="application/pdf,image/png,image/jpeg" pending={uploading === f.key} aria-invalid={!!e} onFile={file => void upload(f.key, file)}>
                  {uploading === f.key ? t('uploading') : id && documents[id] ? t('uploaded', { file: documents[id]! }) : t('upload', { what: fieldPlaceholder(f, t) ?? t('lf_pdf') })}
                </FileButton>
                {fieldHelp(f, locale) ? <div className="nl-hint">{fieldHelp(f, locale)}</div> : null}
                {e ? <div role="alert" className="nl-error">{e}</div> : null}
              </div>
            );
          }
          if (f.kind === 'stripe_confirmation') {
            return <Field key={f.key} label={label(f)} note={note(f)}><TextInput disabled aria-disabled="true" placeholder={fieldPlaceholder(f, t)} /></Field>;
          }
          if (f.kind === 'choice' || f.kind === 'yes_no') {
            const options = f.kind === 'yes_no' ? ['true', 'false'] : f.options;
            const v = values[f.key];
            const value = typeof v === 'boolean' ? String(v) : typeof v === 'string' ? v : options[0] ?? '';
            return (
              <Field key={f.key} label={label(f)} note={note(f)} hint={fieldHelp(f, locale)} error={err(f.key)}>
                <Select value={value} onChange={e => set(f.key, e.target.value)} options={options.map(o => ({ value: o, label: optionLabel(f, o) }))} />
              </Field>
            );
          }
          return textField(f, typeof values[f.key] === 'string' ? (values[f.key] as string) : '', v => set(f.key, v), f.key, f.kind === 'address');
        })}
      </div>
      {spec ? (
        <div>
          <div className="nl-ob-owners-head"><strong style={{ fontSize: 14 }} id="nl-ob-owners">{t(spec.label)}</strong><span className="nl-small nl-muted">{t(spec.note)}</span></div>
          <div className="nl-ob-owners" role="group" aria-labelledby="nl-ob-owners">
            {owners.map((o, i) => (
              <div key={i} className="nl-ob-owner" role="group" aria-label={t('ownerRow', { n: i + 1 })}>
                <Field label={t('fullLegalName')} error={errors[`principals[${i}].legalName`]}>
                  <TextInput placeholder={t('fullLegalNamePh')} value={o.legalName} onChange={e => onOwners(owners.map((r, j) => (j === i ? { ...r, legalName: e.target.value } : r)))} onBlur={() => onTouch(`principals[${i}].legalName`)} />
                </Field>
                <Field label={t('role')} error={errors[`principals[${i}].role`]}>
                  <Select value={o.role} onChange={e => onOwners(owners.map((r, j) => (j === i ? { ...r, role: e.target.value as PrincipalRole } : r)))} options={spec.roles.map(r => ({ value: r, label: t(`role_${r}`) }))} />
                </Field>
                <Field label={spec.share ? t(spec.share) : t('noShare')} error={errors[`principals[${i}].ownershipPct`]}>
                  <TextInput placeholder="%" inputMode="decimal" disabled={!spec.share} value={o.pct} onChange={e => onOwners(owners.map((r, j) => (j === i ? { ...r, pct: e.target.value } : r)))} onBlur={() => onTouch(`principals[${i}].ownershipPct`)} />
                </Field>
                <button type="button" className="btn btn-ghost" style={{ minHeight: 44 }} disabled={owners.length === 1} onClick={() => onOwners(owners.filter((_, j) => j !== i))}>{t('remove')}</button>
              </div>
            ))}
          </div>
          {errors.principals ? <div role="alert" className="nl-error">{errors.principals}</div> : null}
          <button type="button" className="btn btn-ghost" style={{ marginTop: 6 }} onClick={() => onOwners([...owners, { legalName: '', role: spec.roles[1] ?? spec.roles[0]!, pct: '' }])}>{t('addAnother')}</button>
        </div>
      ) : null}
    </section>
  );
}
