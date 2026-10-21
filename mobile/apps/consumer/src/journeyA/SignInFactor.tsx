import { router } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { space } from '@northline/mobile-kit';

import { HandoffError, useAccountFlow } from '../auth/AccountFlow';
import { useAuth } from '../auth/AuthProvider';
import { fieldErrors, flowError, isRestart, validateCode } from '../auth/rules';
import { useI18n } from '../i18n';
import { CodeInput } from '../ui/CodeInput';
import { Body, Button, Field, Link, Notice, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { useAfterSignIn } from './useAfterSignIn';

/**
 * The account's second factor after its password (S-154, S-159): the authenticator's 6-digit code, or one backup code.
 * An account whose only factor is a passkey continues in the browser (the app has no native passkeys yet).
 */
export function SignInFactor() {
  const { t } = useI18n();
  const flow = useAccountFlow();
  const { signInWithBrowser } = useAuth();
  const afterSignIn = useAfterSignIn();
  const totpOk = flow.factors.includes('totp');
  const [kind, setKind] = useState<'totp' | 'backup_code'>(totpOk || !flow.factors.includes('backup_code') ? 'totp' : 'backup_code');
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const passkeyOnly = flow.factors.length > 0 && !flow.factors.includes('totp') && !flow.factors.includes('backup_code');

  const submit = async () => {
    const invalid = kind === 'totp' ? validateCode(code, t) : code.trim() ? undefined : t('authErr.backupRequired');
    setError(invalid ?? null);
    setMessage(null);
    if (invalid) return;
    setBusy(true);
    try {
      await flow.signInFactor(kind, code);
      afterSignIn();
    } catch (e) {
      const fields = fieldErrors(e, t, 'authErr.signInCodeWrong');
      if (fields.code) setError(fields.code);
      setMessage(e instanceof HandoffError ? t('done.failed') : flowError(e, t) ?? null);
      if (isRestart(e)) {
        flow.reset();
        router.replace('/sign-in');
      }
    } finally {
      setBusy(false);
    }
  };

  if (passkeyOnly) {
    return (
      <Screen title={t('title.factor')} testID="sign-in-factor" footer={<Button label={t('factor.continue')} large onPress={() => void signInWithBrowser().then((r) => r.ok && afterSignIn())} />}>
        <Body>{t('factor.passkeyOnly')}</Body>
      </Screen>
    );
  }

  return (
    <Screen
      title={t('title.factor')}
      testID="sign-in-factor"
      footer={
        <>
          {message ? <Notice message={message} /> : null}
          <Button label={t('otp.verify')} large busy={busy} onPress={() => void submit()} testID="factor-submit" />
        </>
      }
    >
      <Title>{kind === 'totp' ? t('factor.title') : t('factor.backup')}</Title>
      <Body tone="muted">{kind === 'totp' ? t('factor.lede') : t('factor.backupLede')}</Body>
      <View style={styles.code}>
        {kind === 'totp' ? (
          <CodeInput value={code} onChange={(v) => { setCode(v); setError(null); }} label={t('factor.code')} error={error} autoFocus testID="factor-code" />
        ) : (
          <Field label={t('factor.backup')} value={code} onChangeText={(v) => { setCode(v); setError(null); }} error={error ?? undefined} autoCapitalize="none" autoCorrect={false} testID="factor-backup" />
        )}
      </View>
      {kind === 'totp' && flow.factors.includes('backup_code') ? (
        <Link label={t('factor.useBackup')} onPress={() => { setKind('backup_code'); setCode(''); setError(null); }} testID="use-backup" />
      ) : kind === 'backup_code' && totpOk ? (
        <Link label={t('factor.useTotp')} onPress={() => { setKind('totp'); setCode(''); setError(null); }} testID="use-totp" />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({ code: { paddingTop: space[3] } });
