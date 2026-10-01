import { useState } from 'react';
import { useRegions } from '../shell/place';
import { Field, FileButton, Select, TextInput } from '@northline/ui';
import { uploadDocument } from '../storefront/api';
import { ValidationError } from '../../lib/http';
import type { PrincipalRole, Structure } from './api';
import { useOnboardingT } from './messages';
import { LEGAL_FIELDS, OWNERS, type Attorney, type LegalValues, type OwnerRow } from './legal';

export interface LegalBlockProps {
  merchantId: string;
  structure: Structure;
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

/** "Legal details · <structure>" with the per-structure fields and the owners / principals table (design 02 `bsDefs`). */
export function LegalBlock({ merchantId, structure, values, onChange, documents, onDocument, owners, onOwners, errors, onTouch }: LegalBlockProps) {
  const t = useOnboardingT();
  const regions = useRegions();
  const provinceName = (code: string) => regions?.provinces.find(p => p.code === code)?.name ?? code;
  const [uploading, setUploading] = useState<string | null>(null);
  const [uploadError, setUploadError] = useState<Record<string, string>>({});
  const spec = OWNERS[structure];
  const set = (key: string, v: LegalValues[string]) => onChange({ ...values, [key]: v });
  const err = (key: string) => errors[`legalDetails.${key}`];

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

  return (
    <section className="nl-ob-legal" aria-labelledby="nl-ob-legal-title">
      <div className="nl-ob-legal-head"><strong id="nl-ob-legal-title" style={{ fontSize: 15 }}>{t('legalDetails', { name: t(`bs_${structure}_name`) })}</strong><span className="nl-small nl-muted">{t(`bs_${structure}_law`)}</span></div>
      <p className="nl-ob-legal-why">{t(`bs_${structure}_why`)}</p>
      <div className="nl-grid" style={{ ['--nl-min' as string]: '220px' }}>
        {LEGAL_FIELDS[structure].map(f => {
          const note = t(`req_${f.req}`);
          if (f.kind === 'attorney') {
            const a = (values[f.key] ?? {}) as Attorney;
            return (
              <div key={f.key} className="nl-span-all nl-grid" style={{ ['--nl-min' as string]: '220px' }}>
                <Field label={`${t(f.label)} · ${t('lf_attorney_name')}`} note={note} error={err(`${f.key}.name`)}>
                  <TextInput value={a.name ?? ''} onChange={e => set(f.key, { ...a, name: e.target.value })} onBlur={() => onTouch(`legalDetails.${f.key}.name`)} />
                </Field>
                <Field label={t('lf_attorney_address')} note={note} error={err(`${f.key}.alberta_address`)}>
                  <TextInput value={a.alberta_address ?? ''} onChange={e => set(f.key, { ...a, alberta_address: e.target.value })} onBlur={() => onTouch(`legalDetails.${f.key}.alberta_address`)} />
                </Field>
              </div>
            );
          }
          if (f.kind === 'doc') {
            const id = typeof values[f.key] === 'string' ? (values[f.key] as string) : undefined;
            const e = uploadError[f.key] || err(f.key);
            return (
              <div key={f.key} className="nl-field" style={f.span ? { gridColumn: '1 / -1' } : undefined}>
                <span className="nl-label">{t(f.label)} <span className="nl-label-note">{note}</span></span>
                <FileButton className="nl-ob-doc-btn" accept="application/pdf,image/png,image/jpeg" pending={uploading === f.key} aria-invalid={!!e} onFile={file => void upload(f.key, file)}>
                  {uploading === f.key ? t('uploading') : id && documents[id] ? t('uploaded', { file: documents[id]! }) : t('upload', { what: f.ph ? t(f.ph) : t('lf_pdf') })}
                </FileButton>
                {e ? <div role="alert" className="nl-error">{e}</div> : null}
              </div>
            );
          }
          if (f.kind === 'sin') {
            return <Field key={f.key} label={t(f.label)} note={note}><TextInput disabled aria-disabled="true" placeholder={f.ph ? t(f.ph) : undefined} /></Field>;
          }
          if (f.kind === 'select' || f.kind === 'bool') {
            const v = values[f.key];
            const value = typeof v === 'boolean' ? String(v) : typeof v === 'string' ? v : f.options![0]!.value;
            return (
              <Field key={f.key} label={t(f.label)} note={note} error={err(f.key)}>
                <Select value={value} onChange={e => set(f.key, e.target.value)} options={f.options!.map(o => ({ value: o.value, label: o.province ? t(o.label, { name: provinceName(o.value) }) : t(o.label) }))} />
              </Field>
            );
          }
          return (
            <Field key={f.key} label={t(f.label)} note={note} error={err(f.key)} span={f.span}>
              <TextInput value={typeof values[f.key] === 'string' ? (values[f.key] as string) : ''} placeholder={f.ph ? t(f.ph) : undefined} inputMode={f.pattern ? 'numeric' : undefined} onChange={e => set(f.key, e.target.value)} onBlur={() => onTouch(`legalDetails.${f.key}`)} />
            </Field>
          );
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
