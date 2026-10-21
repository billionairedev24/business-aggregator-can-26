import { router } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { space } from '@northline/mobile-kit';

import { useAccountFlow } from '../auth/AccountFlow';
import { fieldErrors, flowError, validateCode, validateEmail, validatePassword } from '../auth/rules';
import { useI18n } from '../i18n';
import { CodeInput } from '../ui/CodeInput';
import { Body, Button, Field, Notice, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';

/**
 * Forgot password (S-154's `password/forgot` + `password/reset`, S-159 in the app): the email, then the emailed code
 * and a new password. The reset ends every session and still asks for the second factor at the next sign-in; the
 * app goes back to Sign in.
 */
export function ForgotPassword() {
  const { t } = useI18n();
  const flow = useAccountFlow();
  const [step, setStep] = useState<'email' | 'code' | 'done'>('email');
  const [email, setEmail] = useState(flow.identifier);
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [errors, setErrors] = useState<Record<string, string | undefined>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setMessage(null);
    try {
      await action();
    } catch (e) {
      setErrors(fieldErrors(e, t));
      setMessage(flowError(e, t) ?? null);
    } finally {
      setBusy(false);
    }
  };

  const send = () => {
    const invalid = validateEmail(email, t);
    setErrors({ email: invalid });
    if (!invalid) void run(async () => { await flow.forgotPassword(email); setStep('code'); });
  };

  const save = () => {
    const found = {
      code: validateCode(code, t),
      password: validatePassword(password, t, email),
      passwordConfirm: confirm === password ? undefined : t('authErr.passwordConfirm'),
    };
    setErrors(found);
    if (!found.code && !found.password && !found.passwordConfirm) void run(async () => { await flow.resetPassword(code, password, confirm); setStep('done'); });
  };

  if (step === 'done') {
    return (
      <Screen title={t('title.forgot')} testID="forgot-password" footer={<Button label={t('signin.submit')} large onPress={() => router.replace('/sign-in')} testID="back-to-sign-in" />}>
        <Notice message={t('forgot.done')} tone="info" />
      </Screen>
    );
  }

  return (
    <Screen
      title={t('title.forgot')}
      testID="forgot-password"
      footer={
        <>
          {message ? <Notice message={message} /> : null}
          {step === 'email'
            ? <Button label={t('forgot.send')} large busy={busy} onPress={send} testID="forgot-send" />
            : <Button label={t('forgot.save')} large busy={busy} onPress={save} testID="forgot-save" />}
        </>
      }
    >
      <Title>{t('forgot.title')}</Title>
      {step === 'email' ? (
        <>
          <Body tone="muted">{t('forgot.lede')}</Body>
          <View style={styles.fields}>
            <Field label={t('field.email')} value={email} onChangeText={setEmail} error={errors.email} keyboardType="email-address" autoCapitalize="none" autoCorrect={false} autoComplete="email" testID="field-email" />
          </View>
        </>
      ) : (
        <>
          <Body tone="muted">{t('forgot.codeLede', { email: flow.email || email })}</Body>
          <View style={styles.fields}>
            <CodeInput value={code} onChange={setCode} label={t('otp.code')} error={errors.code ?? null} />
            <Field label={t('forgot.newPassword')} hint={t('field.passwordHint')} value={password} onChangeText={setPassword} error={errors.password} secureTextEntry autoCapitalize="none" autoComplete="new-password" textContentType="newPassword" testID="field-password" />
            <Field label={t('field.passwordConfirm')} value={confirm} onChangeText={setConfirm} error={errors.passwordConfirm} secureTextEntry autoCapitalize="none" autoComplete="new-password" textContentType="newPassword" testID="field-password-confirm" />
          </View>
        </>
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({ fields: { gap: 14, paddingTop: space[2] } });
