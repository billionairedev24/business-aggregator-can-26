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
 * A3 Verify phone: the 6-digit code (filled from the SMS by iOS / Android), "Resend in 0:42" then "Resend code",
 * "Call me instead" (a voice call for landlines), Verify. Six digits typed or filled submit once by themselves. A
 * registration moves on to the second factor; a sign-in is done.
 */
export function Verify() {
  const { t } = useI18n();
  const flow = useAccountFlow();
  const online = useOnline();
  const afterSignIn = useAfterSignIn();
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<{ text: string; tone: 'error' | 'info' } | null>(null);
  const [busy, setBusy] = useState(false);
  const [sending, setSending] = useState(false);
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
      const next = await flow.verify(value);
      if (next === 'mfa') router.replace('/second-factor');
      else afterSignIn();
    } catch (e) {
      if (e instanceof HandoffError) setHandoffFailed(true);
      const fields = fieldErrors(e, t, flow.mode === 'register' ? 'authErr.codeWrong' : 'authErr.signInCodeWrong');
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

  // SMS autofill (or typing the sixth digit) submits once per code
  useEffect(() => {
    if (code.length === 6 && online && autoSubmitted.current !== code && !busy) {
      autoSubmitted.current = code;
      void verify(code);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code, online]);

  const resend = async (channel: 'sms' | 'voice') => {
    setSending(true);
    setMessage(null);
    try {
      await flow.resend(channel);
      setCode('');
      setError(null);
      setMessage({ text: t(channel === 'voice' ? 'otp.calling' : 'otp.resent'), tone: 'info' });
    } catch (e) {
      setMessage({ text: flowError(e, t, channel) ?? t('common.error'), tone: 'error' });
    } finally {
      setSending(false);
    }
  };

  const retryFinish = async () => {
    setBusy(true);
    setMessage(null);
    try {
      await flow.retryFinish();
      afterSignIn();
    } catch (e) {
      setMessage({ text: flowError(e, t) ?? t('done.failed'), tone: 'error' });
    } finally {
      setBusy(false);
    }
  };

  if (handoffFailed) {
    return (
      <Screen title={t('title.otp')} testID="verify" footer={<Button label={t('done.finish')} large busy={busy} onPress={() => void retryFinish()} testID="finish-sign-in" />}>
        <Notice message={message?.text ?? t('done.failed')} />
      </Screen>
    );
  }

  const edit = () => router.replace(flow.mode === 'register' ? '/sign-up' : '/sign-in');

  return (
    <Screen
      title={t('title.otp')}
      testID="verify"
      footer={
        <>
          {message ? <Notice message={message.text} tone={message.tone} /> : null}
          <Button label={t('otp.verify')} large busy={busy} onPress={() => void verify()} testID="verify-button" />
        </>
      }
    >
      <Title>{t('otp.title')}</Title>
      {flow.mode === 'register' && flow.phone ? (
        <Row wrap>
          <Body tone="muted">{t('otp.sentTo', { phone: flow.phone })} ·</Body>
          <Link label={t('otp.edit')} onPress={edit} testID="otp-edit" />
        </Row>
      ) : (
        <Row wrap>
          <Body tone="muted">{t('otp.toAccount')}</Body>
          <Link label={t('otp.edit')} onPress={edit} testID="otp-edit" />
        </Row>
      )}
      <View style={styles.code}>
        <CodeInput value={code} onChange={setCode} label={t('otp.code')} error={error} autoFocus />
      </View>
      <View style={styles.links}>
        {wait > 0 ? (
          <Body tone="small">{t('otp.resendIn', { time: mmss(wait) })}</Body>
        ) : (
          <Link label={t('otp.resend')} onPress={() => void (sending ? undefined : resend('sms'))} testID="otp-resend" />
        )}
        <Link label={t('otp.callMe')} onPress={() => void (sending ? undefined : resend('voice'))} testID="otp-call" />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  code: { paddingTop: space[3] },
  links: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', minHeight: 48 },
});
