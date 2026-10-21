import * as Linking from 'expo-linking';
import { router } from 'expo-router';
import { useState } from 'react';
import { Image, Share, StyleSheet, Text, View } from 'react-native';

import { colors, radius, space } from '@northline/mobile-kit';

import { HandoffError, useAccountFlow } from '../auth/AccountFlow';
import { fieldErrors, flowError, isRestart, validateCode } from '../auth/rules';
import { useI18n } from '../i18n';
import { CodeInput } from '../ui/CodeInput';
import { Body, Button, Checkbox, Notice, Option, Tag, Title, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { useAfterSignIn } from './useAfterSignIn';

/**
 * A4 Second factor on S-154's accounts (S-159): offered after the email is confirmed (or at a sign-in of an account
 * without one) — customers may skip it ("Not now"; the Studio and the console require one, S-154).
 * - **Passkey** is shown but can't be picked: creating one needs a native passkey module the app doesn't have yet.
 * - **Authenticator app**: the QR (for another device), the key, and "Open my authenticator app" (the `otpauth://`
 *   link); its 6-digit code enrols it (`/api/auth/enroll/totp/verify`, the session then has `acr=mfa`) and the ten
 *   backup codes are shown once, "I saved them" required to go on.
 * Either way the app is then signed in (the hand-off), then Location.
 */
export function SecondFactor() {
  const { t } = useI18n();
  const flow = useAccountFlow();
  const [step, setStep] = useState<'choose' | 'totp' | 'codes'>('choose');
  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [handoffFailed, setHandoffFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [savedError, setSavedError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const afterSignIn = useAfterSignIn();
  // a new account asks where to deliver (A5); a sign-in lands where it always does
  const done = () => (flow.mode === 'register' ? router.replace('/location?step=address') : afterSignIn());

  const fail = (e: unknown) => {
    // the account exists and the auth session is signed in, but the tokens didn't arrive: offer to finish
    if (e instanceof HandoffError) setHandoffFailed(true);
    const fields = fieldErrors(e, t);
    if (fields.code) setCodeError(fields.code);
    setMessage(flowError(e, t) ?? null);
    if (isRestart(e)) {
      flow.reset();
      router.replace('/sign-in');
    }
  };

  const run = async (action: () => Promise<void>, after: () => void = done) => {
    setBusy(true);
    setMessage(null);
    try {
      await action();
      after();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const verifyTotp = () => {
    const invalid = validateCode(code, t);
    setCodeError(invalid ?? null);
    if (!invalid) void run(async () => void (await flow.finishTotp(code)), () => setStep('codes'));
  };

  if (handoffFailed) {
    return (
      <Screen title={t('title.mfa')} testID="second-factor" footer={<Button label={t('done.finish')} large busy={busy} onPress={() => void run(() => flow.retryFinish())} />}>
        <Notice message={message ?? t('done.failed')} />
      </Screen>
    );
  }

  if (step === 'codes' && flow.backupCodes) {
    const codes = flow.backupCodes;
    return (
      <Screen
        title={t('title.mfa')}
        testID="backup-codes"
        footer={
          <>
            {message ? <Notice message={message} /> : null}
            <Button
              label={t('backup.continue')}
              large
              busy={busy}
              onPress={() => {
                if (!saved) return setSavedError(t('backup.savedRequired'));
                void run(() => flow.finishEnrolment());
              }}
              testID="backup-continue"
            />
          </>
        }
      >
        <Title>{t('backup.title')}</Title>
        <Body tone="muted">{t('backup.lede')}</Body>
        <View style={styles.codes} accessible accessibilityLabel={codes.join(', ')}>
          {codes.map((c) => <Text key={c} selectable style={[type.body, styles.code]}>{c}</Text>)}
        </View>
        {/* the system share sheet: a password manager, Notes, or a message to oneself (nothing native to add) */}
        <Button label={copied ? t('backup.copied') : t('backup.copy')} tone="secondary" onPress={() => void Share.share({ message: codes.join('\n') }).then(() => setCopied(true), () => setCopied(false))} testID="backup-copy" />
        <Checkbox checked={saved} onChange={(v) => { setSaved(v); setSavedError(null); }} label={t('backup.saved')} error={savedError} testID="backup-saved">
          <Body>{t('backup.saved')}</Body>
        </Checkbox>
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

  return (
    <Screen
      title={t('title.mfa')}
      testID="second-factor"
      footer={
        <>
          {message ? <Notice message={message} /> : null}
          <Button label={t('mfa.setUp')} large busy={busy} onPress={() => void run(async () => void (await flow.startTotp()), () => setStep('totp'))} testID="mfa-continue" />
          <Button label={t('mfa.notNow')} tone="secondary" disabled={busy} onPress={() => void run(() => flow.finishEnrolment())} testID="mfa-skip" />
        </>
      }
    >
      <Tag label={t('mfa.tag')} tone="accent" />
      <Title>{t('mfa.title')}</Title>
      <Body tone="muted">{t('mfa.lede')}</Body>
      <View accessibilityRole="radiogroup" style={styles.options}>
        <Option selected={false} disabled onPress={() => undefined} title={t('mfa.passkey')} description={t('mfa.passkeyDesc')} tag={t('mfa.recommended')} testID="mfa-passkey" />
        <Option selected onPress={() => undefined} title={t('mfa.totp')} description={t('mfa.totpDesc')} tag={t('mfa.strong')} testID="mfa-totp" />
      </View>
      <Body tone="small">{t('mfa.passkeyLater')}</Body>
    </Screen>
  );
}

const styles = StyleSheet.create({
  options: { gap: 8 },
  qr: { alignItems: 'center', padding: space[3], backgroundColor: colors.surface, borderRadius: radius.md },
  qrImage: { width: 180, height: 180 },
  codes: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, padding: space[3], backgroundColor: colors.surface, borderRadius: radius.md },
  code: { width: '45%', fontVariant: ['tabular-nums'] },
});
