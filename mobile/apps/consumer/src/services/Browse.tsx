import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useMemo, useState } from 'react';
import { RefreshControl, StyleSheet, Text, View } from 'react-native';

import { SIZE, radius, semantic, slugSlot, space, textStyles } from '@northline/mobile-kit';

import { nameIn, type Landing, type ServiceItem } from '../api/services';
import { useI18n } from '../i18n';
import { PlaceBar } from '../location/PlaceBar';
import { Body, Field, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, QueryView, Skeleton } from '../ui/states';
import { Picture } from '../ui/commerce';
import { shop, useMarket, useShopFormat } from '../shop/common';
import { SectionHead, Strip, Tile } from '../shop/parts';
import { useServicesApi } from './parts';

/**
 * Browse tab (light & photo-first; the web's Shop and Services landings on a phone): the place and the one search on
 * top; the Shop's departments as illustrated tiles in two columns; then the services — a linen band ("N categories
 * live · every provider verified", region-neutral), a field that narrows the categories on the phone, the popular
 * categories, and one sideways row of illustrated tiles per group with "N pros" or "Coming soon". Guests may browse.
 */
export function Browse() {
  const { t, locale } = useI18n();
  const api = useServicesApi();
  const landing = useQuery({ queryKey: ['services', 'landing', locale], queryFn: () => api.landing(locale === 'fr-CA' ? 'fr' : 'en'), staleTime: 60_000 });
  const [text, setText] = useState('');

  return (
    <Screen
      testID="services"
      padded={false}
      chrome
      top={<PlaceBar searchTestID="browse-search" />}
      refreshControl={<RefreshControl refreshing={landing.isRefetching} onRefresh={() => void landing.refetch()} accessibilityLabel={t('common.retry')} />}
    >
      <View style={styles.pad}>
        <Title>{t('rd.browse.title')}</Title>
      </View>
      <ShopDepartments />
      <QueryView
        query={landing}
        skeleton={<BrowseSkeleton />}
        isEmpty={(d) => d.groups.every((g) => g.items.length === 0)}
        empty={
          <View style={styles.pad}>
            <EmptyState art="services" message={t('services.browse.empty')} action={t('services.browse.emptyAction')} onAction={() => router.push('/home')} />
          </View>
        }
      >
        {(data) => <Groups data={data} text={text} setText={setText} />}
      </QueryView>
    </Screen>
  );
}

function Groups({ data, text, setText }: { data: Landing; text: string; setText: (v: string) => void }) {
  const { t, locale } = useI18n();
  const wanted = text.trim().toLocaleLowerCase();
  const groups = useMemo(
    () =>
      data.groups
        .map((g) => ({ ...g, items: g.items.filter((i) => !wanted || nameIn(i.names, locale).toLocaleLowerCase().includes(wanted)) }))
        .filter((g) => g.items.length > 0),
    [data, wanted, locale],
  );
  const popular = [...data.groups.flatMap((g) => g.items)].filter((i) => i.providers > 0).sort((a, b) => b.providers - a.providers).slice(0, 6);
  const sub = (i: ServiceItem) => (i.providers > 0 ? t('lt.home.pros', { n: i.providers }) : t('lt.home.soon'));
  const tile = (i: ServiceItem, testID?: string) => (
    <Tile key={i.slug} label={nameIn(i.names, locale)} sub={sub(i)} slot={slugSlot('service', i.slug)} icon="wrench" width={SIZE.rail * 2 + space[1]} onPress={() => router.push(`/services/${i.slug}`)} testID={testID} />
  );
  return (
    <>
      <View style={styles.pad}>
        <View style={styles.band}>
          <Picture slot="banner/services" crop="wide" icon="wrench" style={styles.bandArt} />
          <View style={styles.bandText}>
            <Text style={styles.kicker}>{t('lt.browse.live', { n: data.liveCategories })}</Text>
            <Text accessibilityRole="header" style={styles.bandTitle}>
              {t('services.browse.title')}
            </Text>
            <Body tone="small">{t('lt.browse.bandSub')}</Body>
          </View>
        </View>
        <Field label={t('lt.browse.filter')} placeholder={t('services.browse.search')} value={text} onChangeText={setText} returnKeyType="search" testID="services-search" />
      </View>
      {!wanted && popular.length > 1 ? (
        <View style={styles.section}>
          <View style={styles.pad}>
            <SectionHead title={t('lt.browse.popular')} />
          </View>
          <Strip edge testID="services-popular">{popular.map((i) => tile(i))}</Strip>
        </View>
      ) : null}
      {groups.length === 0 ? (
        <View style={styles.pad}>
          <EmptyState art="search" message={t('services.browse.noMatch', { text: text.trim() })} />
        </View>
      ) : null}
      {groups.map((g) => (
        <View key={g.id} style={styles.section}>
          <View style={styles.pad}>
            <SectionHead title={nameIn(g.names, locale)} />
            {g.note ? <Body tone="small">{g.note}</Body> : null}
          </View>
          <Strip edge>{g.items.map((i) => tile(i, `category-${i.slug}`))}</Strip>
        </View>
      ))}
    </>
  );
}

/** The Shop's departments (the same `GET /public/shop` Home reads), each opening its department list. */
function ShopDepartments() {
  const { t } = useI18n();
  const f = useShopFormat();
  const market = useMarket();
  const landing = useQuery({
    queryKey: ['shop', 'landing', market.city?.toLowerCase() ?? '', f.locale],
    queryFn: () => shop().landing(market.city, f.locale),
    enabled: market.ready,
    staleTime: 60_000,
  });
  const departments = landing.data?.departments ?? [];
  if (!departments.length) return null;
  const rows: (typeof departments)[] = [];
  for (let i = 0; i < departments.length; i += 2) rows.push(departments.slice(i, i + 2));
  return (
    <View style={[styles.pad, styles.shop]} testID="browse-shop">
      <SectionHead title={t('lt.home.tileShop')} />
      {rows.map((row) => (
        <View key={row[0]!.slug} style={styles.grid}>
          {row.map((d) => (
            <Tile
              key={d.slug}
              label={d.name}
              sub={d.shops > 0 ? t('lt.home.shops', { n: d.shops }) : undefined}
              slot={slugSlot('shop', d.slug)}
              onPress={() => router.push({ pathname: '/search', params: { department: d.slug, name: d.name } })}
              testID={`department-${d.slug}`}
            />
          ))}
          {row.length === 1 ? <View style={styles.flex} /> : null}
        </View>
      ))}
    </View>
  );
}

function BrowseSkeleton() {
  const { t } = useI18n();
  return (
    <View accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={[styles.pad, styles.section]} testID="loading">
      <Skeleton height={120} />
      <Skeleton height={48} />
      {[0, 1].map((i) => (
        <View key={i} style={styles.section}>
          <Skeleton height={22} width="40%" />
          <View style={styles.grid}>
            {[0, 1].map((j) => (
              <View key={j} style={styles.flex}>
                <Skeleton height={110} />
              </View>
            ))}
          </View>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  pad: { paddingHorizontal: space[4], gap: space[3] },
  shop: { paddingBottom: space[4] },
  grid: { flexDirection: 'row', gap: space[3] },
  section: { gap: space[2], paddingTop: space[2] },
  band: { borderRadius: radius.panel, overflow: 'hidden', backgroundColor: semantic.well, marginTop: space[3] },
  bandText: { padding: space[4], gap: space[1] },
  bandArt: { width: '100%', aspectRatio: 16 / 9, borderRadius: 0 },
  kicker: { ...StyleSheet.flatten(textStyles.caps), color: semantic['text-muted'] },
  bandTitle: { ...StyleSheet.flatten(textStyles.pageTitle) },
});
