import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { Button, Dialog, ErrorState, Field, FormGrid, Select, Skeleton, TextInput, useFormatters } from '@northline/ui';
import { serverFieldErrors } from '@northline/client';
import { profileQuery, useRequestErasure, useSaveProfile, type Profile } from './settingsApi';
import { useSettingsT, type SettingsT } from './settingsMessages';

const FIELDS = ['firstName', 'lastName', 'email', 'pronouns', 'birthday'] as const;
type Values = Record<(typeof FIELDS)[number], string>;

/** validation-rules.md (registration) + the birthday's own rule, worded in the reader's language. */
const schema = (t: SettingsT) => z.object({
  firstName: z.string().trim().min(1, t('v_firstName')).max(60, t('v_nameLong')),
  lastName: z.string().trim().min(1, t('v_lastName')).max(60, t('v_nameLong')),
  email: z.string().trim().min(1, t('v_email')).regex(/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/, t('v_emailFormat')),
  pronouns: z.string(),
  birthday: z.string().trim().refine(v => v === '' || /^(0?[1-9]|1[0-2])\s*[/-]\s*(0?[1-9]|[12]\d|3[01])$/.test(v), t('v_birthday')),
});

const fromProfile = (p: Profile): Values => ({
  firstName: p.firstName, lastName: p.lastName, email: p.email ?? '', pronouns: p.pronouns ?? '',
  birthday: p.birthday ? p.birthday.replace('-', ' / ') : '',
});

/** Profile (design 06 `at.profile`): name, verified mobile, receipts email, pronouns, birthday; reliability; delete. */
export function ProfileTab() {
  const t = useSettingsT();
  const profile = useQuery(profileQuery);
  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t('profileTitle')}</h1>
      {profile.isPending ? <FormSkeleton label={t('loading')} />
        : profile.isError ? <ErrorState message={t('loadError')} onRetry={() => void profile.refetch()} />
          : <ProfileForm profile={profile.data} />}
    </>
  );
}

function ProfileForm({ profile }: { profile: Profile }) {
  const t = useSettingsT();
  const { number, date } = useFormatters();
  const save = useSaveProfile();
  const erase = useRequestErasure();
  const [values, setValues] = useState<Values>(() => fromProfile(profile));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saved, setSaved] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const set = (k: keyof Values) => (v: string) => { setValues(x => ({ ...x, [k]: v })); setSaved(false); };
  const initials = `${values.firstName.charAt(0)}${values.lastName.charAt(0)}`.toUpperCase() || 'NL';

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const parsed = schema(t).safeParse(values);
    if (!parsed.success) {
      const out: Record<string, string> = {};
      for (const issue of parsed.error.issues) out[String(issue.path[0])] ??= issue.message;
      setErrors(out);
      return;
    }
    setErrors({});
    const b = parsed.data.birthday.match(/^(\d{1,2})\s*[/-]\s*(\d{1,2})$/);
    save.mutate({
      firstName: parsed.data.firstName, lastName: parsed.data.lastName, email: parsed.data.email,
      pronouns: parsed.data.pronouns || null, birthday: b ? `${b[1]!.padStart(2, '0')}-${b[2]!.padStart(2, '0')}` : null,
    }, {
      onSuccess: p => { setValues(fromProfile(p)); setSaved(true); },
      onError: err => setErrors(serverFieldErrors(err, FIELDS)),
    });
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="nl-profile-head">
        <span className="nl-profile-avatar" aria-hidden>{initials}</span>
      </div>
      <FormGrid min={240} className="nl-acct-form">
        <Field label={t('firstName')} error={errors.firstName}><TextInput value={values.firstName} onChange={e => set('firstName')(e.target.value)} autoComplete="given-name" /></Field>
        <Field label={t('lastName')} error={errors.lastName}><TextInput value={values.lastName} onChange={e => set('lastName')(e.target.value)} autoComplete="family-name" /></Field>
        <Field label={t('mobile')} hint={t('mobileHint')}><TextInput value={profile.phone ?? ''} readOnly /></Field>
        <Field label={t('email')} error={errors.email}><TextInput type="email" value={values.email} onChange={e => set('email')(e.target.value)} autoComplete="email" /></Field>
        <Field label={t('pronouns')} error={errors.pronouns}>
          <Select value={values.pronouns} onChange={e => set('pronouns')(e.target.value)}
            options={[{ value: '', label: t('pr_unset') }, ...(['she', 'he', 'they', 'none'] as const).map(p => ({ value: p, label: t(`pr_${p}`) }))]} />
        </Field>
        <Field label={t('birthday')} error={errors.birthday}><TextInput value={values.birthday} onChange={e => set('birthday')(e.target.value)} placeholder={t('birthdayPlaceholder')} inputMode="numeric" /></Field>
      </FormGrid>
      <h2 className="nl-acct-h2">{profile.reliability != null ? t('reliabilityTitle', { score: number(profile.reliability, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }) : t('reliabilityNone')}</h2>
      <p className="nl-acct-lede">{t('reliabilityBody')}</p>
      {errors._form ? <p className="nl-error" role="alert">{errors._form}</p> : null}
      {save.isError && !Object.keys(errors).length ? <p className="nl-error" role="alert">{t('saveError')}</p> : null}
      {profile.erasureRequestedAt ? <p className="nl-acct-note" role="status">{t('deleteRequested', { date: date(profile.erasureRequestedAt, 'long') })}</p> : null}
      <div className="nl-acct-actions">
        <Button type="submit" disabled={save.isPending} aria-busy={save.isPending}>{t('saveChanges')}</Button>
        {!profile.erasureRequestedAt ? <Button type="button" variant="ghost" className="nl-danger" onClick={() => setConfirmDelete(true)}>{t('deleteAccount')}</Button> : null}
        {saved ? <span className="nl-small nl-muted" role="status">{t('saved')}</span> : null}
      </div>
      <Dialog open={confirmDelete} onClose={() => setConfirmDelete(false)} title={t('deleteTitle')} role="alertdialog"
        actions={<>
          <Button type="button" variant="ghost" onClick={() => setConfirmDelete(false)}>{t('cancel')}</Button>
          <Button type="button" className="nl-danger-btn" disabled={erase.isPending} onClick={() => erase.mutate(undefined, { onSuccess: () => setConfirmDelete(false) })}>{t('deleteConfirm')}</Button>
        </>}>
        <p>{t('deleteBody')}</p>
        {erase.isError ? <p className="nl-error" role="alert">{t('saveError')}</p> : null}
      </Dialog>
    </form>
  );
}

export function FormSkeleton({ label, rows = 4 }: { label: string; rows?: number }) {
  return (
    <div aria-busy="true" style={{ marginTop: 18, maxWidth: 640 }}>
      <span className="nl-sr-only">{label}</span>
      {Array.from({ length: rows }, (_, i) => <Skeleton key={i} height={44} style={{ marginTop: 12 }} />)}
    </div>
  );
}
