import { useId, useState } from 'react';
import { Alert, Avatar, Checkbox, Field, FormGrid, OptionCard, Select, TextInput, useLocale } from '@northline/ui';
import { useSession, useSignOut } from '../../lib/session';
import { serverFieldErrors } from '../../lib/forms';
import type { MerchantType } from '../shell/api';
import { useStartOnboarding, useUpdateAccount, type Onboarding } from './api';
import { useOnboardingT } from './messages';
import { PICKER_TYPES } from './model';
import { accountErrors } from './validation';

type Province = 'AB' | 'BC' | 'ON' | 'QC';

export interface AccountStepProps {
  type: MerchantType | undefined;
  onboarding: Onboarding | undefined;
  /** Brand-new account (07d): Business Terms checkbox, all provinces. */
  isNew: boolean;
  onTypeChange: (type: MerchantType) => void;
  onDone: (merchantId: string, type: MerchantType) => void;
}

/** Step 1 · Account (design 02 lines 110–138): business type, who owns it, province; creates the applicant. */
export function AccountStep({ type, onboarding, isNew, onTypeChange, onDone }: AccountStepProps) {
  const t = useOnboardingT();
  const { locale } = useLocale();
  const { data: session } = useSession();
  const signOut = useSignOut();
  const start = useStartOnboarding();
  const update = useUpdateAccount(onboarding?.merchantId ?? '');
  const [picking, setPicking] = useState(!type);
  const [province, setProvince] = useState<Province>(onboarding?.province ?? 'AB');
  const [workEmail, setWorkEmail] = useState(onboarding?.workEmail ?? '');
  const [terms, setTerms] = useState(onboarding?.businessTermsAccepted ?? false);
  const [tried, setTried] = useState(false);
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const ids = { email: useId(), province: useId(), picker: useId() };
  const user = session?.user;
  const needsTerms = isNew && !onboarding?.businessTermsAccepted;
  const errors = accountErrors({ type, workEmail, terms, needsTerms }, t);
  const pending = start.isPending || update.isPending;
  const server = serverFieldErrors(start.error ?? update.error, ['type', 'province', 'workEmail', 'businessTermsAccepted']);
  const show = (k: string) => (tried || touched[k] ? errors[k] ?? server[k] : server[k]);

  const submit = () => {
    setTried(true);
    if (Object.keys(errors).length || !type) return;
    const body = { type, province, workEmail: workEmail.trim() || undefined, businessTermsAccepted: terms || undefined };
    const opts = { onSuccess: (o: Onboarding) => onDone(o.merchantId, o.type) };
    if (onboarding) update.mutate(body, opts); else start.mutate(body, opts);
  };

  const since = user?.memberSince ? new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'long', year: 'numeric', timeZone: 'America/Edmonton' }).format(new Date(user.memberSince)) : undefined;
  const contact = [user?.email, user?.phone].filter(Boolean).join(' · ');
  const provinces: Province[] = isNew ? ['AB', 'BC', 'ON', 'QC'] : ['AB', 'BC'];

  return (
    <>
      <h1 className="nl-ob-title">{t(type ? `title_${type}` : 'title_none')}</h1>
      <p className="nl-ob-intro">{t(type ? `intro_${type}` : 'intro_none')}</p>

      {type && !picking ? (
        <div className="nl-ob-box">
          <span className="tag tag-accent">{t(`label_${type}`)}</span>
          <span className="nl-ob-box-desc">{t(`desc_${type}`)}</span>
          {onboarding && onboarding.status !== 'applicant' ? null : <button type="button" className="btn btn-ghost" onClick={() => setPicking(true)}>{t('change')}</button>}
        </div>
      ) : (
        <div role="radiogroup" aria-labelledby={ids.picker} className="nl-ob-types">
          <span id={ids.picker} className="nl-sr-only">{t('pickerLabel')}</span>
          {PICKER_TYPES.map(k => (
            <OptionCard key={k} role="radio" aria-checked={type === k} selected={type === k} title={t(`pick_${k}`)} description={t(`pickDesc_${k}`)} onClick={() => { onTypeChange(k); setPicking(false); }} />
          ))}
        </div>
      )}
      {show('type') ? <div role="alert" className="nl-error" style={{ marginTop: -14, marginBottom: 14 }}>{show('type')}</div> : null}

      <div className="nl-ob-kicker">{t('signedInAs')}</div>
      <div className="nl-ob-user">
        <Avatar initials={user?.initials ?? '··'} size={44} tone="dark" />
        <span className="nl-ob-user-text">
          <strong>{user ? `${user.firstName} ${user.lastName}` : '—'}</strong>{contact ? ` · ${contact} · ${t('verifiedContact')}` : ''}
          <br /><span className="nl-small nl-muted">{isNew ? t('createdNow') : since ? t('customerSince', { date: since }) : ''}</span>
        </span>
        <button type="button" className="btn btn-ghost" onClick={() => void signOut()}>{t('notYou')}</button>
      </div>
      <p className="nl-ob-note">{t('ownerNote')}</p>

      <FormGrid className="nl-ob-narrow" style={{ marginTop: 14 }}>
        {!isNew && (
          <Field label={t('workEmail')} error={show('workEmail')}>
            <TextInput id={ids.email} type="email" autoComplete="email" placeholder={t('workEmailPh')} value={workEmail} onChange={e => setWorkEmail(e.target.value)} onBlur={() => setTouched(s => ({ ...s, workEmail: true }))} />
          </Field>
        )}
        <Field label={t('province')} error={server.province}>
          <Select id={ids.province} value={province} onChange={e => setProvince(e.target.value as Province)} options={provinces.map(p => ({ value: p, label: t(`prov_${p}`) }))} />
        </Field>
      </FormGrid>

      {isNew ? (
        <div className="nl-ob-terms">
          <Checkbox checked={terms} onChange={v => { setTerms(v); setTouched(s => ({ ...s, businessTermsAccepted: true })); }} label={<>{t('termsBefore')}<a href="/legal/terms.html#business" target="_blank" rel="noopener">{t('termsLink')}</a>{t('termsAfter')}</>} />
          {show('businessTermsAccepted') ? <div role="alert" className="nl-error">{show('businessTermsAccepted')}</div> : null}
        </div>
      ) : (
        <div style={{ marginTop: 14, maxWidth: 640 }}><Alert tone="info" title={t('stepUpTitle')}>{t('stepUp')}</Alert></div>
      )}

      {(start.isError || update.isError) && !Object.keys(server).length ? <div style={{ marginTop: 14, maxWidth: 640 }}><Alert tone="error">{t('saveError')}</Alert></div> : null}
      <div className="nl-ob-actions">
        <button type="button" className="btn btn-primary" disabled={pending || !type} onClick={submit}>{t('acctCta', { name: user?.firstName ?? '' })}</button>
        <span className="nl-ob-hint">{t(type === 'kitchen' ? 'acctHint_kitchen' : 'acctHint')}</span>
      </div>
    </>
  );
}
