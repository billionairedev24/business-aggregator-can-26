import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { router, useLocalSearchParams, type Href } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import { MIN_TARGET, colors, fonts, radius } from '@northline/mobile-kit';

import type { ProductCard, SearchItem, SearchQuery, Sort } from '../api/shop';
import type { MessageKey } from '../i18n';
import { Button, Tag } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, LoadingList, QueryView } from '../ui/states';
import { shop, useMarket, useShopFormat } from './common';
import { Chip, Kicker, Thumb, shopStyles } from './parts';

type Filter = 'tonight' | 'under10' | 'master' | 'halal';
/** Design 01's chips that the search api can answer ("Organic" has no data in the index, S-44 — the web drops it too). */
const FILTERS: Filter[] = ['tonight', 'under10', 'master', 'halal'];
const SORTS: Sort[] = ['relevance', 'price_asc', 'price_desc', 'rating'];
const UNDER_10 = 999;

interface Row {
  key: string;
  name: string;
  shop: string;
  meta?: string | null;
  priceCents?: number | null;
  unit?: string | null;
  tag: 'tonight' | 'soldOut' | 'later';
  to: Href;
}

const fromSearch = (i: SearchItem): Row => ({
  key: i.id,
  name: i.name,
  shop: i.merchant.name,
  meta: i.category?.name ?? null,
  priceCents: i.priceCents,
  tag: i.onTonightsRun ? 'tonight' : i.soldOut ? 'soldOut' : 'later',
  // search answers the offer; the product page takes an offer id too (S-50)
  to: { pathname: '/product/[id]', params: { id: i.id, offer: i.id } },
});
const fromCard = (p: ProductCard): Row => ({
  key: p.offerId,
  name: p.name,
  shop: p.shopName,
  meta: p.unit,
  priceCents: p.priceCents,
  tag: p.run?.day === 'today' ? 'tonight' : 'later',
  to: { pathname: '/product/[id]', params: { id: p.productId, offer: p.offerId } },
});

/**
 * B2 Search & filters (design 01 `search`): what was typed, the filter chips ("On tonight's run" on by default), the
 * count and the sort, the results. Text search is `GET /search` (S-44, kind=product, the person's province and
 * position); a department tile from Home browses `GET /public/shop/departments/{slug}` with the same chips applied to
 * its products. Results follow the typing (debounced) — the suggestions endpoint isn't needed for that.
 */
export function Search() {
  const f = useShopFormat();
  const { t, locale } = f;
  const params = useLocalSearchParams<{ q?: string; department?: string; name?: string }>();
  const market = useMarket();
  const [text, setText] = useState(params.q ?? '');
  const [q, setQ] = useState(text);
  const [on, setOn] = useState<Record<Filter, boolean>>({ tonight: true, under10: false, master: false, halal: false });
  const [sort, setSort] = useState<Sort>('relevance');
  const department = params.department && !text ? params.department : undefined;

  useEffect(() => {
    const id = setTimeout(() => setQ(text.trim()), 300);
    return () => clearTimeout(id);
  }, [text]);

  const query: SearchQuery = {
    q,
    market: market.province,
    lat: market.location.lat,
    lng: market.location.lng,
    sort,
    tonight: on.tonight,
    maxPrice: on.under10 ? UNDER_10 : undefined,
    tier: on.master ? 'master' : undefined,
    dietary: on.halal ? ['halal'] : undefined,
  };
  const search = useInfiniteQuery({
    queryKey: ['shop', 'search', query, locale],
    queryFn: ({ pageParam }) => shop().search(query, pageParam || undefined),
    initialPageParam: '',
    getNextPageParam: (last) => last?.next ?? undefined,
    enabled: market.ready && !department,
    staleTime: 30_000,
  });
  const browse = useQuery({
    queryKey: ['shop', 'department', department ?? '', market.city?.toLowerCase() ?? '', locale],
    queryFn: () => shop().department(department!, market.city, locale),
    enabled: market.ready && !!department,
    staleTime: 60_000,
  });

  const rows: Row[] | undefined = department
    ? browse.data
      ? (() => {
          const tiers = new Map(browse.data.shops.map((s) => [s.merchantId, s.tier.toLowerCase()]));
          const list = browse.data.products
            .filter((p) => (!on.tonight || p.run?.day === 'today') && (!on.under10 || p.priceCents <= UNDER_10) && (!on.master || tiers.get(p.merchantId) === 'master'))
            .map(fromCard);
          if (sort === 'price_asc') list.sort((a, b) => (a.priceCents ?? 0) - (b.priceCents ?? 0));
          if (sort === 'price_desc') list.sort((a, b) => (b.priceCents ?? 0) - (a.priceCents ?? 0));
          return list;
        })()
      : undefined
    : search.data?.pages.flatMap((p) => (p?.items ?? []).filter((i) => i.kind === 'product').map(fromSearch));
  const total = department ? rows?.length ?? 0 : search.data?.pages[0]?.total ?? 0;
  const view = department ? { ...browse, data: rows } : { data: rows, error: search.error, isPending: search.isPending, isError: search.isError, refetch: search.refetch };
  const filters = department ? FILTERS.filter((x) => x !== 'halal') : FILTERS;
  const anyFilter = FILTERS.some((x) => on[x]);

  return (
    <Screen title={t('title.search')} testID="search">
      <TextInput
        value={text}
        onChangeText={setText}
        accessibilityLabel={t('shop.search.label')}
        placeholder={t('shop.home.search')}
        placeholderTextColor={colors.neutral600}
        autoFocus={!params.q && !params.department}
        returnKeyType="search"
        onSubmitEditing={() => setQ(text.trim())}
        style={styles.input}
        testID="search-input"
      />
      {department ? <Kicker>{t('shop.search.department', { name: params.name ?? department })}</Kicker> : null}
      <View style={shopStyles.chips}>
        {filters.map((x) => (
          <Chip key={x} label={t(`shop.filter.${x}` as MessageKey)} on={on[x]} onPress={() => setOn((o) => ({ ...o, [x]: !o[x] }))} testID={`filter-${x}`} />
        ))}
      </View>
      <QueryView
        query={view}
        skeleton={<LoadingList rows={5} height={64} />}
        isEmpty={(d) => d.length === 0}
        empty={
          <EmptyState
            message={q ? t('shop.search.empty', { q }) : t('shop.search.emptyBrowse')}
            action={anyFilter ? t('shop.search.clear') : undefined}
            onAction={() => setOn({ tonight: false, under10: false, master: false, halal: false })}
          />
        }
      >
        {(list) => (
          <View>
            <View style={styles.countRow}>
              <Text style={styles.small}>{t('shop.search.count', { n: total })} </Text>
              <Pressable
                accessibilityRole="button"
                accessibilityHint={t('shop.search.sortHint')}
                onPress={() => setSort((s) => SORTS[(SORTS.indexOf(s) + 1) % SORTS.length]!)}
                style={styles.sort}
                testID="search-sort"
              >
                <Text style={[styles.small, styles.sortText]}>{t(`shop.sort.${sort}` as MessageKey)}</Text>
              </Pressable>
            </View>
            {list.map((r) => (
              <Pressable
                key={r.key}
                accessibilityRole="button"
                accessibilityLabel={[r.name, r.shop, r.meta, r.priceCents != null ? f.money(r.priceCents) : null, t(`shop.tag.${r.tag}` as MessageKey)].filter(Boolean).join(', ')}
                onPress={() => router.push(r.to)}
                style={shopStyles.row}
              >
                <Thumb size={64} />
                <View style={shopStyles.rowText}>
                  <Text style={[styles.body, shopStyles.strong]}>{r.name}</Text>
                  <Text style={styles.small}>{[r.shop, r.meta].filter(Boolean).join(' · ')}</Text>
                  <Tag label={t(`shop.tag.${r.tag}` as MessageKey)} tone={r.tag === 'tonight' ? 'accent' : 'neutral'} small />
                </View>
                {r.priceCents != null ? <Text style={shopStyles.price}>{f.money(r.priceCents)}</Text> : null}
              </Pressable>
            ))}
            {!department && search.hasNextPage ? (
              <Button label={t('shop.search.more')} tone="secondary" busy={search.isFetchingNextPage} onPress={() => void search.fetchNextPage()} />
            ) : null}
          </View>
        )}
      </QueryView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  input: {
    minHeight: MIN_TARGET,
    paddingHorizontal: 14,
    fontFamily: fonts.body,
    fontSize: 16,
    color: colors.text,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.neutral300,
    borderRadius: radius.md,
  },
  countRow: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap' },
  sort: { minHeight: MIN_TARGET, justifyContent: 'center' },
  sortText: { fontFamily: fonts.bodyStrong, color: colors.text },
  body: { fontFamily: fonts.body, fontSize: 15, color: colors.text },
  small: { fontFamily: fonts.body, fontSize: 13, color: colors.neutral700 },
});
