import { useQuery } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { router, type Href } from 'expo-router';
import { StyleSheet, Text, View } from 'react-native';

import { radius, semantic, space, textStyles } from '@northline/mobile-kit';

import type { NearbyReward } from '../api/account';
import { accountApi } from '../api/account';
import { config } from '../config';
import { services } from '../services';
import { Card } from '../ui/primitives';
import { useMarket, useShopFormat } from './common';
import { SectionHead, Strip } from './parts';

/**
 * S-152 "Rewards near you" on Home: businesses in the person's place paying extra points right now (S-75's
 * provider-funded rewards, while their budget lasts; `GET /public/rewards?city=&lat=&lng=`). A provider opens its page
 * in the app, a kitchen the consumer site's. Nothing shows when there are none.
 */
export function RewardsNear() {
  const f = useShopFormat();
  const { t } = f;
  const market = useMarket();
  const { lat, lng } = market.location;
  const place = {
    city: market.city ?? null,
    lat: lat ?? null,
    lng: lng ?? null,
  };
  const rewards = useQuery({
    queryKey: ['public', 'rewards', place.city ?? '', place.lat ?? '', place.lng ?? ''],
    queryFn: () => accountApi(services().api).rewardsNear(place),
    enabled: market.ready && (!!place.city || (place.lat != null && place.lng != null)),
    staleTime: 60_000,
  });
  const items = rewards.data ?? [];
  if (items.length === 0) return null;
  return (
    <View style={styles.section} testID="home-rewards">
      <View style={styles.pad}>
        <SectionHead title={t('loyalty.nearTitle')} />
      </View>
      <Strip edge>
        {items.map((r) => (
          <RewardCard key={r.merchantId} r={r} f={f} />
        ))}
      </Strip>
    </View>
  );
}

function RewardCard({ r, f }: { r: NearbyReward; f: ReturnType<typeof useShopFormat> }) {
  const { t } = f;
  const what = r.label ? t('loyalty.nearOn', { multiplier: r.multiplier, label: r.label }) : t('loyalty.nearAll', { multiplier: r.multiplier });
  const until = t('loyalty.nearUntil', {
    date: f.date(`${r.endsOn}T12:00:00Z`),
  });
  const open = r.slug
    ? r.type === 'kitchen'
      ? () => void Linking.openURL(`${config.siteOrigin}/food/${encodeURIComponent(r.slug!)}`)
      : () => router.push(`/providers/${encodeURIComponent(r.slug!)}` as Href)
    : undefined;
  return (
    <Card onPress={open} label={[r.businessName, what, until].join(', ')} style={styles.card} testID={`reward-${r.merchantId}`}>
      <Text style={styles.mult} accessibilityElementsHidden importantForAccessibility="no">
        {r.multiplier}×
      </Text>
      <Text style={styles.name} numberOfLines={2}>
        {r.businessName}
      </Text>
      <Text style={styles.sub} numberOfLines={2}>
        {what} · {until}
      </Text>
    </Card>
  );
}

const styles = StyleSheet.create({
  section: { gap: space[2], paddingTop: space[3] },
  pad: { paddingHorizontal: space[4] },
  card: { width: 200, gap: space[1], borderRadius: radius.panel },
  mult: { ...StyleSheet.flatten(textStyles.pageTitle), color: semantic['action-primary'] },
  name: { ...StyleSheet.flatten(textStyles.bodyStrong) },
  sub: { ...StyleSheet.flatten(textStyles.meta), color: semantic['text-muted'] },
});
