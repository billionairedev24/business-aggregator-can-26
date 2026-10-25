import Constants from 'expo-constants';
import { useRouter } from 'expo-router';
import { useState } from 'react';

import { StyleSheet, View } from 'react-native';

import { Logo, SIZE, radius, semantic, space } from '@northline/mobile-kit';

import { useAuth } from '../../../src/auth';
import { LanguageSwitch } from '../../../src/components/LanguageSwitch';
import { Banner, Body, Button, Card, IconBadge, Screen } from '../../../src/components/ui';
import { useMe, useOutbox } from '../../../src/hooks';
import { useI18n, type MessageKey } from '../../../src/i18n';
import { ThisPhone } from '../../../src/push/PhonePush';

/**
 * Profile (redesign spec § 2.5 → AT DR:209 SYNC / PROFILE): language, run notifications on this phone, what is shared,
 * sign-out (which warns when actions are still on the phone), the version.
 */
export default function AccountScreen() {
  const { t } = useI18n();
  const { signOut } = useAuth();
  const { pending } = useOutbox();
  const [confirming, setConfirming] = useState(false);
  const me = useMe();
  const router = useRouter();

  const out = () => {
    if (pending.length > 0 && !confirming) return setConfirming(true);
    void signOut();
  };

  return (
    <Screen testID="account-screen" title={t('tab.profile')}>
      <Card>
        <View style={styles.head}>
          <IconBadge name="truck" tone="dark" size={52} />
          <View style={styles.flex}>
            <Logo size={SIZE['header-mark'] - space[1.5]} portal={t('signIn.portal')} />
            {me.data?.vehicle ? <Body muted>{t('shift.vehicle', { vehicle: t(`vehicle.${me.data.vehicle}` as MessageKey) })}</Body> : null}
          </View>
        </View>
      </Card>
      <Button tone="secondary" icon="receipt" label={t('earnings.open')} onPress={() => router.push('/earnings')} testID="open-earnings" />
      <LanguageSwitch />
      <ThisPhone />
      <View style={styles.sunken}>
        <Body>{t('account.privacy')}</Body>
      </View>
      {confirming ? <Banner tone="warn">{t('account.signOutUnsent', { n: pending.length })}</Banner> : null}
      <Button tone="danger" large icon="sign-out" label={confirming ? t('account.signOutAnyway') : t('account.signOut')} onPress={out} testID="sign-out" />
      {confirming ? <Button tone="ghost" label={t('common.cancel')} onPress={() => setConfirming(false)} /> : null}
      <Body muted>{t('account.version', { version: Constants.expoConfig?.version ?? '' })}</Body>
    </Screen>
  );
}

const styles = StyleSheet.create({
  head: { flexDirection: 'row', alignItems: 'center', gap: space[3] },
  flex: { flex: 1, minWidth: 0, gap: space[1] },
  sunken: { padding: space[4], borderRadius: radius.panel, backgroundColor: semantic.well },
});
