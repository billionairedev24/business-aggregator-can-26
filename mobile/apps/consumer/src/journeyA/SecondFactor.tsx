import * as Linking from 'expo-linking';
import { router } from 'expo-router';
import { useState } from 'react';
import { Image, StyleSheet, Text, View } from 'react-native';

import { colors, radius, space } from '@northline/mobile-kit';

import { HandoffError, useAccountFlow } from '../auth/AccountFlow';
import { fieldErrors, flowError, isRestart, validateCode } from '../auth/rules';
import { useI18n } from '../i18n';
import { CodeInput } from '../ui/CodeInput';
import { Body, Button, Notice, Option, Tag, Title, type } from '../ui/primitives';
import { Screen } from '../ui/screen';

type Choice = 'passkey' | 'totp' | 'sms';

/**
 * A4 Second factor, as far as the consumer design allows on the phone (DECISIONS S-98):
 * - **Passkey** (the design's recommendation) is shown but can't be picked: creating one needs a native passkey module
 *   the app doesn't have yet; the line under the list says to add one later on the Northline site.
 * - **Authenticator app**: "Scan QR code" shows the QR (for another device), the key, and "Open my authenticator app"
 *   (the `otpauth://` link, for an authenticator on this phone); the 6-digit code it shows finishes the account.
 * - **SMS code**: the account without a second factor (S-62: "SMS code · backup only", `POST /register/complete`).
 * Either way the account is created and the app is signed in (the hand-off), then Location.
 */
export function SecondFactor() {
  const { t } = useI18n();
  const flow = useAccountFlow();
  const [choice, setChoice] = useState<Choice>('totp');
  const [step, setStep] = useState<'choose' | 'totp'>('choose');
  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [handoffFailed, setHandoffFailed] = useState(false);
  const [busy, setBusy] = useState(false);

  const done = () => router.replace('/location');

  const fail = (e: unknown) => {
    // the account exists and the auth session is signed in, but the tokens didn't arrive: offer to finish
    if (e instanceof HandoffError) setHandoffFailed(true);
    const fields = fieldErrors(e, t);
    if (fields.code) setCodeError(fields.code);
    setMessage(flowError(e, t) ?? null);
    if (isRestart(e)) {
      flow.reset();
      router.replace('/sign-up');
    }
  };

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setMessage(null);
    try {
      await action();
      done();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const go = async () => {
    if (choice === 'sms') return run(() => flow.finishWithSms());
    if (choice === 'totp') {
      setBusy(true);
      setMessage(null);
      try {
        await flow.startTotp();
        setStep('totp');
      } catch (e) {
        fail(e);
      } finally {
        setBusy(false);
      }
    }
  };

  const verifyTotp = () => {
    const invalid = validateCode(code, t);
    setCodeError(invalid ?? null);
    if (!invalid) void run(() => flow.finishWithTotp(code));
  };

  if (handoffFailed) {
    return (
      <Screen title={t('title.mfa')} testID="second-factor" footer={<Button label={t('done.finish')} large busy={busy} onPress={() => void run(() => flow.retryFinish())} />}>
        <Notice message={message ?? t('done.failed')} />
      </Screen>
    );
  }

  if (step === 'totp' && flow.totp) {
    const totp = flow.totp;
    return (
      <Screen
        title={t('title.mfa')}
        onBack={() => setStep('choose')}
        testID="totp"
        footer={
          <>
            {message ? <Notice message={message} /> : null}
            <Button label={t('otp.verify')} large busy={busy} onPress={verifyTotp} testID="totp-verify" />
          </>
        }
      >
        <Title>{t('mfa.totp')}</Title>
        <Body tone="muted">{t('totp.help')}</Body>
        <View style={styles.qr}>
          <Image source={{ uri: totp.qrCode }} style={styles.qrImage} accessibilityLabel={t('totp.qrAlt')} accessibilityRole="image" />
        </View>
        <Button label={t('totp.open')} tone="secondary" onPress={() => void Linking.openURL(totp.otpauthUri).catch(() => undefined)} testID="totp-open" />
        <Text selectable style={type.small}>
          {t('totp.key', { secret: totp.secret })}
        </Text>
        <CodeInput value={code} onChange={(v) => { setCode(v); setCodeError(null); }} label={t('otp.code')} error={codeError} testID="totp-input" />
      </Screen>
    );
  }

  const cta = choice === 'sms' ? t('mfa.continueSms') : t('mfa.scanQr');
  return (
    <Screen
      title={t('title.mfa')}
      testID="second-factor"
      footer={
        <>
          {message ? <Notice message={message} /> : null}
          <Button label={cta} large busy={busy} onPress={() => void go()} testID="mfa-continue" />
        </>
      }
    >
      <Tag label={t('mfa.tag')} tone="accent" />
      <Title>{t('mfa.title')}</Title>
      <Body tone="muted">{t('mfa.lede')}</Body>
      <View accessibilityRole="radiogroup" style={styles.options}>
        <Option selected={false} disabled onPress={() => setChoice('passkey')} title={t('mfa.passkey')} description={t('mfa.passkeyDesc')} tag={t('mfa.recommended')} testID="mfa-passkey" />
        <Option selected={choice === 'totp'} onPress={() => setChoice('totp')} title={t('mfa.totp')} description={t('mfa.totpDesc')} tag={t('mfa.strong')} testID="mfa-totp" />
        <Option selected={choice === 'sms'} onPress={() => setChoice('sms')} title={t('mfa.sms')} description={t('mfa.smsDesc')} tag={t('mfa.basic')} testID="mfa-sms" />
      </View>
      <Body tone="small">{t('mfa.passkeyLater')}</Body>
      <Body tone="small">{t('mfa.backup')}</Body>
    </Screen>
  );
}

const styles = StyleSheet.create({
  options: { gap: 8 },
  qr: { alignItems: 'center', padding: space[3], backgroundColor: colors.surface, borderRadius: radius.md },
  qrImage: { width: 180, height: 180 },
});
