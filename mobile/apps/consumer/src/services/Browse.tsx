import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useMemo, useState } from 'react';
import { RefreshControl, StyleSheet, View } from 'react-native';

import { space } from '@northline/mobile-kit';

import { geoApi } from '../api/geo';
import { nameIn, type Landing } from '../api/services';
import { useI18n } from '../i18n';
import { services } from '../services';
import { Body, Field, Section, Tag, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, QueryView, Skeleton } from '../ui/states';
import { Chip, useServicesApi } from './parts';

/**
 * C1 Browse services (design 01 `services`, the Services tab): the live categories in their groups, two taps from a
 * provider. The line under the title names the province only when all live providers are in one (region model); the
 * search box narrows the categories on the phone. Guests may browse.
 */
export function Browse() {
  const { t, locale } = useI18n();
  const api = useServicesApi();
  const landing = useQuery({ queryKey: ['services', 'landing', locale], queryFn: () => api.landing(locale === 'fr-CA' ? 'fr' : 'en'), staleTime: 60_000 });
  const regions = useQuery({ queryKey: ['geo', 'regions', locale], queryFn: () => geoApi(services().api).regions(locale), staleTime: 300_000 });
  const [text, setText] = useState('');

  const province = (data: Landing) => {
    if (data.provinces.length !== 1) return null;
    return regions.data?.provinces.find((p) => p.code === data.provinces[0])?.name ?? null;
  };

  return (
    <Screen
      testID="services"
      refreshControl={<RefreshControl refreshing={landing.isRefetching} onRefresh={() => void landing.refetch()} accessibilityLabel={t('common.retry')} />}
    >
      <Title>{t('services.browse.title')}</Title>
      <QueryView
        query={landing}
        skeleton={<BrowseSkeleton />}
        isEmpty={(d) => d.groups.every((g) => g.items.length === 0)}
        empty={<EmptyState message={t('services.browse.empty')} action={t('services.browse.emptyAction')} onAction={() => router.push('/home')} />}
      >
        {(data) => <Groups data={data} province={province(data)} text={text} setText={setText} />}
      </QueryView>
    </Screen>
  );
}

function Groups({ data, province, text, setText }: { data: Landing; province: string | null; text: string; setText: (v: string) => void }) {
  const { t, locale } = useI18n();
  const wanted = text.trim().toLocaleLowerCase();
  const groups = useMemo(
    () =>
      data.groups
        .map((g) => ({ ...g, items: g.items.filter((i) => !wanted || nameIn(i.names, locale).toLocaleLowerCase().includes(wanted)) }))
        .filter((g) => g.items.length > 0),
    [data, wanted, locale],
  );
  const popular = [...data.groups.flatMap((g) => g.items)].sort((a, b) => b.providers - a.providers)[0];
  return (
    <>
      <Body tone="small">
        {province ? t('services.browse.sub', { n: data.liveCategories, region: province }) : t('services.browse.subNoRegion', { n: data.liveCategories })}
      </Body>
      <Field label={t('services.browse.searchLabel')} placeholder={t('services.browse.search')} value={text} onChangeText={setText} returnKeyType="search" testID="services-search" />
      {popular && popular.providers > 0 ? (
        <View style={styles.tags}>
          <Tag label={t('services.browse.popular', { name: nameIn(popular.names, locale) })} />
        </View>
      ) : null}
      {groups.length === 0 ? <EmptyState message={t('services.browse.noMatch', { text: text.trim() })} /> : null}
      {groups.map((g) => (
        <View key={g.id} style={styles.group}>
          <Section>{nameIn(g.names, locale)}</Section>
          <View style={styles.chips}>
            {g.items.map((i) => (
              <Chip
                key={i.slug}
                label={nameIn(i.names, locale)}
                selected={false}
                hint={t('services.browse.providersHint', { n: i.providers })}
                onPress={() => router.push(`/services/${i.slug}`)}
                testID={`category-${i.slug}`}
              />
            ))}
          </View>
        </View>
      ))}
    </>
  );
}

function BrowseSkeleton() {
  const { t } = useI18n();
  return (
    <View accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.group} testID="loading">
      <Skeleton height={14} width="70%" />
      <Skeleton height={48} />
      {[0, 1, 2].map((i) => (
        <View key={i} style={styles.group}>
          <Skeleton height={20} width="40%" />
          <View style={styles.chips}>
            {[0, 1, 2, 3].map((j) => (
              <Skeleton key={j} height={40} width={96} />
            ))}
          </View>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  tags: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
  group: { gap: space[2], paddingTop: space[2] },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
});
