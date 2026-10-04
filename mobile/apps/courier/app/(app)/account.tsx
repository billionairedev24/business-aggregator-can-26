import Constants from 'expo-constants';
import { useState } from 'react';

import { useAuth } from '../../src/auth';
import { LanguageSwitch } from '../../src/components/LanguageSwitch';
import { Banner, Body, Button, Card, Screen } from '../../src/components/ui';
import { useOutbox } from '../../src/hooks';
import { useI18n } from '../../src/i18n';
import { ThisPhone } from '../../src/push/PhonePush';

/** Language, run notifications on this phone, what is shared, sign-out (which warns when actions are still on the phone). */
export default function AccountScreen() {
  const { t } = useI18n();
  const { signOut } = useAuth();
  const { pending } = useOutbox();
  const [confirming, setConfirming] = useState(false);

  const out = () => {
    if (pending.length > 0 && !confirming) return setConfirming(true);
    void signOut();
  };

  return (
    <Screen testID="account-screen">
      <LanguageSwitch />
      <ThisPhone />
      <Card>
        <Body>{t('account.privacy')}</Body>
      </Card>
      {confirming ? <Banner tone="warn">{t('account.signOutUnsent', { n: pending.length })}</Banner> : null}
      <Button tone="danger" label={confirming ? t('account.signOutAnyway') : t('account.signOut')} onPress={out} testID="sign-out" />
      {confirming ? <Button tone="ghost" label={t('common.cancel')} onPress={() => setConfirming(false)} /> : null}
      <Body muted>{t('account.version', { version: Constants.expoConfig?.version ?? '' })}</Body>
    </Screen>
  );
}
