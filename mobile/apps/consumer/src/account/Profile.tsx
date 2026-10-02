import { useQuery } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { useState } from 'react';
import { View } from 'react-native';

import { ApiError } from '@northline/mobile-kit';

import type { Profile as ProfileData } from '../api/account';
import { useAuth } from '../auth/AuthProvider';
import { config } from '../config';
import { useI18n, type MessageKey } from '../i18n';
import { useShopFormat } from '../shop/common';
import { Chip } from '../shop/parts';
import { Body, Button, Field, Notice } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { LoadingList, QueryView, SignInPrompt, errorMessage } from '../ui/states';
import { KEYS, account, useAccountMutation } from './common';
import { Heading, accountStyles } from './parts';

const PRONOUNS = ['she', 'he', 'they', 'none'] as const;
/** docs/spec/validation-rules.md, the api's AccountRules: the same rules, the same words. */
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const BIRTHDAY = /^(0[1-9]|1[0-2])\/(0[1-9]|[12]\d|3[01])$/;
const SERVER: Record<string, MessageKey> = {
  'First name is required.': 'account.profile.v.first',
  'Last name is required.': 'account.profile.v.last',
  'Keep names under 60 characters.': 'account.profile.v.long',
  'Email is required.': 'account.profile.v.email',
  "That doesn't look like an email address.": 'account.profile.v.emailFormat',
  'That email is already used by another account.': 'account.profile.v.emailTaken',
  'Choose from the list.': 'account.profile.v.pronouns',
  'Enter a birthday like 03/14 (month / day).': 'account.profile.v.birthday',
};

/**
 * Personal details (the consumer web's S-59 Profile tab, reached from You): name, email, pronouns, birthday
 * (`GET`/`PATCH /me/profile`, the rules checked here first with the api's words); the mobile number is shown — changing
 * it needs a code and happens on the website. "Delete account…" asks Northline to erase the account
 * (`POST /me/erasure-request`; staff confirm by email, as on the web).
 */
export function Profile() {
  const { t } = useI18n();
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const profile = useQuery({ queryKey: KEYS.profile, queryFn: () => account().profile(), enabled: signedIn, staleTime: 300_000 });
  if (!signedIn) {
    return (
      <Screen title={t('account.profile.title')} testID="profile">
        <SignInPrompt message={t('account.profile.signIn')} />
      </Screen>
    );
  }
  return (
    <Screen title={t('account.profile.title')} testID="profile">
      <QueryView query={profile} skeleton={<LoadingList rows={5} height={48} />}>
        {(p) => <ProfileForm initial={p} />}
      </QueryView>
    </Screen>
  );
}

type Errors = Partial<Record<'firstName' | 'lastName' | 'email' | 'birthday' | 'pronouns', string>>;

function ProfileForm({ initial }: { initial: ProfileData }) {
  const f = useShopFormat();
  const { t } = f;
  const [saved, setSaved] = useState(false);
  // any edit hides "Saved."
  const edited = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    setSaved(false);
  };
  const [first, setFirstRaw] = useState(initial.firstName);
  const [last, setLastRaw] = useState(initial.lastName);
  const [email, setEmailRaw] = useState(initial.email ?? '');
  const [pronouns, setPronounsRaw] = useState<string | null>(initial.pronouns ?? null);
  const [birthday, setBirthdayRaw] = useState(initial.birthday ? initial.birthday.replace('-', '/') : '');
  const [setFirst, setLast, setEmail, setBirthday] = [edited(setFirstRaw), edited(setLastRaw), edited(setEmailRaw), edited(setBirthdayRaw)];
  const setPronouns = edited(setPronounsRaw);
  const [errors, setErrors] = useState<Errors>({});
  const [confirmDelete, setConfirmDelete] = useState(false);
  const save = useAccountMutation((c: Parameters<ReturnType<typeof account>['saveProfile']>[0]) => account().saveProfile(c), { set: KEYS.profile });
  const erase = useAccountMutation(() => account().requestErasure(), { set: KEYS.profile });
  const server = (m: string | undefined) => (m ? (SERVER[m] ? t(SERVER[m]) : m) : undefined);

  const submit = () => {
    const e: Errors = {};
    if (!first.trim()) e.firstName = t('account.profile.v.first');
    else if (first.trim().length > 60) e.firstName = t('account.profile.v.long');
    if (!last.trim()) e.lastName = t('account.profile.v.last');
    else if (last.trim().length > 60) e.lastName = t('account.profile.v.long');
    if (!email.trim()) e.email = t('account.profile.v.email');
    else if (!EMAIL.test(email.trim())) e.email = t('account.profile.v.emailFormat');
    if (birthday.trim() && !BIRTHDAY.test(birthday.trim())) e.birthday = t('account.profile.v.birthday');
    setErrors(e);
    if (Object.keys(e).length) return;
    save.mutate(
      { firstName: first.trim(), lastName: last.trim(), email: email.trim(), pronouns, birthday: birthday.trim() ? birthday.trim().replace('/', '-') : null },
      {
        onSuccess: () => setSaved(true),
        onError: (err) => {
          if (err instanceof ApiError && err.status === 422) {
            setErrors({
              firstName: server(err.fieldMessage('firstName')),
              lastName: server(err.fieldMessage('lastName')),
              email: server(err.fieldMessage('email')),
              pronouns: server(err.fieldMessage('pronouns')),
              birthday: server(err.fieldMessage('birthday')),
            });
          }
        },
      },
    );
  };

  const requested = erase.data?.erasureRequestedAt ?? initial.erasureRequestedAt;
  const saveError = save.error && !(save.error instanceof ApiError && save.error.status === 422) ? errorMessage(save.error, t) : null;
  return (
    <View style={accountStyles.form}>
      <Field label={t('account.profile.first')} value={first} onChangeText={setFirst} autoComplete="given-name" textContentType="givenName" error={errors.firstName} testID="profile-first" />
      <Field label={t('account.profile.last')} value={last} onChangeText={setLast} autoComplete="family-name" textContentType="familyName" error={errors.lastName} testID="profile-last" />
      <Field
        label={t('account.profile.email')}
        value={email}
        onChangeText={setEmail}
        keyboardType="email-address"
        autoCapitalize="none"
        autoComplete="email"
        textContentType="emailAddress"
        error={errors.email}
        testID="profile-email"
      />
      <Field label={t('account.profile.phone')} value={initial.phone ?? ''} editable={false} testID="profile-phone" />
      <Body tone="small">{t('account.profile.phoneHint')}</Body>
      <Body tone="small">{t('account.profile.pronouns')}</Body>
      <View style={accountStyles.chips} accessibilityRole="radiogroup" accessibilityLabel={t('account.profile.pronouns')}>
        {PRONOUNS.map((p) => (
          <Chip key={p} role="radio" label={t(`account.profile.pronoun.${p}`)} on={pronouns === p} onPress={() => setPronouns(pronouns === p ? null : p)} testID={`pronouns-${p}`} />
        ))}
      </View>
      {errors.pronouns ? <Notice message={errors.pronouns} /> : null}
      <Field label={t('account.profile.birthday')} value={birthday} onChangeText={setBirthday} placeholder="03/14" keyboardType="numbers-and-punctuation" maxLength={5} error={errors.birthday} testID="profile-birthday" />
      {saveError ? <Notice message={saveError} /> : null}
      {saved ? <Notice tone="info" message={t('account.profile.saved')} /> : null}
      <Button label={t('account.profile.save')} busy={save.isPending} onPress={submit} testID="profile-save" />

      <Heading>{t('account.profile.privacy')}</Heading>
      {requested ? (
        <Notice tone="info" message={t('account.profile.deleteRequested', { date: f.date(requested) })} testID="erasure-requested" />
      ) : confirmDelete ? (
        <View style={accountStyles.actions}>
          <Body>{t('account.profile.deleteBody')}</Body>
          {erase.error ? <Notice message={errorMessage(erase.error, t)} /> : null}
          <Button label={t('account.profile.deleteConfirm')} tone="danger" busy={erase.isPending} onPress={() => erase.mutate(undefined)} testID="erasure-confirm" />
          <Button label={t('account.profile.keep')} tone="ghost" onPress={() => setConfirmDelete(false)} />
        </View>
      ) : (
        <Button label={t('account.profile.delete')} tone="danger" onPress={() => setConfirmDelete(true)} testID="erasure-start" />
      )}
      <Button label={t('account.profile.phoneSite')} tone="ghost" hint={t('common.opensInBrowser')} onPress={() => void Linking.openURL(`${config.siteOrigin}/account?tab=profile`)} />
    </View>
  );
}
