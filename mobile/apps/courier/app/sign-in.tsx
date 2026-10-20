import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { Logo, radius, space, Scene } from '@northline/mobile-kit';

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
        {/* the palette's mark and wordmark (brand.json), with the portal word — AT DR:31 LOGIN */}
        <Logo size={36} portal={t('signIn.portal')} />
        <Scene slot="hero/courier" subject="courier" tone="sage" fit="contain" style={styles.art} />
        <Heading>{t('signIn.title')}</Heading>
      </View>
      {ended ? <Banner tone="warn">{t('signIn.ended')}</Banner> : null}
      {pending.length > 0 ? <Banner tone="warn">{t('signIn.unsent', { n: pending.length })}</Banner> : null}
      <Body>{t('signIn.body')}</Body>
      <Button label={t('signIn.button')} large onPress={() => void go()} busy={busy} testID="sign-in-button" />
      <Body muted>{t('signIn.browserNote')}</Body>
      {message ? <Banner tone="error">{message}</Banner> : null}
      <View style={styles.spacer} />
      <LanguageSwitch />
    </Screen>
  );
}

const styles = StyleSheet.create({
  brand: { paddingTop: space[4], gap: space[4] },
  art: { height: 180, borderRadius: radius.lg },
  spacer: { flex: 1, minHeight: space[6] },
});
