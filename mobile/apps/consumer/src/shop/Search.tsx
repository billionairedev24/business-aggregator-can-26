import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { router, useLocalSearchParams, type Href } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';

import { BottomSheet, CAP, FilterChip, Icon, LINE, MIN_TARGET, MetaLine, OptionCard, ServiceCard, TYPE, UnderlineTabs, radius, semantic, space, textStyles } from '@northline/mobile-kit';

import type { ProductCard, SearchItem, SearchQuery, Sort } from '../api/shop';
import type { MessageKey } from '../i18n';
import { Button } from '../ui/primitives';
import { ShopProductCard, iconFor, photoSource, searchImage } from '../ui/commerce';
import { GUTTER, Screen } from '../ui/screen';
import { CardSkeleton, EmptyState, QueryView } from '../ui/states';
import { shop, useMarket, useShopFormat } from './common';
import { Kicker } from './parts';
import { ProposalChips, SmartFilters } from './SmartFilters';
import { withProposals, type Proposal } from './smartSearch';

type Kind = 'all' | 'product' | 'service';
const KINDS: Kind[] = ['all', 'product', 'service'];
type Filter = 'tonight' | 'under10';
/** The chips the search api can answer for goods (the tier and dietary chips left the UI: thrash item 13, spec § 2.1). */
const FILTERS: Filter[] = ['tonight', 'under10'];
const SORTS: Sort[] = ['relevance', 'price_asc', 'price_desc', 'rating'];
const UNDER_10 = 999;

type Result =
  | { key: string; kind: 'product'; card: ProductCard; tonight?: boolean; soldOut?: boolean; fromSearch: boolean }
  | { key: string; kind: 'service'; item: SearchItem; to: Href | null };

const fromSearch = (i: SearchItem): Result =>
  i.kind === 'service'
    ? { key: `service-${i.id}`, kind: 'service', item: i, to: i.merchant.slug ? ({ pathname: '/providers/[slug]', params: { slug: i.merchant.slug } } as Href) : null }
    : {
        key: `product-${i.id}`,
        kind: 'product',
        // a product result is its offer (the product page takes an offer id too, S-50)
        card: { productId: i.id, offerId: i.id, name: i.name, merchantId: i.merchant.id, shopName: i.merchant.name, unit: null, priceCents: i.priceCents ?? 0, imageUrl: searchImage(i.imageKey), sellers: 1, run: null },
        tonight: !!i.onTonightsRun,
        soldOut: !!i.soldOut,
        fromSearch: true,
      };
const fromCard = (p: ProductCard): Result => ({ key: p.offerId, kind: 'product', card: p, fromSearch: false });

/**
 * Search results (E-15 A · Plainsight, design/revamp/a.html "Search results" on a phone): the search field with
 * Cancel, All · Products · Services as underline tabs, the filter chips (applied = ink edge with ✓) and S-158's
 * suggested filters, "<n> results for “…”" with the sort (a sheet), the tonight strip, then a two-up grid of the
 * kit's product cards (photo well, name, shop, price and unit price, "Get it Tonight", Add to cart) and service
 * cards ("from $89.00"). Text search is `GET /search` (S-44) with the person's province and position — no `kind` for
 * "all"; food and business results are left out. A department from Home browses `GET /public/shop/departments/{slug}`.
 */
export function Search() {
  const f = useShopFormat();
  const { t, locale } = f;
  const params = useLocalSearchParams<{ q?: string; department?: string; name?: string; kind?: string }>();
  const market = useMarket();
  const [text, setText] = useState(params.q ?? '');
  const [q, setQ] = useState(text);
  const [focused, setFocused] = useState(false);
  const [kind, setKind] = useState<Kind>(params.kind === 'product' || params.kind === 'service' ? params.kind : 'all');
  const [on, setOn] = useState<Record<Filter, boolean>>({ tonight: false, under10: false });
  const [sort, setSort] = useState<Sort>('relevance');
  const [sorting, setSorting] = useState(false);
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

  const rows: Result[] | undefined = department
    ? browse.data
      ? (() => {
          const list = browse.data.products.filter((p) => (!on.tonight || p.run?.day === 'today') && (!on.under10 || p.priceCents <= UNDER_10));
          if (sort === 'price_asc') list.sort((a, b) => a.priceCents - b.priceCents);
          if (sort === 'price_desc') list.sort((a, b) => b.priceCents - a.priceCents);
          return list.map(fromCard);
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
  const tonightCount = (rows ?? []).filter((r) => r.kind === 'product' && (r.fromSearch ? r.tonight : r.card.run?.day === 'today')).length;
  const place = market.label ?? browse.data?.market;

  return (
    <Screen testID="search" title={department ? (params.name ?? t('title.search')) : undefined}>
      {department ? null : (
        <View style={styles.bar}>
          <View style={[styles.input, focused && styles.inputFocused]}>
            <TextInput
              value={text}
              onChangeText={setText}
              accessibilityLabel={t('shop.search.label')}
              placeholder={t('rd.search.placeholder')}
              placeholderTextColor={semantic['text-subtle']}
              autoFocus={!params.q && !params.department}
              returnKeyType="search"
              onSubmitEditing={() => setQ(text.trim())}
              onFocus={() => setFocused(true)}
              onBlur={() => setFocused(false)}
              style={styles.inputText}
              testID="search-input"
            />
            {text ? (
              <Pressable accessibilityRole="button" accessibilityLabel={t('rd.search.clearText')} onPress={() => setText('')} style={styles.clearText}>
                <Icon name="x" size={TYPE.sub} />
              </Pressable>
            ) : null}
            <Pressable accessibilityRole="button" accessibilityLabel={t('shop.search.label')} onPress={() => setQ(text.trim())} style={styles.go}>
              <Icon name="magnifying-glass" size={TYPE.price} color={semantic['action-primary-text']} />
            </Pressable>
          </View>
          <Pressable accessibilityRole="button" accessibilityLabel={t('rd.search.cancel')} onPress={() => (router.canGoBack() ? router.back() : router.navigate('/home'))} style={styles.cancel} testID="search-cancel">
            <Text style={textStyles.label} maxFontSizeMultiplier={CAP.control}>
              {t('rd.search.cancel')}
            </Text>
          </Pressable>
        </View>
      )}
      {department ? <Kicker>{t('shop.search.department', { name: params.name ?? department })}</Kicker> : null}
      {department ? null : (
        <View style={styles.edge}>
          <UnderlineTabs label={t('rd.search.type')} options={KINDS.map((k) => ({ value: k, label: t(`rd.search.kind.${k}` as MessageKey) }))} value={kind} onChange={setKind} testID="kind" />
        </View>
      )}
      {goods ? (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={[styles.edge, styles.chipRow]} contentContainerStyle={styles.chips}>
          {FILTERS.map((x) => (
            <FilterChip key={x} label={t(`shop.filter.${x}` as MessageKey)} selected={on[x]} onPress={() => setOn((o) => ({ ...o, [x]: !o[x] }))} testID={`filter-${x}`} />
          ))}
        </ScrollView>
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
        skeleton={<CardSkeleton count={4} />}
        isEmpty={(d) => d.length === 0}
        empty={<EmptyState art="search" message={q ? t('shop.search.empty', { q }) : t('shop.search.emptyBrowse')} action={anyFilter ? t('shop.search.clear') : undefined} onAction={clear} />}
      >
        {(list) => (
          <View style={styles.results}>
            <View style={styles.resbar}>
              <Text style={styles.count} accessibilityRole="header">
                <Text style={styles.countStrong}>{q && !department ? t('rv.results.count', { n: total, q }) : t('rv.results.countAll', { n: total })}</Text>
                {place ? ` · ${t('rv.results.place', { place })}` : ''}
              </Text>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={t('shop.search.sortLabel', { sort: t(`shop.sort.${sort}` as MessageKey) })}
                accessibilityHint={t('shop.search.sortHint')}
                onPress={() => setSorting(true)}
                style={({ pressed }) => [styles.sort, pressed && styles.pressed]}
                testID="search-sort"
              >
                <Text style={textStyles.body} maxFontSizeMultiplier={CAP.control}>
                  {t(`rv.sort.${sort}` as MessageKey)}
                </Text>
                <Icon name="caret-down" size={TYPE.meta} color={semantic['text-muted']} />
              </Pressable>
            </View>
            {goods && tonightCount > 0 ? (
              <View style={styles.tonight} testID="search-tonight">
                <MetaLine icon="truck">
                  <Text style={styles.countStrong}>{t('rv.results.tonight', { n: tonightCount })}</Text> {t('rv.results.tonightSub')}
                </MetaLine>
              </View>
            ) : null}
            <View style={styles.grid}>
              {list.map((r) => (
                <View key={r.key} style={styles.cell} testID={`result-${r.key}`}>
                  {r.kind === 'product' ? (
                    <ShopProductCard
                      p={r.card}
                      eta={r.fromSearch ? (r.tonight ? t('rv.card.etaTonight') : null) : undefined}
                      low={r.soldOut ? t('shop.tag.soldOut') : null}
                    />
                  ) : (
                    <ServiceResult r={r.item} to={r.to} />
                  )}
                </View>
              ))}
            </View>
            {!department && search.hasNextPage ? (
              <Button label={t('shop.search.more')} tone="secondary" busy={search.isFetchingNextPage} onPress={() => void search.fetchNextPage()} />
            ) : null}
          </View>
        )}
      </QueryView>
      <BottomSheet visible={sorting} onClose={() => setSorting(false)} title={t('rv.sort.title')} testID="sort-sheet">
        <View style={styles.sortList} accessibilityRole="radiogroup">
          {SORTS.map((s) => (
            <OptionCard
              key={s}
              title={t(`rv.sort.${s}` as MessageKey)}
              selected={s === sort}
              onPress={() => {
                setSort(s);
                setSorting(false);
              }}
              testID={`sort-${s}`}
            />
          ))}
        </View>
      </BottomSheet>
    </Screen>
  );
}

/** A service result: the kit's ServiceCard — the category's photo well, the service, who offers it, "from $89.00". */
function ServiceResult({ r, to }: { r: SearchItem; to: Href | null }) {
  const f = useShopFormat();
  const { t } = f;
  const meta = [r.merchant.name, r.category?.name].filter(Boolean).join(' · ');
  const price = r.priceCents != null ? t('rd.search.from', { price: f.money(r.priceCents) }) : null;
  return (
    <ServiceCard
      title={r.name}
      meta={meta}
      fromCents={r.priceCents ?? undefined}
      rating={r.rating ? { value: r.rating } : undefined}
      source={photoSource(searchImage(r.imageKey))}
      icon={iconFor('service')}
      onPress={to ? () => router.push(to) : undefined}
      label={[r.name, t('rd.search.kind.service'), meta, price].filter(Boolean).join(', ')}
    />
  );
}

const styles = StyleSheet.create({
  pressed: { opacity: 0.85 },
  bar: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  input: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'stretch',
    minHeight: MIN_TARGET,
    borderRadius: radius.control,
    borderWidth: LINE.bold,
    borderColor: semantic['border-control'],
    backgroundColor: semantic.surface,
    overflow: 'hidden',
  },
  // S-188: the violet focus edge while typing, the violet search button (the action)
  inputFocused: { borderColor: semantic.focus },
  inputText: { ...StyleSheet.flatten(textStyles.sub), fontFamily: textStyles.body.fontFamily, flex: 1, minWidth: 0, minHeight: MIN_TARGET - 2 * LINE.bold, paddingHorizontal: space[3] },
  clearText: { width: MIN_TARGET, minHeight: MIN_TARGET, alignItems: 'center', justifyContent: 'center' },
  go: { width: MIN_TARGET + space[2], minHeight: MIN_TARGET, alignItems: 'center', justifyContent: 'center', backgroundColor: semantic['action-primary'] },
  cancel: { minHeight: MIN_TARGET, justifyContent: 'center', paddingHorizontal: space[1] },
  edge: { marginHorizontal: -GUTTER },
  chipRow: { flexGrow: 0 },
  chips: { flexDirection: 'row', gap: space[2], paddingHorizontal: GUTTER, paddingVertical: space[1] },
  smartApplied: { gap: space[2] },
  results: { gap: space[3] },
  resbar: { gap: space[2], paddingBottom: space[3], borderBottomWidth: LINE.hair, borderBottomColor: semantic.border },
  count: textStyles.label,
  countStrong: { fontFamily: textStyles.section.fontFamily },
  sort: {
    alignSelf: 'flex-start',
    flexDirection: 'row',
    alignItems: 'center',
    gap: space[3],
    minHeight: MIN_TARGET,
    paddingHorizontal: space[3],
    borderRadius: radius.control,
    borderWidth: LINE.hair,
    borderColor: semantic['border-control'],
    backgroundColor: semantic.surface,
  },
  tonight: { padding: space[3], borderRadius: radius.panel, backgroundColor: semantic.well },
  grid: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', rowGap: space[6] },
  cell: { width: '48.5%' },
  sortList: { gap: space[2] },
});
