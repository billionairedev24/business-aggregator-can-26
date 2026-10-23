import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { router, useLocalSearchParams, type Href } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import { Icon, MIN_TARGET, colors, fonts, radius, semantic, shadow, space } from '@northline/mobile-kit';

import type { ProductCard, SearchItem, SearchQuery, Sort } from '../api/shop';
import type { MessageKey } from '../i18n';
import { Button, Tag } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, LoadingList, QueryView } from '../ui/states';
import { shop, useMarket, useShopFormat } from './common';
import { Chip, Kicker, Thumb, shopStyles } from './parts';
import { ProposalChips, SmartFilters } from './SmartFilters';
import { withProposals, type Proposal } from './smartSearch';

type Kind = 'all' | 'product' | 'service';
const KINDS: Kind[] = ['all', 'product', 'service'];
type Filter = 'tonight' | 'under10';
/** The chips the search api can answer for goods (the tier and dietary chips left the UI: thrash item 13, spec § 2.1). */
const FILTERS: Filter[] = ['tonight', 'under10'];
const SORTS: Sort[] = ['relevance', 'price_asc', 'price_desc', 'rating'];
const UNDER_10 = 999;

interface Row {
  key: string;
  kind: 'product' | 'service';
  name: string;
  shop: string;
  meta?: string | null;
  priceCents?: number | null;
  rating?: number | null;
  tag?: 'tonight' | 'soldOut' | 'later';
  /** What the no-photo picture draws (the category's id or the product's name). */
  subject: string;
  imageUrl?: string | null;
  to: Href | null;
}

const fromSearch = (i: SearchItem): Row => ({
  key: `${i.kind}-${i.id}`,
  kind: i.kind === 'service' ? 'service' : 'product',
  name: i.name,
  shop: i.merchant.name,
  meta: i.category?.name ?? null,
  priceCents: i.priceCents,
  rating: i.rating,
  tag: i.kind === 'service' ? undefined : i.onTonightsRun ? 'tonight' : i.soldOut ? 'soldOut' : 'later',
  subject: i.kind === 'service' ? (i.category?.id ?? i.name) : i.name,
  // a product result is its offer (the product page takes an offer id too, S-50); a service opens its provider
  to:
    i.kind === 'service'
      ? i.merchant.slug
        ? ({ pathname: '/providers/[slug]', params: { slug: i.merchant.slug } } as Href)
        : null
      : { pathname: '/product/[id]', params: { id: i.id, offer: i.id } },
});
const fromCard = (p: ProductCard): Row => ({
  key: p.offerId,
  kind: 'product',
  name: p.name,
  shop: p.shopName,
  meta: p.unit,
  priceCents: p.priceCents,
  subject: p.name,
  imageUrl: p.imageUrl,
  tag: p.run?.day === 'today' ? 'tonight' : 'later',
  to: { pathname: '/product/[id]', params: { id: p.productId, offer: p.offerId } },
});

/**
 * Search (redesign spec § 2.4 → MA:212 SEARCH; products and services together, as the web's results page): the search
 * pill with Cancel, the type chips (all · products · services) and the goods chips, the count and the sort, then one
 * list of products and services (96 pt pictures). Text search is `GET /search` (S-44) with the person's province and
 * position — no `kind` for "all"; food and business results are left out (the app has no food screens; Food is hidden
 * as a vertical, thrash item 7). A department circle from Home browses `GET /public/shop/departments/{slug}`. S-158: a
 * typed sentence gets the AI's suggested filters as chips, applied only when the person taps Apply (and removable).
 */
export function Search() {
  const f = useShopFormat();
  const { t, locale } = f;
  const params = useLocalSearchParams<{ q?: string; department?: string; name?: string; kind?: string }>();
  const market = useMarket();
  const [text, setText] = useState(params.q ?? '');
  const [q, setQ] = useState(text);
  const [kind, setKind] = useState<Kind>(params.kind === 'product' || params.kind === 'service' ? params.kind : 'all');
  const [on, setOn] = useState<Record<Filter, boolean>>({ tonight: false, under10: false });
  const [sort, setSort] = useState<Sort>('relevance');
  const department = params.department && !text ? params.department : undefined;
  // S-158: filters the person applied from the AI's reading of their sentence (removable; cleared by new words)
  const [applied, setApplied] = useState<Proposal[]>([]);
  const [appliedFor, setAppliedFor] = useState('');
  const goods = kind !== 'service';

  useEffect(() => {
    const id = setTimeout(() => setQ(text.trim()), 300);
    return () => clearTimeout(id);
  }, [text]);

  const own: SearchQuery = {
    q,
    kind: kind === 'all' ? undefined : kind,
    market: market.province,
    lat: market.location.lat,
    lng: market.location.lng,
    sort,
    tonight: goods && on.tonight,
    maxPrice: goods && on.under10 ? UNDER_10 : undefined,
  };
  const smart = applied.length > 0 && appliedFor === q ? applied : [];
  const query: SearchQuery = smart.length ? withProposals(own, smart) : own;
  const located = market.location.lat !== undefined && market.location.lng !== undefined;
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
          const list = browse.data.products.filter((p) => (!on.tonight || p.run?.day === 'today') && (!on.under10 || p.priceCents <= UNDER_10)).map(fromCard);
          if (sort === 'price_asc') list.sort((a, b) => (a.priceCents ?? 0) - (b.priceCents ?? 0));
          if (sort === 'price_desc') list.sort((a, b) => (b.priceCents ?? 0) - (a.priceCents ?? 0));
          return list;
        })()
      : undefined
    : search.data?.pages.flatMap((p) => (p?.items ?? []).filter((i) => i.kind === 'product' || i.kind === 'service').map(fromSearch));
  const total = department ? (rows?.length ?? 0) : (search.data?.pages[0]?.total ?? 0);
  const view = department ? { ...browse, data: rows } : { data: rows, error: search.error, isPending: search.isPending, isError: search.isError, refetch: search.refetch };
  const anyFilter = FILTERS.some((x) => on[x]) || kind !== 'all';
  const clear = () => {
    setOn({ tonight: false, under10: false });
    setKind('all');
  };

  return (
    <Screen testID="search" title={department ? (params.name ?? t('title.search')) : undefined}>
      {department ? null : (
        <View style={styles.bar}>
          <View style={styles.input}>
            <Icon name="magnifying-glass" size={20} />
            <TextInput
              value={text}
              onChangeText={setText}
              accessibilityLabel={t('shop.search.label')}
              placeholder={t('rd.search.placeholder')}
              placeholderTextColor={colors.textSubtle}
              autoFocus={!params.q && !params.department}
              returnKeyType="search"
              onSubmitEditing={() => setQ(text.trim())}
              style={styles.inputText}
              testID="search-input"
            />
            {text ? (
              <Pressable accessibilityRole="button" accessibilityLabel={t('rd.search.clearText')} onPress={() => setText('')} hitSlop={10} style={styles.clearText}>
                <Icon name="x" size={18} />
              </Pressable>
            ) : null}
          </View>
          <Pressable accessibilityRole="button" accessibilityLabel={t('rd.search.cancel')} onPress={() => (router.canGoBack() ? router.back() : router.navigate('/home'))} style={styles.cancel} testID="search-cancel">
            <Text style={styles.cancelText}>{t('rd.search.cancel')}</Text>
          </Pressable>
        </View>
      )}
      {department ? <Kicker>{t('shop.search.department', { name: params.name ?? department })}</Kicker> : null}
      {department ? null : (
        <View style={shopStyles.chips} accessibilityRole="radiogroup" accessibilityLabel={t('rd.search.type')}>
          {KINDS.map((k) => (
            <Chip key={k} role="radio" label={t(`rd.search.kind.${k}` as MessageKey)} on={kind === k} onPress={() => setKind(k)} testID={`kind-${k}`} />
          ))}
        </View>
      )}
      {goods ? (
        <View style={shopStyles.chips}>
          {FILTERS.map((x) => (
            <Chip key={x} label={t(`shop.filter.${x}` as MessageKey)} on={on[x]} onPress={() => setOn((o) => ({ ...o, [x]: !o[x] }))} testID={`filter-${x}`} />
          ))}
        </View>
      ) : null}
      {department ? null : smart.length ? (
        <View style={styles.smartApplied}>
          <Kicker>{t('ai.search.applied')}</Kicker>
          <ProposalChips chips={smart} onRemove={(c) => setApplied((list) => list.filter((x) => x.id !== c.id))} testID="smart-applied" />
        </View>
      ) : (
        <SmartFilters text={q} located={located} onApply={(chips) => { setApplied(chips); setAppliedFor(q); }} />
      )}
      <QueryView
        query={view}
        skeleton={<LoadingList rows={5} height={96} />}
        isEmpty={(d) => d.length === 0}
        empty={<EmptyState art="search" message={q ? t('shop.search.empty', { q }) : t('shop.search.emptyBrowse')} action={anyFilter ? t('shop.search.clear') : undefined} onAction={clear} />}
      >
        {(list) => (
          <View style={styles.results}>
            <View style={styles.countRow}>
              <Text style={styles.small}>{t('shop.search.count', { n: total })} </Text>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={t('shop.search.sortLabel', { sort: t(`shop.sort.${sort}` as MessageKey) })}
                accessibilityHint={t('shop.search.sortHint')}
                onPress={() => setSort((s) => SORTS[(SORTS.indexOf(s) + 1) % SORTS.length]!)}
                style={styles.sort}
                testID="search-sort"
              >
                <Text style={[styles.small, styles.sortText]}>{t(`shop.sort.${sort}` as MessageKey)}</Text>
                <Icon name="caret-down" size={12} />
              </Pressable>
            </View>
            {list.map((r) => (
              <ResultRow key={r.key} row={r} f={f} />
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

/** A result (MA:218): the 96 pt picture, the name, who sells it and its category, a tag, the price ("from" for a service). */
function ResultRow({ row: r, f }: { row: Row; f: ReturnType<typeof useShopFormat> }) {
  const { t } = f;
  const price = r.priceCents != null ? (r.kind === 'service' ? t('rd.search.from', { price: f.money(r.priceCents) }) : f.money(r.priceCents)) : null;
  const kindWord = t(r.kind === 'service' ? 'rd.search.kind.service' : 'rd.search.kind.product');
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={[r.name, kindWord, r.shop, r.meta, price, r.tag ? t(`shop.tag.${r.tag}` as MessageKey) : null].filter(Boolean).join(', ')}
      disabled={!r.to}
      onPress={() => r.to && router.push(r.to)}
      style={({ pressed }) => [styles.row, pressed && styles.pressed]}
      testID={`result-${r.key}`}
    >
      <Thumb size={88} uri={r.imageUrl} subject={r.subject} />
      <View style={shopStyles.rowText}>
        <Text style={styles.name} numberOfLines={2}>
          {r.name}
        </Text>
        <Text style={styles.small} numberOfLines={1}>
          {[r.shop, r.meta].filter(Boolean).join(' · ')}
        </Text>
        {r.rating ? (
          <View style={styles.rating}>
            <Icon name="star" filled size={14} color={colors.rating} />
            <Text style={styles.small}>{r.rating.toFixed(1)}</Text>
          </View>
        ) : null}
        {price ? <Text style={shopStyles.price}>{price}</Text> : null}
        {r.tag ? <Tag label={t(`shop.tag.${r.tag}` as MessageKey)} tone={r.tag === 'tonight' ? 'accent' : r.tag === 'soldOut' ? 'accent2' : 'neutral'} small /> : r.kind === 'service' ? <Tag label={kindWord} small /> : null}
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  bar: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  input: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: space[2],
    minHeight: 48,
    paddingLeft: space[4],
    paddingRight: space[2],
    borderRadius: radius.control,
    borderWidth: 1.5,
    borderColor: semantic['action-primary'],
    backgroundColor: colors.surface,
    ...shadow.sm,
  },
  inputText: { flex: 1, minWidth: 0, minHeight: 44, fontFamily: fonts.regular, fontSize: 16, color: colors.text },
  clearText: { width: 32, height: 32, alignItems: 'center', justifyContent: 'center' },
  cancel: { minHeight: MIN_TARGET, justifyContent: 'center', paddingHorizontal: 4 },
  cancelText: { fontFamily: fonts.medium, fontSize: 15, color: colors.text },
  results: { gap: space[3] },
  smartApplied: { gap: space[2] },
  countRow: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap' },
  sort: { minHeight: MIN_TARGET, flexDirection: 'row', alignItems: 'center', gap: 4 },
  sortText: { fontFamily: fonts.bold, color: colors.text },
  row: { flexDirection: 'row', gap: space[3], paddingBottom: space[4], borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border, minHeight: MIN_TARGET },
  pressed: { opacity: 0.85 },
  name: { fontFamily: fonts.semibold, fontSize: 15, lineHeight: 20, color: colors.text },
  small: { fontFamily: fonts.regular, fontSize: 13, color: colors.textMuted },
  rating: { flexDirection: 'row', alignItems: 'center', gap: 4 },
});

