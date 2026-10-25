import { router } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { space } from '@northline/mobile-kit';

import { useAccountFlow } from '../auth/AccountFlow';
import { useAuth } from '../auth/AuthProvider';
import { fieldErrors, flowError } from '../auth/rules';
import { useI18n } from '../i18n';
import { Body, Button, Field, Link, Notice, Row, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { useAfterSignIn } from './useAfterSignIn';

/**
 * Sign in by phone (S-98's sign-in, kept as an option since S-159 made email + password the way in): email or mobile,
 * then a 6-digit code to the account's verified phone (S-62). A passkey, Apple or Google sign-in continues on the
 * consumer site in the system browser.
 */
export function SignInPhone() {
  const { t } = useI18n();
  const flow = useAccountFlow();
  const { signInWithBrowser, ended } = useAuth();
  const afterSignIn = useAfterSignIn();
  const [identifier, setIdentifier] = useState(flow.identifier);
  const [error, setError] = useState<string | undefined>();
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const send = async () => {
    setMessage(null);
    if (!identifier.trim()) {
      setError(t('authErr.identifierRequired'));
      return;
    }
    setBusy(true);
    try {
      flow.setIdentifier(identifier);
      await flow.signInByPhone(identifier);
      router.push('/verify');
    } catch (e) {
      setError(fieldErrors(e, t).identifier);
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
      title={t('title.signInPhone')}
      testID="sign-in-phone"
      footer={
        <>
          {message ? <Notice message={message} /> : null}
          <Button label={t('signup.send')} large busy={busy} onPress={() => void send()} testID="send-code" />
          <Button label={t('signin.passkey')} tone="secondary" hint={t('social.hint')} onPress={() => void browser()} testID="browser-sign-in" />
          <Row wrap>
            <Button label={t('social.apple')} tone="secondary" hint={t('social.hint')} onPress={() => void browser()} style={styles.half} />
            <Button label={t('social.google')} tone="secondary" hint={t('social.hint')} onPress={() => void browser()} style={styles.half} />
          </Row>
          <Link label={t('phone.password')} onPress={() => router.replace('/sign-in')} testID="use-password" />
          <Row wrap style={styles.center}>
            <Body tone="small">{t('signin.new')}</Body>
            <Link label={t('common.createAccount')} onPress={() => router.replace('/sign-up')} />
          </Row>
        </>
      }
    >
      {ended ? <Notice message={t('common.signedOut')} tone="info" /> : null}
      <Title>{t('phone.title')}</Title>
      <Body tone="muted">{t('phone.lede')}</Body>
      <View style={styles.fields}>
        <Field
          label={t('field.identifier')}
          value={identifier}
          onChangeText={(v) => {
            setIdentifier(v);
            setError(undefined);
          }}
          error={error}
          autoCapitalize="none"
          autoCorrect={false}
          autoComplete="username"
          textContentType="username"
          returnKeyType="send"
          onSubmitEditing={() => void send()}
          testID="field-identifier"
        />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  fields: { gap: space[3.5], paddingTop: space[2] },
  // two to a row when they fit; "Continue with Apple" in French or at a large text size takes its own row
  half: { flexGrow: 1, flexBasis: 180, paddingHorizontal: space[3] },
  center: { justifyContent: 'center' },
});
