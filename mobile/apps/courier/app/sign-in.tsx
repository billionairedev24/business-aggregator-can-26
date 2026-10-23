import * as Linking from 'expo-linking';
import { useRef, useState } from 'react';
import { Image, Share, StyleSheet, Text, TextInput, View, type TextInputProps } from 'react-native';

import { ApiError, LINE, Logo, NetworkError, radius, Photo, PhotoWell, photoFor, semantic, space, textStyles, type PendingSignIn } from '@northline/mobile-kit';

import type { PasswordStep, SignedIn, TotpSetup } from '../src/api/auth';
import { useAuth } from '../src/auth';
import { LanguageSwitch } from '../src/components/LanguageSwitch';
import { Banner, Body, Button, Check, Heading, Screen } from '../src/components/ui';
import { useOutbox } from '../src/hooks';
import { useI18n } from '../src/i18n';
import { services } from '../src/services';

type Step = 'credentials' | 'factor' | 'enrol' | 'codes' | 'forgot' | 'reset' | 'reset-done';

/**
 * Courier sign-in (S-159 on S-154's accounts): email and password, then the account's second factor — the
 * authenticator's code or a backup code; an account without one sets up an authenticator here first (courier
 * accounts always sign in with two factors) and saves its backup codes. Forgot password emails a code. Every path
 * ends in the kit's AppSignIn hand-off to the same PKCE + DPoP tokens as before (S-29); the system browser stays for
 * a passkey sign-in.
 */
export default function SignIn() {
  const { t } = useI18n();
  const { signIn, signedIn, ended } = useAuth();
  const { pending } = useOutbox();
  const [step, setStep] = useState<Step>('credentials');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [confirm, setConfirm] = useState('');
  const [factors, setFactors] = useState<string[]>([]);
  const [backup, setBackup] = useState(false);
  const [totp, setTotp] = useState<TotpSetup | null>(null);
  const [codes, setCodes] = useState<string[]>([]);
  const [saved, setSaved] = useState(false);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const signing = useRef<{ pending: PendingSignIn | null; continueTo: string | null }>({ pending: null, continueTo: null });

  const problem = (e: unknown) => {
    if (e instanceof NetworkError) return t('signIn.network');
    if (e instanceof ApiError) {
      if (e.status === 422) {
        const first = e.errors[0];
        if (first) setFieldError(first.message);
        return null;
      }
      if (e.status === 429) return t('signIn.tooMany');
      if (e.status === 409) return t('signIn.restart');
      return e.message;
    }
    return t('signIn.failed', { detail: e instanceof Error ? e.message : '' });
  };

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setMessage(null);
    setFieldError(null);
    try {
      await action();
    } catch (e) {
      const m = problem(e);
      if (m) setMessage(m);
      if (e instanceof ApiError && e.status === 409) setStep('credentials');
    } finally {
      setBusy(false);
    }
  };

  const keep = (answer: SignedIn | null | undefined) => {
    if (answer?.continueTo) signing.current.continueTo = answer.continueTo;
  };

  /** The hand-off: northline-auth's code for this app's authorization request, exchanged with a DPoP proof. */
  const finish = async (answer: SignedIn | null) => {
    keep(answer);
    const p = signing.current.pending;
    if (!p) throw new Error('no sign-in in progress');
    await services().appSignIn.complete(signing.current.continueTo, p);
    signing.current = { pending: null, continueTo: null };
    signedIn();
  };

  const submitPassword = () => {
    if (!email.trim()) return setFieldError(t('signIn.emailRequired'));
    if (!password) return setFieldError(t('signIn.passwordRequired'));
    void run(async () => {
      signing.current = { pending: await services().appSignIn.begin(), continueTo: null };
      const next: PasswordStep = await services().authApi.signInWithPassword(email, password);
      setPassword('');
      if (next.step === 'factor') {
        setFactors(next.factors ?? []);
        setBackup(!(next.factors ?? []).includes('totp') && (next.factors ?? []).includes('backup_code'));
        setStep('factor');
      } else if (next.step === 'enroll') {
        keep(next.session);
        setTotp(await services().authApi.enrolTotpSetup());
        setStep('enrol');
      } else {
        // couriers' accounts are made by Northline with a confirmed email; a pending one finishes on the site
        setMessage(t('signIn.restart'));
      }
    });
  };

  const submitFactor = () => {
    if (!code.trim() || (!backup && !/^\d{6}$/.test(code.trim()))) return setFieldError(t('factor.codeRequired'));
    void run(async () => {
      const api = services().authApi;
      await finish(backup ? await api.signInBackupCode(code) : await api.signInTotp(code));
    });
  };

  const submitEnrol = () => {
    if (!/^\d{6}$/.test(code.trim())) return setFieldError(t('factor.codeRequired'));
    void run(async () => {
      const enrolled = await services().authApi.enrolTotp(code);
      keep(enrolled.session);
      setCodes(enrolled.backupCodes);
      setCode('');
      setStep('codes');
    });
  };

  const browser = async () => {
    setBusy(true);
    setMessage(null);
    const result = await signIn();
    setBusy(false);
    if (!result.ok) {
      setMessage(result.reason === 'cancelled' ? t('signIn.cancelled') : result.reason === 'webOnly' ? t('signIn.webOnly') : t('signIn.failed', { detail: result.detail ?? '' }));
    }
  };

  const error = fieldError ? <Banner tone="error">{fieldError}</Banner> : null;
  const notice = message ? <Banner tone="error">{message}</Banner> : null;

  let content;
  if (step === 'factor') {
    const passkeyOnly = factors.length > 0 && !factors.includes('totp') && !factors.includes('backup_code');
    content = passkeyOnly ? (
      <>
        <Body>{t('factor.passkeyOnly')}</Body>
        <Button label={t('signIn.browser')} onPress={() => void browser()} busy={busy} testID="browser-sign-in" />
      </>
    ) : (
      <>
        <Heading level={2}>{backup ? t('factor.backupTitle') : t('factor.title')}</Heading>
        <LabeledInput label={t('factor.code')} value={code} onText={(v) => { setCode(v); setFieldError(null); }} testID="factor-code" keyboardType={backup ? 'default' : 'number-pad'} textContentType="oneTimeCode" autoComplete="one-time-code" onSubmitEditing={submitFactor} />
        {error}
        <Button label={t('factor.submit')} large onPress={submitFactor} busy={busy} testID="factor-submit" />
        {factors.includes('backup_code') && !backup ? <Button label={t('factor.useBackup')} tone="ghost" onPress={() => { setBackup(true); setCode(''); }} testID="use-backup" /> : null}
        {factors.includes('totp') && backup ? <Button label={t('factor.useTotp')} tone="ghost" onPress={() => { setBackup(false); setCode(''); }} testID="use-totp" /> : null}
      </>
    );
  } else if (step === 'enrol' && totp) {
    content = (
      <>
        <Heading level={2}>{t('enrol.title')}</Heading>
        <Body>{t('enrol.body')}</Body>
        <View style={styles.qr}><Image source={{ uri: totp.qrCode }} style={styles.qrImage} accessibilityLabel={t('enrol.qr')} accessibilityRole="image" /></View>
        <Button label={t('enrol.open')} tone="secondary" onPress={() => void Linking.openURL(totp.otpauthUri).catch(() => undefined)} testID="enrol-open" />
        <Text selectable style={styles.small}>{t('enrol.key', { secret: totp.secret })}</Text>
        <LabeledInput label={t('factor.code')} value={code} onText={(v) => { setCode(v); setFieldError(null); }} testID="enrol-code" keyboardType="number-pad" textContentType="oneTimeCode" onSubmitEditing={submitEnrol} />
        {error}
        <Button label={t('factor.submit')} large onPress={submitEnrol} busy={busy} testID="enrol-submit" />
      </>
    );
  } else if (step === 'codes') {
    content = (
      <>
        <Heading level={2}>{t('codes.title')}</Heading>
        <Body>{t('codes.body')}</Body>
        <View style={styles.codes}>{codes.map((c) => <Text key={c} selectable style={styles.code}>{c}</Text>)}</View>
        <Button label={t('codes.share')} tone="secondary" onPress={() => void Share.share({ message: codes.join('\n') }).catch(() => undefined)} testID="codes-share" />
        <Check label={t('codes.saved')} on={saved} onPress={() => { setSaved(!saved); setFieldError(null); }} testID="codes-saved" />
        {error}
        <Button label={t('codes.continue')} large busy={busy} onPress={() => (saved ? void run(() => finish(null)) : setFieldError(t('codes.savedRequired')))} testID="codes-continue" />
      </>
    );
  } else if (step === 'forgot' || step === 'reset' || step === 'reset-done') {
    content = step === 'reset-done' ? (
      <>
        <Banner tone="info" role="none">{t('forgot.done')}</Banner>
        <Button label={t('signIn.button')} large onPress={() => setStep('credentials')} testID="back-to-sign-in" />
      </>
    ) : step === 'forgot' ? (
      <>
        <Heading level={2}>{t('forgot.title')}</Heading>
        <Body>{t('forgot.body')}</Body>
        <LabeledInput label={t('signIn.email')} value={email} onText={(v) => { setEmail(v); setFieldError(null); }} testID="field-email" keyboardType="email-address" autoComplete="email" />
        {error}
        <Button label={t('forgot.send')} large busy={busy} onPress={() => (email.trim() ? void run(async () => { await services().authApi.forgotPassword(email); setStep('reset'); }) : setFieldError(t('signIn.emailRequired')))} testID="forgot-send" />
        <Button label={t('common.back')} tone="ghost" onPress={() => setStep('credentials')} />
      </>
    ) : (
      <>
        <Heading level={2}>{t('forgot.title')}</Heading>
        <Body>{t('forgot.codeBody')}</Body>
        <LabeledInput label={t('forgot.code')} value={code} onText={(v) => { setCode(v); setFieldError(null); }} testID="reset-code" keyboardType="number-pad" textContentType="oneTimeCode" />
        <LabeledInput label={t('forgot.newPassword')} value={password} onText={(v) => { setPassword(v); setFieldError(null); }} testID="reset-password" secureTextEntry textContentType="newPassword" />
        <LabeledInput label={t('forgot.confirm')} value={confirm} onText={(v) => { setConfirm(v); setFieldError(null); }} testID="reset-confirm" secureTextEntry textContentType="newPassword" />
        {error}
        <Button
          label={t('forgot.save')}
          large
          busy={busy}
          onPress={() => {
            if (!/^\d{6}$/.test(code.trim())) return setFieldError(t('factor.codeRequired'));
            if ([...password.normalize('NFKC')].length < 12) return setFieldError(t('forgot.tooShort'));
            if (password !== confirm) return setFieldError(t('forgot.mismatch'));
            void run(async () => {
              await services().authApi.resetPassword(code, password, confirm);
              setCode('');
              setPassword('');
              setConfirm('');
              setStep('reset-done');
            });
          }}
          testID="reset-save"
        />
      </>
    );
  } else {
    content = (
      <>
        <Body>{t('signIn.body')}</Body>
        <LabeledInput label={t('signIn.email')} value={email} onText={(v) => { setEmail(v); setFieldError(null); }} testID="field-email" keyboardType="email-address" autoComplete="email" textContentType="username" />
        <LabeledInput label={t('signIn.password')} value={password} onText={(v) => { setPassword(v); setFieldError(null); }} testID="field-password" secureTextEntry autoComplete="current-password" textContentType="password" onSubmitEditing={submitPassword} />
        {error}
        <Button label={t('signIn.button')} large onPress={submitPassword} busy={busy} testID="sign-in-button" />
        <Button label={t('signIn.forgot')} tone="ghost" onPress={() => { setStep('forgot'); setFieldError(null); setMessage(null); }} testID="forgot" />
        <Button label={t('signIn.browser')} tone="secondary" onPress={() => void browser()} testID="browser-sign-in" />
        <Body muted>{t('signIn.browserNote')}</Body>
      </>
    );
  }

  return (
    <Screen testID="sign-in">
      <View style={styles.brand}>
        {/* the palette's mark and wordmark (brand.json), with the portal word — AT DR:31 LOGIN */}
        <Logo size={36} portal={t('signIn.portal')} />
        {step === 'credentials' ? <Hero /> : null}
        <Heading>{t('signIn.title')}</Heading>
      </View>
      {ended ? <Banner tone="warn">{t('signIn.ended')}</Banner> : null}
      {pending.length > 0 ? <Banner tone="warn">{t('signIn.unsent', { n: pending.length })}</Banner> : null}
      {content}
      {notice}
      <View style={styles.spacer} />
      <LanguageSwitch />
    </Screen>
  );
}

/** The courier's photo (the library's hero), else the dashed well with the truck: never a drawing. */
function Hero() {
  const photo = photoFor('hero/courier', 'wide');
  return <View style={styles.art}>{photo ? <Photo slot={[]} photo={photo} crop="wide" style={StyleSheet.absoluteFill} /> : <PhotoWell icon="truck" inset={0} />}</View>;
}

const styles = StyleSheet.create({
  brand: { paddingTop: space[4], gap: space[4] },
  art: { width: '100%', aspectRatio: 16 / 9, borderRadius: radius.panel, overflow: 'hidden', backgroundColor: semantic.well },
  spacer: { flex: 1, minHeight: space[6] },
  field: { gap: space[1] },
  input: { ...StyleSheet.flatten(textStyles.body), minHeight: 48, borderWidth: LINE.hair, borderColor: semantic['border-control'], borderRadius: radius.panel, paddingHorizontal: space[3], backgroundColor: semantic.surface },
  qr: { alignItems: 'center', padding: space[3], backgroundColor: semantic.surface, borderRadius: radius.panel },
  qrImage: { width: 180, height: 180 },
  codes: { gap: space[1], padding: space[3], backgroundColor: semantic.surface, borderRadius: radius.panel },
  code: { ...StyleSheet.flatten(textStyles.body), fontVariant: ['tabular-nums'] },
  small: { ...StyleSheet.flatten(textStyles.body), color: semantic['text-muted'] },
});

/** A labelled text field (the courier app has no form kit of its own). */
function LabeledInput({ label, value, onText, ...props }: TextInputProps & { label: string; value: string; onText: (v: string) => void; testID: string }) {
  return (
    <View style={styles.field}>
      <Body strong>{label}</Body>
      <TextInput accessibilityLabel={label} value={value} onChangeText={onText} style={styles.input} autoCapitalize="none" autoCorrect={false} {...props} />
    </View>
  );
}
