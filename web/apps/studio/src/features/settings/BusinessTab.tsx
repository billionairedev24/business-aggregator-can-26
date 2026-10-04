import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Alert, Button, ErrorState, Field, PageSkeleton, Segmented, Select, Tag, TextInput, useLocale } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { complianceQuery, type ComplianceDoc } from '../compliance/api';
import { docName, docState, yearMonth } from '../compliance/docs';
import { useComplianceT } from '../compliance/messages';
import { useMerchantId, useRole } from '../shell/api';
import { screenHref } from '../shell/nav';
import { businessQuery, useSaveBusiness, type Business } from './api';
import { useSettingsT, type SettingsT } from './messages';
import { businessErrors, dollarsToCents, localizeServerErrors, type BusinessForm } from './validation';

const LANGUAGES = ['en', 'fr', 'pa', 'hi', 'zh', 'tl', 'es', 'ar', 'vi', 'uk'] as const;
const langName = (code: string, t: SettingsT) => ((LANGUAGES as readonly string[]).includes(code) ? t(`lang_${code}` as Parameters<SettingsT>[0]) : code);

const omit = (o: Record<string, string>, key: string) => Object.fromEntries(Object.entries(o).filter(([k]) => k !== key));

const toForm = (b: Business): BusinessForm => ({
  displayName: b.displayName, legalName: b.legalName, gstNumber: b.gstNumber ?? '', serviceArea: b.serviceArea ?? '',
  cancellationPolicy: b.cancellationPolicy, autoAccept: b.autoAcceptQuoteCents == null ? '' : `$${(b.autoAcceptQuoteCents / 100).toFixed(b.autoAcceptQuoteCents % 100 ? 2 : 0)}`,
  languages: b.languages,
});

export function BusinessTab() {
  const t = useSettingsT();
  const merchantId = useMerchantId();
  const q = useQuery(businessQuery(merchantId));
  if (q.isPending) return <PageSkeleton kpis={0} rows={6} />;
  if (q.isError) return <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} />;
  return <BusinessEditor business={q.data} />;
}

function BusinessEditor({ business }: { business: Business }) {
  const t = useSettingsT();
  const merchantId = useMerchantId();
  const role = useRole();
  const owner = role === 'owner';
  const save = useSaveBusiness(merchantId);
  const [form, setForm] = useState<BusinessForm>(() => toForm(business));
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [submitted, setSubmitted] = useState(false);
  const [server, setServer] = useState<Record<string, string>>({});
  const [saved, setSaved] = useState(false);
  useEffect(() => { setForm(toForm(business)); }, [business]);

  const errors = businessErrors(form, business.gstRequired, t);
  const shown = (k: string) => server[k] ?? ((touched[k] || submitted) ? errors[k] : undefined);
  const count = Object.values(errors).filter(Boolean).length;
  const set = <K extends keyof BusinessForm>(k: K, v: BusinessForm[K]) => { setForm(f => ({ ...f, [k]: v })); setServer(s => omit(s, k === 'autoAccept' ? 'autoAcceptQuoteCents' : k)); setSaved(false); };
  const touch = (k: string) => () => setTouched(x => ({ ...x, [k]: true }));
  const quotes = business.type === 'provider' || business.type === 'both';

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    if (count) return;
    save.mutate({
      displayName: form.displayName.trim(), legalName: form.legalName.trim(), gstNumber: form.gstNumber.trim() || null, serviceArea: form.serviceArea.trim() || null,
      cancellationPolicy: form.cancellationPolicy, autoAcceptQuoteCents: dollarsToCents(form.autoAccept), languages: form.languages,
    }, {
      onSuccess: () => { setSaved(true); setSubmitted(false); setTouched({}); },
      onError: err => { if (err instanceof ValidationError) setServer(localizeServerErrors(err.errors, t)); },
    });
  };

  const addable = LANGUAGES.filter(l => !form.languages.includes(l));
  const serverCount = Object.keys(server).length;
  return (
    <form className="nl-set-business" onSubmit={submit} noValidate>
      {!owner && <p className="nl-small nl-muted">{t('viewOnly', { role: t(`role_${role}` as Parameters<SettingsT>[0]) })}</p>}
      {(submitted && count > 0) || serverCount > 0 ? <Alert tone="error" role="alert">{t('attention', { n: submitted && count ? count : serverCount })}</Alert> : null}
      <fieldset className="nl-set-fields" disabled={!owner}>
        <Field label={t('businessName')} error={shown('displayName')}>
          <TextInput value={form.displayName} onChange={e => set('displayName', e.target.value)} onBlur={touch('displayName')} autoComplete="organization" />
        </Field>
        <Field label={t('legalEntity')} error={shown('legalName')}>
          <TextInput value={form.legalName} onChange={e => set('legalName', e.target.value)} onBlur={touch('legalName')} />
        </Field>
        <Field label={t('gstNumber')} note={business.gstRequired ? undefined : t('gstOptional')} error={shown('gstNumber')}>
          <TextInput value={form.gstNumber} onChange={e => set('gstNumber', e.target.value)} onBlur={touch('gstNumber')} inputMode="text" placeholder="123456789 RT0001" />
        </Field>
        <Field label={t('serviceArea')} error={shown('serviceArea')}>
          <TextInput value={form.serviceArea} onChange={e => set('serviceArea', e.target.value)} onBlur={touch('serviceArea')} />
        </Field>
        <Field label={t('cancellationPolicy')}>
          <Segmented name="cancellation-policy" aria-label={t('cancellationPolicy')} value={form.cancellationPolicy} onChange={v => set('cancellationPolicy', v)}
            options={(['flexible', '12h', '24h'] as const).map(v => ({ value: v, label: t(`policy_${v}`) }))} />
        </Field>
        {quotes && (
          <Field label={t('autoAccept')} hint={t('autoAcceptHint')} error={shown('autoAcceptQuoteCents')}>
            <TextInput value={form.autoAccept} onChange={e => set('autoAccept', e.target.value)} onBlur={touch('autoAcceptQuoteCents')} inputMode="decimal" placeholder="$150" />
          </Field>
        )}
        <div className="nl-field nl-set-langs" role="group" aria-labelledby="set-langs-label">
          <span id="set-langs-label" className="nl-label">{t('languages')}</span>
          <div className="nl-set-langrow">
            {form.languages.map(l => (
              <button key={l} type="button" className="tag tag-accent nl-set-lang" aria-label={t('removeLanguage', { language: langName(l, t) })}
                onClick={() => { set('languages', form.languages.filter(x => x !== l)); setTouched(x => ({ ...x, languages: true })); }}>{langName(l, t)}</button>
            ))}
            {!form.languages.includes('fr') && (
              <button type="button" className="tag tag-neutral nl-set-lang" onClick={() => set('languages', [...form.languages, 'fr'])}>{t('addLanguage', { language: t('lang_fr') })}</button>
            )}
            {addable.filter(l => l !== 'fr').length > 0 && (
              <Select aria-label={t('moreLanguages')} className="nl-set-langselect" value="" placeholder={t('moreLanguages')}
                options={addable.filter(l => l !== 'fr').map(l => ({ value: l, label: langName(l, t) }))}
                onChange={e => { if (e.target.value) set('languages', [...form.languages, e.target.value]); }} />
            )}
          </div>
          {shown('languages') ? <div role="alert" className="nl-error">{shown('languages')}</div> : null}
        </div>
      </fieldset>
      {owner && (
        <div className="nl-set-actions">
          <Button type="submit" disabled={save.isPending}>{save.isPending ? t('saving') : t('save')}</Button>
          <span role="status" className="nl-small nl-muted">{saved ? t('saved') : t('legalReview')}</span>
          {save.isError && !(save.error instanceof ValidationError) && <span role="alert" className="nl-error">{t('saveError')}</span>}
        </div>
      )}
      <Licences />
    </form>
  );
}

/** "Licences & insurance" — verified licences, permits and insurance from the compliance ledger. */
function Licences() {
  const t = useSettingsT();
  const ct = useComplianceT();
  const { locale } = useLocale();
  const merchantId = useMerchantId();
  const q = useQuery(complianceQuery(merchantId));
  const shown = (d: ComplianceDoc) => ['licence', 'insurance', 'ahs_permit', 'food_cert'].includes(d.checkType) && d.reference !== 'none' && d.reference !== 'not_applicable';
  const state = (d: ComplianceDoc) => {
    if (d.status === 'verified' && !d.dueSoon) {
      return { text: d.expiresAt ? t(d.checkType === 'insurance' ? 'lic_to' : 'lic_renews', { month: yearMonth(d.expiresAt) }) : t('lic_verified'), tone: 'accent' as const };
    }
    return docState(d, ct, locale);
  };
  return (
    <section className="nl-set-licences" aria-labelledby="set-lic">
      <h2 id="set-lic" className="nl-set-h3">{t('licencesTitle')}</h2>
      {q.isPending ? <PageSkeleton kpis={0} rows={3} /> : q.isError ? <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /> : (
        <>
          {q.data.documents.filter(shown).length === 0 ? <p className="nl-muted nl-small">{t('noLicences')}</p> : (
            <ul className="nl-set-rows">
              {q.data.documents.filter(shown).map(d => {
                const st = state(d);
                return <li key={d.id} className="nl-set-row"><span>{docName(d, ct)}</span><Tag tone={st.tone}>{st.text}</Tag></li>;
              })}
            </ul>
          )}
          <Link className="nl-small nl-set-tolink" to={screenHref(merchantId, 'compliance')}>{t('manageCompliance')}</Link>
        </>
      )}
    </section>
  );
}
