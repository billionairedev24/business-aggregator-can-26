import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useState } from 'react';
import { RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';

import { CAP, FilterChip, LINE, MetaLine, Photo, PhotoWell, Rating, SIZE, StatusTag, photoFor, radius, semantic, slugSlot, space, textStyles } from '@northline/mobile-kit';

import { nameIn, type ProviderCard } from '../api/services';
import { useI18n } from '../i18n';
import { useDeliveryLocation } from '../location/DeliveryLocation';
import { GUTTER, Screen } from '../ui/screen';
import { EmptyState, QueryView, Skeleton } from '../ui/states';
import { iconFor } from '../ui/commerce';
import { dateIn, price } from './format';
import { Row, useServicesApi, useTier, type T } from './parts';

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
 * C2 Providers (design 01 `providers`; E-15 Plainsight results on a phone): the category's photo in the grey well, the
 * filter chips (applied = ink edge with ✓), the count, then one dense row per provider whose service area covers the
 * customer (the saved address's coordinates, else its city), most trusted first: the provider's initial in the well
 * (providers have no photo of their own yet), the name, the verified seal, the rating with the kit's stars, the
 * on-time figure, the line they wrote, "from $89.00" and the next opening in the business's zone. Guests may browse.
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
      <Cover slug={slug} />
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.chipRow} contentContainerStyle={styles.chips} accessibilityLabel={t('services.providers.filters')} testID="provider-filters">
        {FILTERS.map((f) => (
          <FilterChip key={f} label={t(`services.filter.${f}`)} selected={on.has(f)} onPress={() => toggle(f)} role="button" testID={`filter-${f}`} />
        ))}
      </ScrollView>
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
              <Text accessibilityRole="header" style={textStyles.label}>
                {t('services.providers.count', { n: shown.length, area })}
              </Text>
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

/** The category's photo (the library's), else the dashed well with the services icon. */
function Cover({ slug }: { slug: string }) {
  const photo = photoFor([slugSlot('service', slug), 'banner/services'], 'wide');
  return <View style={styles.cover}>{photo ? <Photo slot={[]} photo={photo} crop="wide" style={StyleSheet.absoluteFill} /> : <PhotoWell icon={iconFor('service')} inset={0} />}</View>;
}

function ProviderRow({ p }: { p: ProviderCard }) {
  const { t, time, locale } = useI18n();
  const tier = useTier()(p.tier);
  const avail = nextSlot(t, time, locale, p.nextAvailable, p.timeZone);
  const onTime = p.onTimePct != null ? t('services.onTime', { pct: Math.round(p.onTimePct) }) : null;
  // S-147: spoken without the ★ glyph
  const spoken = [p.reviewCount > 0 ? t('services.ratingSpoken', { rating: p.rating.toFixed(1), n: p.reviewCount }) : t('services.newProvider'), onTime].filter(Boolean).join(', ');
  const from = p.fromCents != null ? t('services.providers.from', { price: price(p.fromCents, locale) }) : t('services.providers.quoted');
  return (
    <Row onPress={() => router.push(`/providers/${p.slug}`)} label={[p.name, tier.label, spoken, from, avail].join(', ')} testID={`provider-${p.slug}`}>
      <View style={styles.card}>
        <View style={styles.monogram} accessible={false}>
          <Text style={styles.initial} maxFontSizeMultiplier={CAP.badge}>
            {p.name.trim().charAt(0).toUpperCase()}
          </Text>
        </View>
        <View style={styles.cardMain}>
          <Text style={textStyles.sub} numberOfLines={2}>
            {p.name}
          </Text>
          {p.tier === 'master' || p.tier === 'trusted' ? <StatusTag label={tier.label} tone="success" /> : <Text style={textStyles.meta}>{tier.label}</Text>}
          {p.reviewCount > 0 ? <Rating value={p.rating} count={p.reviewCount} /> : <Text style={textStyles.meta}>{t('services.newProvider')}</Text>}
          {onTime ? <Text style={textStyles.meta}>{onTime}</Text> : null}
        </View>
      </View>
      {p.blurb ? (
        <Text style={textStyles.meta} numberOfLines={2}>
          {p.blurb}
        </Text>
      ) : null}
      <View style={styles.cardFoot}>
        <Text style={textStyles.priceSm}>{from}</Text>
        <MetaLine icon="clock" tone={p.nextAvailable ? 'success' : undefined}>
          {avail}
        </MetaLine>
      </View>
    </Row>
  );
}

function ListSkeleton() {
  const { t } = useI18n();
  return (
    <View accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.list} testID="loading">
      <Skeleton height={space[3.5]} width="60%" />
      {[0, 1, 2, 3].map((i) => (
        <View key={i} style={styles.card}>
          <Skeleton height={MONO} width={MONO} />
          <View style={styles.cardMain}>
            <Skeleton height={space[4]} width="70%" />
            <Skeleton height={space[3]} width="50%" />
          </View>
        </View>
      ))}
    </View>
  );
}

const MONO = SIZE.rail - space[2];

const styles = StyleSheet.create({
  cover: { width: '100%', aspectRatio: 16 / 9, borderRadius: radius.image, overflow: 'hidden', backgroundColor: semantic.well },
  chipRow: { flexGrow: 0, marginHorizontal: -GUTTER },
  chips: { flexDirection: 'row', gap: space[2], paddingHorizontal: GUTTER, paddingVertical: space[1] },
  list: { gap: space[4] },
  card: { flexDirection: 'row', alignItems: 'flex-start', gap: space[3] },
  cardMain: { flex: 1, minWidth: 0, gap: space[1] },
  monogram: { width: MONO, height: MONO, borderRadius: radius.image, backgroundColor: semantic.well, alignItems: 'center', justifyContent: 'center' },
  initial: { ...StyleSheet.flatten(textStyles.pageTitle), color: semantic['well-ink'] },
  cardFoot: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: space[3], paddingTop: space[3], borderTopWidth: LINE.hair, borderTopColor: semantic.border },
});
