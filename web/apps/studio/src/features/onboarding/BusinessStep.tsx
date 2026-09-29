import { useMemo, useState } from 'react';
import { Chip, Field, FormGrid, Select, TextArea, TextInput } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { attentionCount } from '../../lib/forms';
import type { MerchantType } from '../shell/api';
import { useSaveBusiness, type Onboarding, type Structure } from './api';
import { PROFILE_FIELDS, defaultProfile, type BusinessProfile } from './businessFields';
import { CategoryPicker } from './CategoryPicker';
import { LegalBlock } from './LegalBlock';
import { useOnboardingT } from './messages';
import { LEGAL_FIELDS, OWNERS, toLegalDetails, toPrincipals, validateLegal, validatePrincipals, type LegalValues, type OwnerRow } from './legal';
import { businessSchema, gstOptional, zodErrors } from './validation';

const STRUCTURES: Structure[] = ['sole', 'partnership', 'corp_ab', 'corp_fed', 'corp_ex', 'coop', 'nonprofit'];

/** Select / yes-no legal fields start on their first option (as the design shows them). */
function legalDefaults(structure: Structure, values: LegalValues = {}): LegalValues {
  const out: LegalValues = { ...values };
  for (const f of LEGAL_FIELDS[structure]) if ((f.kind === 'select' || f.kind === 'bool') && out[f.key] === undefined) out[f.key] = f.options![0]!.value;
  return out;
}
const firstOwner = (s: Structure): OwnerRow[] => (OWNERS[s] ? [{ legalName: '', role: OWNERS[s]!.roles[0]!, pct: '' }] : []);

export interface BusinessStepProps { onboarding: Onboarding; onBack: () => void; onDone: () => void }

/** Step 2 · Business (design 02 lines 140–177): names, structure + legal details + principals, GST, categories, public profile. */
export function BusinessStep({ onboarding, onBack, onDone }: BusinessStepProps) {
  const t = useOnboardingT();
  const type: MerchantType = onboarding.type;
  const saved = onboarding.business;
  const save = useSaveBusiness(onboarding.merchantId);

  const [displayName, setDisplayName] = useState(saved?.displayName ?? '');
  const [legalName, setLegalName] = useState(saved?.legalName ?? '');
  const [structure, setStructure] = useState<Structure>(saved?.structure ?? 'sole');
  const [gstNumber, setGst] = useState(saved?.gstNumber ?? '');
  const [legal, setLegal] = useState<LegalValues>(() => legalDefaults(saved?.structure ?? 'sole', (saved?.legalDetails ?? {}) as LegalValues));
  const [owners, setOwners] = useState<OwnerRow[]>(() =>
    saved?.principals.length && saved.structure !== 'sole'
      ? saved.principals.map(p => ({ legalName: p.legalName, role: p.role, pct: p.ownershipPct == null ? '' : String(p.ownershipPct) }))
      : firstOwner(saved?.structure ?? 'sole'));
  const [categoryIds, setCategoryIds] = useState<string[]>(saved?.categories.filter(c => !c.suggested).map(c => c.id) ?? []);
  const [suggestions, setSuggestions] = useState<string[]>(saved?.categories.filter(c => c.suggested).map(c => c.name) ?? []);
  const [profile, setProfile] = useState<BusinessProfile>(() => ({ ...defaultProfile(type), ...((saved?.profile ?? {}) as BusinessProfile) }));
  const [documents, setDocuments] = useState<Record<string, string>>(() => Object.fromEntries((saved?.documents ?? []).map(d => [d.id, d.fileName])));
  const [tried, setTried] = useState(false);
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [server, setServer] = useState<Record<string, string>>({});
  const touch = (k: string) => setTouched(s => ({ ...s, [k]: true }));

  const clientErrors = useMemo(() => ({
    ...zodErrors(businessSchema(type, structure, t).safeParse({ displayName, legalName, gstNumber, categories: categoryIds.length + suggestions.length })),
    ...validateLegal(structure, legal, t),
    ...validatePrincipals(structure, owners, t),
  }), [type, structure, t, displayName, legalName, gstNumber, categoryIds, suggestions, legal, owners]);
  const errorsAll: Record<string, string> = { ...server, ...clientErrors };
  const visible: Record<string, string | undefined> = Object.fromEntries(Object.entries(errorsAll).map(([k, v]) => [k, tried || touched[k] || server[k] ? v : undefined]));
  const count = tried ? attentionCount(errorsAll) : 0;
  const clearServer = (k: string) => setServer(s => { if (!(k in s)) return s; const n = { ...s }; delete n[k]; return n; });

  const changeStructure = (s: Structure) => { setStructure(s); setLegal(legalDefaults(s)); setOwners(firstOwner(s)); setServer({}); };

  const submit = () => {
    setTried(true);
    if (attentionCount(clientErrors)) return;
    save.mutate({
      displayName: displayName.trim(), legalName: legalName.trim(), structure, gstNumber: gstNumber.trim() || undefined,
      legalDetails: toLegalDetails(structure, legal), principals: toPrincipals(structure, owners), categoryIds, suggestedCategories: suggestions, profile,
    }, {
      onSuccess: onDone,
      onError: e => { if (e instanceof ValidationError) setServer(Object.fromEntries(e.errors.map(x => [x.field, x.message]))); },
    });
  };

  return (
    <>
      <h1 className="nl-ob-title">{t('bizTitle')}</h1>
      <p className="nl-ob-intro" style={{ marginBottom: 24 }}>{t('bizIntro')}</p>
      <FormGrid className="nl-ob-form">
        <Field label={t(`nameLabel_${type}`)} error={visible.displayName}>
          <TextInput placeholder={t(`namePh_${type}`)} value={displayName} onChange={e => { setDisplayName(e.target.value); clearServer('displayName'); }} onBlur={() => touch('displayName')} />
        </Field>
        <Field label={t('legalEntity')} error={visible.legalName}>
          <TextInput placeholder={t('legalEntityPh')} value={legalName} onChange={e => { setLegalName(e.target.value); clearServer('legalName'); }} onBlur={() => touch('legalName')} />
        </Field>
        <Field label={t('structure')}>
          <Select value={structure} onChange={e => changeStructure(e.target.value as Structure)} options={STRUCTURES.map(s => ({ value: s, label: t(`st_${s}`) }))} />
        </Field>
        <Field label={t('gst')} note={`· ${t(gstOptional(structure) ? 'gstNoteOptional' : 'gstNoteRequired')}`} error={visible.gstNumber}>
          <TextInput placeholder={t('gstPh')} value={gstNumber} onChange={e => { setGst(e.target.value); clearServer('gstNumber'); }} onBlur={() => touch('gstNumber')} />
        </Field>
        <LegalBlock
          merchantId={onboarding.merchantId}
          structure={structure}
          values={legal}
          onChange={v => { setLegal(v); setServer(s => Object.fromEntries(Object.entries(s).filter(([k]) => !k.startsWith('legalDetails.')))); }}
          documents={documents}
          onDocument={(id, name) => setDocuments(d => ({ ...d, [id]: name }))}
          owners={owners}
          onOwners={rows => { setOwners(rows); setServer(s => Object.fromEntries(Object.entries(s).filter(([k]) => !k.startsWith('principals')))); }}
          errors={visible}
          onTouch={touch}
        />
        <CategoryPicker
          type={type}
          value={categoryIds}
          onChange={ids => { setCategoryIds(ids); clearServer('categories'); }}
          suggestions={suggestions}
          onSuggestionsChange={s => { setSuggestions(s); clearServer('categories'); }}
          error={visible.categories}
          onClose={() => touch('categories')}
        />
        {PROFILE_FIELDS[type].map(f => {
          const value = profile[f.key];
          const set = (v: unknown) => setProfile(p => ({ ...p, [f.key]: v }));
          if (f.kind === 'select') return <Field key={f.key} label={t(f.label)} span={f.span}><Select value={String(value ?? f.options[0]!.value)} onChange={e => set(e.target.value)} options={f.options.map(o => ({ value: o.value, label: t(o.label) }))} /></Field>;
          if (f.kind === 'chips') {
            const list = Array.isArray(value) ? (value as string[]) : [];
            return (
              <div key={f.key} className={f.span ? 'nl-field nl-span-all' : 'nl-field'} role="group" aria-label={t(f.label)}>
                <span className="nl-label">{t(f.label)}</span>
                <div className="nl-chips">{f.options.map(o => <Chip key={o.value} selected={list.includes(o.value)} onClick={() => set(list.includes(o.value) ? list.filter(x => x !== o.value) : [...list, o.value])}>{t(o.label)}</Chip>)}</div>
              </div>
            );
          }
          const text = typeof value === 'string' ? value : '';
          const err = visible[`profile.${f.key}`];
          return f.kind === 'area'
            ? <Field key={f.key} label={t(f.label)} span={f.span} error={err}><TextArea style={{ minHeight: 80 }} placeholder={t(f.ph)} maxLength={f.max} value={text} onChange={e => set(e.target.value)} /></Field>
            : <Field key={f.key} label={t(f.label)} span={f.span} error={err}><TextInput placeholder={t(f.ph)} maxLength={f.max} value={text} onChange={e => set(e.target.value)} /></Field>;
        })}
      </FormGrid>
      {count > 0 ? <div role="alert" className="nl-alert nl-alert-error" style={{ marginTop: 22, maxWidth: 720 }}><strong>{t('attention', { count })}</strong> {t('attentionTail')}</div> : null}
      {save.isError && !(save.error instanceof ValidationError) ? <div role="alert" className="nl-alert nl-alert-error" style={{ marginTop: 22, maxWidth: 720 }}>{t('saveError')}</div> : null}
      <div className="nl-ob-actions">
        <button type="button" className="btn btn-primary" disabled={save.isPending} onClick={submit}>{t('bizNext')}</button>
        <button type="button" className="btn btn-ghost" onClick={onBack}>{t('back')}</button>
      </div>
    </>
  );
}
