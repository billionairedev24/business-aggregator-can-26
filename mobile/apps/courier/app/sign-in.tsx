import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { colors, fonts, space } from '@northline/mobile-kit';

import { useAuth } from '../src/auth';
import { LanguageSwitch } from '../src/components/LanguageSwitch';
import { Banner, Body, Button, Heading, Screen } from '../src/components/ui';
import { useOutbox } from '../src/hooks';
import { useI18n } from '../src/i18n';

export default function SignIn() {
  const { t } = useI18n();
  const { signIn, ended } = useAuth();
  const { pending } = useOutbox();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const go = async () => {
    setBusy(true);
    setMessage(null);
    const result = await signIn();
    setBusy(false);
    if (!result.ok) {
      setMessage(
        result.reason === 'cancelled' ? t('signIn.cancelled') : result.reason === 'webOnly' ? t('signIn.webOnly') : t('signIn.failed', { detail: result.detail ?? '' }),
      );
    }
  };

  return (
    <Screen testID="sign-in">
      <View style={styles.brand}>
        <Body style={styles.wordmark}>Northline</Body>
        <Heading>{t('signIn.title')}</Heading>
      </View>
      {ended ? <Banner tone="warn">{t('signIn.ended')}</Banner> : null}
      {pending.length > 0 ? <Banner tone="warn">{t('signIn.unsent', { n: pending.length })}</Banner> : null}
      <Body>{t('signIn.body')}</Body>
      <Button label={t('signIn.button')} onPress={() => void go()} busy={busy} testID="sign-in-button" />
      <Body muted>{t('signIn.browserNote')}</Body>
      {message ? <Banner tone="error">{message}</Banner> : null}
      <View style={styles.spacer} />
      <LanguageSwitch />
    </Screen>
  );
}

const styles = StyleSheet.create({
  brand: { paddingTop: space[8], gap: space[1] },
  wordmark: { fontFamily: fonts.heading, fontSize: 20, color: colors.accent },
  spacer: { flex: 1, minHeight: space[6] },
});
