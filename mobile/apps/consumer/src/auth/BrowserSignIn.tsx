import { router } from 'expo-router';
import { useState } from 'react';

import { useI18n } from '../i18n';
import { Body, Button } from '../ui/primitives';
import { useAuth } from './AuthProvider';

/**
 * "Sign in in the browser": the `mobile-consumer` authorization request in the system browser, which northline-auth
 * sends to the consumer site's sign-in page (passkeys, Google, Apple — what the app can't do natively yet).
 */
export function BrowserSignIn({ then = '/home', tone = 'secondary' }: { then?: '/home' | '/location'; tone?: 'primary' | 'secondary' }) {
  const { t } = useI18n();
  const { signInWithBrowser } = useAuth();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const go = async () => {
    setBusy(true);
    setMessage(null);
    const result = await signInWithBrowser();
    setBusy(false);
    if (result.ok) {
      router.replace(then);
      return;
    }
    setMessage(
      result.reason === 'cancelled'
        ? t('browserSignIn.cancelled')
        : result.reason === 'webOnly'
          ? t('browserSignIn.webOnly')
          : t('browserSignIn.failed', { detail: result.detail ?? '' }),
    );
  };
  return (
    <>
      <Button label={t('browserSignIn.button')} hint={t('common.opensInBrowser')} tone={tone} busy={busy} onPress={() => void go()} testID="browser-sign-in" />
      {message ? <Body tone="small">{message}</Body> : null}
    </>
  );
}
