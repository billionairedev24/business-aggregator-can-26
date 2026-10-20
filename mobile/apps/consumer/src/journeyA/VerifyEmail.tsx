import { router } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { space } from '@northline/mobile-kit';

import { HandoffError, useAccountFlow } from '../auth/AccountFlow';
import { fieldErrors, flowError, isRestart, mmss, validateCode } from '../auth/rules';
import { useI18n } from '../i18n';
import { CodeInput } from '../ui/CodeInput';
import { Body, Button, Link, Notice, Row, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { useOnline } from '../ui/states';
import { useCountdown } from '../ui/useCountdown';
import { useAfterSignIn } from './useAfterSignIn';

/**
 * Confirm the email (S-159 on S-154's `accounts/verify`): the 6-digit code from the email, "Resend in 0:59" then
 * "Send a new code". Six digits typed or pasted submit once by themselves. The account is created and signed in with
 * its password; one without a second factor is offered one next, else the app is signed in.
 */
export function VerifyEmail() {
  const { t } = useI18n();
  const flow = useAccountFlow();
  const online = useOnline();
  const afterSignIn = useAfterSignIn();
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<{ text: string; tone: 'error' | 'info' } | null>(null);
  const [busy, setBusy] = useState(false);
  const [handoffFailed, setHandoffFailed] = useState(false);
  const autoSubmitted = useRef<string | null>(null);
  const wait = useCountdown(flow.resendAt);

  const verify = async (value = code) => {
    const invalid = validateCode(value, t);
    setMessage(null);
    if (invalid) {
      setError(invalid);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const next = await flow.verifyEmail(value);
      if (next === 'enrol') router.replace('/second-factor');
      else afterSignIn();
    } catch (e) {
      if (e instanceof HandoffError) setHandoffFailed(true);
      const fields = fieldErrors(e, t);
      if (fields.code) setError(fields.code);
      const other = flowError(e, t);
      if (other) setMessage({ text: other, tone: 'error' });
      if (isRestart(e)) {
        flow.reset();
        router.replace(flow.mode === 'register' ? '/sign-up' : '/sign-in');
      }
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    if (code.length === 6 && online && autoSubmitted.current !== code && !busy) {
      autoSubmitted.current = code;
      void verify(code);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code, online]);

  const resend = async () => {
    setMessage(null);
    try {
      await flow.resendEmail();
      setCode('');
      setError(null);
      setMessage({ text: t('email.resent'), tone: 'info' });
    } catch (e) {
      setMessage({ text: flowError(e, t) ?? t('common.error'), tone: 'error' });
    }
  };

  if (handoffFailed) {
    return (
      <Screen title={t('title.verifyEmail')} testID="verify-email" footer={<Button label={t('done.finish')} large busy={busy} onPress={() => void flow.retryFinish().then(afterSignIn, () => setMessage({ text: t('done.failed'), tone: 'error' }))} testID="finish-sign-in" />}>
        <Notice message={message?.text ?? t('done.failed')} />
      </Screen>
    );
  }

  return (
    <Screen
      title={t('title.verifyEmail')}
      testID="verify-email"
      footer={
        <>
          {message ? <Notice message={message.text} tone={message.tone} /> : null}
          <Button label={t('otp.verify')} large busy={busy} onPress={() => void verify()} testID="verify-button" />
        </>
      }
    >
      <Title>{t('email.title')}</Title>
      <Row wrap>
        <Body tone="muted">{t('email.sentTo', { email: flow.email })}</Body>
        <Link label={t('otp.edit')} onPress={() => router.replace(flow.mode === 'register' ? '/sign-up' : '/sign-in')} testID="email-edit" />
      </Row>
      <View style={styles.code}>
        <CodeInput value={code} onChange={setCode} label={t('otp.code')} error={error} autoFocus />
      </View>
      <View style={styles.links}>
        {wait > 0 ? <Body tone="small">{t('otp.resendIn', { time: mmss(wait) })}</Body> : <Link label={t('email.resend')} onPress={() => void resend()} testID="email-resend" />}
      </View>
      <Body tone="small">{t('email.spam')}</Body>
    </Screen>
  );
}

const styles = StyleSheet.create({
  code: { paddingTop: space[3] },
  links: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', minHeight: 48 },
});
