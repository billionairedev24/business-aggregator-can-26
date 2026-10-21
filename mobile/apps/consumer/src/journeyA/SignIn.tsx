import { router } from 'expo-router';
import { useRef, useState } from 'react';
import { StyleSheet, View, type TextInput } from 'react-native';

import { space } from '@northline/mobile-kit';

import { HandoffError, useAccountFlow } from '../auth/AccountFlow';
import { useAuth } from '../auth/AuthProvider';
import { fieldErrors, flowError, validateEmail } from '../auth/rules';
import { useI18n } from '../i18n';
import { Body, Button, Field, Link, Notice, Row, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { useAfterSignIn } from './useAfterSignIn';

/**
 * Sign in on S-154's accounts (S-159): email and password on the app's own screens, then the account's second factor
 * when it has one (authenticator or backup code), the email code when the address isn't confirmed yet, or the offer
 * to add a factor. The phone code stays as an option (Sign in by phone); a passkey, Apple or Google sign-in continues
 * on the consumer site in the system browser. Tokens are S-29's PKCE + DPoP either way.
 */
export function SignIn() {
  const { t } = useI18n();
  const flow = useAccountFlow();
  const { signInWithBrowser, ended } = useAuth();
  const afterSignIn = useAfterSignIn();
  const [email, setEmail] = useState(flow.identifier);
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<{ email?: string; password?: string }>({});
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const passwordRef = useRef<TextInput>(null);

  const submit = async () => {
    setMessage(null);
    const found = { email: validateEmail(email, t), password: password ? undefined : t('authErr.passwordRequired') };
    setErrors(found);
    if (found.email || found.password) return;
    setBusy(true);
    try {
      flow.setIdentifier(email);
      const next = await flow.signIn(email, password);
      setPassword('');
      if (next === 'factor') router.push('/sign-in-factor');
      else if (next === 'verify-email') router.push('/verify-email');
      else if (next === 'enrol') router.replace('/second-factor');
      else afterSignIn();
    } catch (e) {
      if (e instanceof HandoffError) setMessage(t('done.failed'));
      const fields = fieldErrors(e, t);
      setErrors({ email: fields.email, password: fields.password });
      setMessage(flowError(e, t, 'form') ?? null);
    } finally {
      setBusy(false);
    }
  };

  const browser = async () => {
    setMessage(null);
    const r = await signInWithBrowser();
    if (r.ok) afterSignIn();
    else if (r.reason !== 'cancelled') setMessage(r.reason === 'webOnly' ? t('browserSignIn.webOnly') : t('browserSignIn.failed', { detail: r.detail ?? '' }));
  };

  return (
    <Screen
      title={t('title.signin')}
      testID="sign-in"
      footer={
        <>
          {message ? <Notice message={message} /> : null}
          <Button label={t('signin.submit')} large busy={busy} onPress={() => void submit()} testID="sign-in-submit" />
          <Button label={t('signin.phone')} tone="secondary" onPress={() => router.push('/sign-in-phone')} testID="sign-in-phone" />
          <Button label={t('signin.passkey')} tone="secondary" hint={t('social.hint')} onPress={() => void browser()} testID="browser-sign-in" />
          <Row wrap>
            <Button label={t('social.apple')} tone="secondary" hint={t('social.hint')} onPress={() => void browser()} style={styles.half} />
            <Button label={t('social.google')} tone="secondary" hint={t('social.hint')} onPress={() => void browser()} style={styles.half} />
          </Row>
          <Row wrap style={styles.center}>
            <Body tone="small">{t('signin.new')}</Body>
            <Link label={t('common.createAccount')} onPress={() => router.replace('/sign-up')} />
          </Row>
        </>
      }
    >
      {ended ? <Notice message={t('common.signedOut')} tone="info" /> : null}
      <Title>{t('signin.title')}</Title>
      <Body tone="muted">{t('signin.lede')}</Body>
      <View style={styles.fields}>
        <Field
          label={t('field.email')}
          value={email}
          onChangeText={(v) => {
            setEmail(v);
            setErrors((e) => ({ ...e, email: undefined }));
          }}
          error={errors.email}
          keyboardType="email-address"
          autoCapitalize="none"
          autoCorrect={false}
          autoComplete="email"
          textContentType="username"
          returnKeyType="next"
          onSubmitEditing={() => passwordRef.current?.focus()}
          testID="field-email"
        />
        <Field
          ref={passwordRef}
          label={t('field.password')}
          value={password}
          onChangeText={(v) => {
            setPassword(v);
            setErrors((e) => ({ ...e, password: undefined }));
          }}
          error={errors.password}
          secureTextEntry
          autoCapitalize="none"
          autoCorrect={false}
          autoComplete="current-password"
          textContentType="password"
          returnKeyType="go"
          onSubmitEditing={() => void submit()}
          testID="field-password"
        />
        <Link label={t('signin.forgot')} onPress={() => { flow.setIdentifier(email); router.push('/forgot-password'); }} testID="forgot-password" />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  fields: { gap: 14, paddingTop: space[2] },
  half: { flexGrow: 1, flexBasis: 180, paddingHorizontal: space[3] },
  center: { justifyContent: 'center' },
});
