import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { StyleSheet, View } from 'react-native';

import { space } from '@northline/mobile-kit';

import { geoApi } from '../api/geo';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n';
import { useDeliveryLocation } from '../location/DeliveryLocation';
import { services } from '../services';
import { Body, Button, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';

/**
 * A1 Welcome: the value proposition in one breath; Create account, Sign in, or Browse first (browsing needs no account,
 * paying does). The kicker names the province from the region model — the person's, else the platform's default —
 * never a fixed one.
 */
export function Welcome() {
  const { t, locale } = useI18n();
  const { markWelcomed } = useAuth();
  const { location } = useDeliveryLocation();
  const regions = useQuery({
    queryKey: ['geo', 'regions', locale],
    queryFn: () => geoApi(services().api).regions(locale === 'fr-CA' ? 'fr' : 'en'),
    staleTime: 3_600_000,
  });
  const code = location.province ?? regions.data?.defaultProvince;
  const province = regions.data?.provinces.find((p) => p.code === code)?.name;

  const browse = () => {
    void markWelcomed();
    router.replace('/home');
  };

  return (
    <Screen
      testID="welcome"
      footer={
        <>
          <Button label={t('common.createAccount')} large onPress={() => router.push('/sign-up')} testID="welcome-create" />
          <Button label={t('common.signIn')} tone="secondary" onPress={() => router.push('/sign-in')} />
          <Button label={t('welcome.browse')} tone="ghost" onPress={browse} />
        </>
      }
    >
      <View style={styles.top}>
        <Body tone="kicker">{province ? t('welcome.kicker', { province }) : t('app.name')}</Body>
        <View style={styles.hero}>
          <Title hero>{t('welcome.hero')}</Title>
        </View>
        <Body tone="lead">{t('welcome.lede')}</Body>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  top: { paddingTop: space[6], gap: space[4] },
  hero: { paddingTop: space[2] },
});
