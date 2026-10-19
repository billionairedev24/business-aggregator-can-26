import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useState } from 'react';
import { RefreshControl, StyleSheet, Text, View } from 'react-native';

import { Art, Icon, colors, fonts, radius, semantic, space } from '@northline/mobile-kit';

import { nameIn, type ProviderCard } from '../api/services';
import { useI18n } from '../i18n';
import { useDeliveryLocation } from '../location/DeliveryLocation';
import { Body, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, QueryView, Skeleton } from '../ui/states';
import { Strip } from '../shop/parts';
import { dateIn, price } from './format';
import { brand, Chip, Mark, Row, useServicesApi, useTier, type T } from './parts';

export type ProviderFilter = 'master' | 'instant' | 'today' | 'under80';
export const FILTERS: readonly ProviderFilter[] = ['master', 'instant', 'today', 'under80'];

/** The design's filters: Master tier, instant book, a free slot today (the business's day), from under $80. */
export function applyFilters(items: ProviderCard[], on: ReadonlySet<ProviderFilter>, now = new Date()): ProviderCard[] {
  return items.filter(
    (p) =>
      (!on.has('master') || p.tier === 'master') &&
      (!on.has('instant') || p.instantBook) &&
      (!on.has('today') || (!!p.nextAvailable && dateIn(p.nextAvailable, p.timeZone) === dateIn(now, p.timeZone))) &&
      (!on.has('under80') || (p.fromCents != null && p.fromCents < 8000)),
  );
}

/** "Today 3:00 p.m." / "Tomorrow 3:00 p.m." / "Thu 3:00 p.m." in the business's zone. */
export function nextSlot(t: T, time: (iso: string, z: string) => string, locale: string, iso: string | null | undefined, zone: string, now = new Date()): string {
  if (!iso) return t('services.providers.noOpenings');
  const day = dateIn(iso, zone);
  const today = dateIn(now, zone);
  const tomorrow = dateIn(now.getTime() + 86_400_000, zone);
  const at = time(iso, zone);
  if (day === today) return t('services.providers.today', { time: at });
  if (day === tomorrow) return t('services.providers.tomorrow', { time: at });
  const dow = new Intl.DateTimeFormat(locale === 'fr-CA' ? 'fr-CA' : 'en-CA', { weekday: 'short', timeZone: zone }).format(new Date(iso));
  return t('services.providers.onDay', { day: dow, time: at });
}

/**
 * C2 Providers (design 01 `providers`): the category's providers whose service area covers the customer (the saved
 * address's coordinates, else its city), most trusted first, with the design's filters. Guests may browse.
 */
export function Providers({ slug }: { slug: string }) {
  const { t, locale } = useI18n();
  const api = useServicesApi();
  const lang = locale === 'fr-CA' ? 'fr' : 'en';
  const { location } = useDeliveryLocation();
  const place = { lat: location.lat, lng: location.lng, city: location.city };
  const category = useQuery({ queryKey: ['services', 'category', slug, lang], queryFn: () => api.category(slug, lang), staleTime: 60_000 });
  const list = useQuery({
    queryKey: ['services', 'providers', slug, place, lang],
    queryFn: () => api.providers(slug, place, lang),
    enabled: location.status !== 'locating',
    staleTime: 30_000,
  });
  const [on, setOn] = useState<ReadonlySet<ProviderFilter>>(new Set());
  const toggle = (f: ProviderFilter) => setOn((s) => {
    const next = new Set(s);
    if (!next.delete(f)) next.add(f);
    return next;
  });
  const title = category.data ? nameIn(category.data.names, locale) : t('title.providers');

  return (
    <Screen
      title={title}
      testID="providers"
      refreshControl={<RefreshControl refreshing={list.isRefetching} onRefresh={() => void list.refetch()} accessibilityLabel={t('common.retry')} />}
    >
      <Art subject={slug} fit="contain" style={styles.cover} />
      <Strip testID="provider-filters">
        <View style={styles.chips} accessibilityLabel={t('services.providers.filters')}>
          {FILTERS.map((f) => (
            <Chip key={f} label={t(`services.filter.${f}`)} selected={on.has(f)} onPress={() => toggle(f)} testID={`filter-${f}`} />
          ))}
        </View>
      </Strip>
      <QueryView
        query={location.status === 'locating' ? { ...list, isPending: true } : list}
        skeleton={<ListSkeleton />}
        isEmpty={(d) => d.items.length === 0}
        empty={
          <EmptyState
            art={slug}
            message={t('services.providers.empty', { area: list.data?.area ?? list.data?.city ?? location.city ?? '' })}
            action={t('services.providers.emptyAction')}
            onAction={() => router.push('/services')}
          />
        }
      >
        {(data) => {
          const shown = applyFilters(data.items, on);
          const area = data.area ?? data.city ?? '';
          return (
            <>
              <Body tone="small">{t('services.providers.count', { n: shown.length, area })}</Body>
              {shown.length === 0 ? (
                <EmptyState message={t('services.providers.filteredEmpty')} action={t('services.providers.clearFilters')} onAction={() => setOn(new Set())} />
              ) : (
                shown.map((p) => <ProviderRow key={p.slug} p={p} />)
              )}
            </>
          );
        }}
      </QueryView>
    </Screen>
  );
}

function ProviderRow({ p }: { p: ProviderCard }) {
  const { t, time, locale } = useI18n();
  const tier = useTier()(p.tier);
  const avail = nextSlot(t, time, locale, p.nextAvailable, p.timeZone);
  const onTime = p.onTimePct != null ? t('services.onTime', { pct: Math.round(p.onTimePct) }) : null;
  const stats = [p.reviewCount > 0 ? t('services.rating', { rating: p.rating.toFixed(1), n: p.reviewCount }) : t('services.newProvider'), onTime]
    .filter(Boolean)
    .join(' · ');
  // S-147: spoken without the ★ glyph
  const spoken = [p.reviewCount > 0 ? t('services.ratingSpoken', { rating: p.rating.toFixed(1), n: p.reviewCount }) : t('services.newProvider'), onTime]
    .filter(Boolean)
    .join(', ');
  const from = p.fromCents != null ? t('services.providers.from', { price: price(p.fromCents, locale) }) : t('services.providers.quoted');
  return (
    <Row onPress={() => router.push(`/providers/${p.slug}`)} label={[p.name, tier.label, spoken, from, avail].join(', ')} testID={`provider-${p.slug}`}>
      <View style={styles.card}>
        <Mark name={p.name} color={brand(p.brandColor)} size={52} />
        <View style={styles.cardMain}>
          <Text style={styles.name} numberOfLines={2}>
            {p.name}
          </Text>
          <View style={styles.nameRow}>
            {p.tier === 'master' || p.tier === 'trusted' ? <Icon name="seal-check" size={16} filled color={semantic.success} /> : null}
            <Text style={type.small}>{tier.label}</Text>
          </View>
          <View style={styles.nameRow}>
            <Text style={type.small}>{stats}</Text>
          </View>
        </View>
      </View>
      {p.blurb ? (
        <Text style={type.small} numberOfLines={2}>
          {p.blurb}
        </Text>
      ) : null}
      <View style={styles.cardFoot}>
        <Text style={styles.from}>{from}</Text>
        <Text style={[type.small, styles.avail, !!p.nextAvailable && styles.open]} numberOfLines={1}>
          {avail}
        </Text>
      </View>
    </Row>
  );
}

function ListSkeleton() {
  const { t } = useI18n();
  return (
    <View accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.list} testID="loading">
      <Skeleton height={14} width="60%" />
      {[0, 1, 2, 3].map((i) => (
        <View key={i} style={styles.card}>
          <Skeleton height={56} width={56} />
          <View style={styles.cardMain}>
            <Skeleton height={16} width="70%" />
            <Skeleton height={12} width="50%" />
          </View>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  cover: { height: 150, borderRadius: radius.lg },
  chips: { flexDirection: 'row', gap: space[2] },
  list: { gap: space[4] },
  card: { flexDirection: 'row', alignItems: 'center', gap: 14 },
  cardMain: { flex: 1, minWidth: 0, gap: 2 },
  nameRow: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 4 },
  name: { fontFamily: fonts.semibold, fontSize: 17, lineHeight: 22, color: colors.text },
  cardFoot: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space[3], paddingTop: space[3], borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.border },
  from: { fontFamily: fonts.bold, fontSize: 15, color: colors.text },
  avail: { flexShrink: 1, textAlign: 'right' },
  open: { color: semantic.success },
});
