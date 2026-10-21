import { router } from 'expo-router';
import { useQuery } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { StyleSheet, View, type TextInput } from 'react-native';

import { space } from '@northline/mobile-kit';

import { useAccountFlow } from '../auth/AccountFlow';
import { useAuth } from '../auth/AuthProvider';
import { fieldErrors, flowError, validateSignUp, type SignUpField, type SignUpForm } from '../auth/rules';
import { useI18n } from '../i18n';
import { services } from '../services';
import { Body, Button, Checkbox, Field, Link, Notice, Row, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { TermsLabel } from './LegalText';
import { useAfterSignIn } from './useAfterSignIn';

/**
 * A2 Sign up: phone first — mobile, full name, email for receipts, the terms (links open outside the app). "Send code"
 * stays off until the mobile and the terms are filled (as on the consumer site, S-62); every rule shows under its
 * field on submit, with the auth server's own rules as a second check. Apple / Google continue on the consumer site in
 * the browser (the app has no native Sign in with Apple / Google).
 */
export function SignUp() {
  const { t, locale } = useI18n();
  const flow = useAccountFlow();
  const { signInWithBrowser } = useAuth();
  const afterSignIn = useAfterSignIn();
  const [form, setForm] = useState<SignUpForm>(flow.draft);
  const [errors, setErrors] = useState<Partial<Record<SignUpField, string>>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // S-157: unticked offers boxes (email, texts) with the auth server's wording — CASL express consent; no wording, no box
  const wordings = useQuery({ queryKey: ['auth', 'consent-wordings', locale], queryFn: () => services().authApi.consentWordings(), retry: false, staleTime: 10 * 60_000 });
  const [ticked, setTicked] = useState<Record<string, boolean>>({});
  const nameRef = useRef<TextInput>(null);
  const emailRef = useRef<TextInput>(null);

  const change = <K extends SignUpField>(key: K, value: SignUpForm[K]) => {
    setForm((f) => ({ ...f, [key]: value }));
    setErrors((e) => ({ ...e, [key]: undefined }));
  };

  const send = async () => {
    const found = validateSignUp(form, t);
    setErrors(found);
    setMessage(null);
    flow.setDraft(form);
    if (Object.keys(found).length) return;
    setBusy(true);
    try {
      const marketing = (wordings.data?.items ?? [])
        .filter((i) => ticked[i.category] && (i.category !== 'marketing_sms' || form.phone.trim()))
        .map((i) => ({ category: i.category, wordingVersion: i.wordingVersion }));
      await flow.register(form, marketing);
      router.push('/verify');
    } catch (e) {
      const fields = fieldErrors(e, t);
      setErrors({ phone: fields.phone, fullName: fields.firstName ?? fields.lastName, email: fields.email, terms: fields.terms });
      setMessage(flowError(e, t, 'form') ?? null);
    } finally {
      setBusy(false);
    }
  };

  const social = async () => {
    const r = await signInWithBrowser();
    if (r.ok) afterSignIn();
    else if (r.reason !== 'cancelled') setMessage(r.reason === 'webOnly' ? t('browserSignIn.webOnly') : t('browserSignIn.failed', { detail: r.detail ?? '' }));
  };

  return (
    <Screen
      title={t('title.signup')}
      testID="sign-up"
      footer={
        <>
          {message ? <Notice message={message} /> : null}
          <Button label={t('signup.send')} large busy={busy} disabled={!form.phone.trim() || !form.terms} onPress={() => void send()} testID="send-code" />
          <Row wrap>
            <Button label={t('social.apple')} tone="secondary" hint={t('social.hint')} onPress={() => void social()} style={styles.half} />
            <Button label={t('social.google')} tone="secondary" hint={t('social.hint')} onPress={() => void social()} style={styles.half} />
          </Row>
          <Row wrap style={styles.center}>
            <Body tone="small">{t('signup.haveAccount')}</Body>
            <Link label={t('common.signIn')} onPress={() => router.replace('/sign-in')} />
          </Row>
        </>
      }
    >
      <Title>{t('signup.title')}</Title>
      <Body tone="muted">{t('signup.lede')}</Body>
      <View style={styles.fields}>
        <Field
          label={t('field.mobile')}
          value={form.phone}
          onChangeText={(v) => change('phone', v)}
          error={errors.phone}
          keyboardType="phone-pad"
          textContentType="telephoneNumber"
          autoComplete="tel"
          returnKeyType="next"
          onSubmitEditing={() => nameRef.current?.focus()}
          testID="field-phone"
        />
        <Field
          ref={nameRef}
          label={t('field.fullName')}
          placeholder={t('field.fullNamePh')}
          value={form.fullName}
          onChangeText={(v) => change('fullName', v)}
          error={errors.fullName}
          textContentType="name"
          autoComplete="name"
          autoCapitalize="words"
          returnKeyType="next"
          onSubmitEditing={() => emailRef.current?.focus()}
          testID="field-name"
        />
        <Field
          ref={emailRef}
          label={t('field.email')}
          placeholder={t('field.emailPh')}
          value={form.email}
          onChangeText={(v) => change('email', v)}
          error={errors.email}
          keyboardType="email-address"
          textContentType="emailAddress"
          autoComplete="email"
          autoCapitalize="none"
          autoCorrect={false}
          testID="field-email"
        />
        <Checkbox checked={form.terms} onChange={(v) => change('terms', v)} label={t('signup.terms')} error={errors.terms} testID="terms">
          <TermsLabel />
        </Checkbox>
        {wordings.data?.items.length ? (
          <View style={styles.marketing} accessibilityRole="none">
            <Body tone="item">{t('signup.marketingTitle')}</Body>
            {wordings.data.items.map((i) => (
              <Checkbox key={i.category} checked={!!ticked[i.category]} onChange={(v) => setTicked((x) => ({ ...x, [i.category]: v }))} label={i.wording} testID={`consent-${i.category}`}>
                <Body tone="small">{i.wording}</Body>
              </Checkbox>
            ))}
            <Body tone="small">{t('signup.marketingNote', { requester: wordings.data.requester })}</Body>
          </View>
        ) : null}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  fields: { gap: 14, paddingTop: space[2] },
  // two to a row when they fit; "Continue with Apple" in French or at a large text size takes its own row
  half: { flexGrow: 1, flexBasis: 180, paddingHorizontal: space[3] },
  center: { justifyContent: 'center' },
  marketing: { gap: space[2] },
});
