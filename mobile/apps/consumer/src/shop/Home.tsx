import { useQuery } from '@tanstack/react-query';
import { router, type Href } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';

import {
  ApiError,
  Button,
  CAP,
  Icon,
  LINE,
  LogoMark,
  MIN_TARGET,
  PhotoWell,
  Photo,
  SIZE,
  ServiceCard,
  TYPE,
  UnderlineTabs,
  brand,
  photoFor,
  radius,
  semantic,
  slugSlot,
  space,
  textStyles,
} from '@northline/mobile-kit';

import { nameIn, servicesApi } from '../api/services';
import type { DepartmentTile, Landing, Run, ShopCard } from '../api/shop';
import { services } from '../services';
import { useAuth } from '../auth/AuthProvider';
import type { MessageKey } from '../i18n';
import { PlaceBar } from '../location/PlaceBar';
import { Body, Card } from '../ui/primitives';
import { ShopProductCard, iconFor, photoSource } from '../ui/commerce';
import { RoundButton, Screen } from '../ui/screen';
import { ErrorState, Skeleton } from '../ui/states';
import { appRoute, shop, useMarket, useShopFormat } from './common';
import { SectionHead, Strip } from './parts';
import { RewardsNear } from './RewardsNear';

/** The service categories Home offers (category slugs of the S-100 pages). */
const SERVICE_TILES: ReadonlyArray<[MessageKey, string]> = [
  ['shop.svc.mechanic', 'mobile-mechanic'],
  ['shop.svc.cleaning', 'house-cleaning'],
  ['shop.svc.plumber', 'plumber'],
  ['shop.svc.bar', 'cocktail-and-mocktail-bar'],
  ['shop.svc.realtor', 'real-estate-agent'],
  ['shop.svc.barber', 'barber-and-hair'],
  ['shop.svc.tutor', 'tutor-k-12'],
];

/** A rail card's width: two cards and the edge of a third on a 390 pt phone (the mockup's 44 % column). */
const RAIL_CARD = SIZE.rail * 2 + space[4];

const nullOn404 = async <T,>(call: () => Promise<T | null>): Promise<T | null> => {
  try {
    return await call();
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 401)) return null;
    throw e;
  }
};

const TIER_RANK: Record<string, number> = { master: 3, trusted: 2, registered: 1 };

/**
 * The maker of the week (DECISIONS 2026-10-23 S-178, as the web's S-170): the api has no such feature, so it is the
 * Shop landing's highest-tier shop with a photo, one on the next pooled run first, then the one with the most
 * products — the same for everyone in a market.
 */
export function pickMaker(shops: readonly ShopCard[], run: Run | null | undefined): ShopCard | null {
  const score = (s: ShopCard) => (TIER_RANK[s.tier] ?? 0) * 1000 + (s.imageUrl ? 500 : 0) + (run && s.run?.windowId === run.windowId ? 200 : 0) + Math.min(s.products, 199);
  return [...shops].sort((a, b) => score(b) - score(a) || a.name.localeCompare(b.name))[0] ?? null;
}

/** Minutes until an instant (at least 0), re-read every minute. */
function useMinutesLeft(iso: string | null | undefined) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!iso) return;
    const id = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(id);
  }, [iso]);
  return iso ? Math.max(0, Math.round((Date.parse(iso) - now) / 60_000)) : null;
}

/**
 * Home (E-15 A · Plainsight, design/revamp/a.html "Consumer home" on a phone, with Main Street's maker of the week
 * from b.html as the hero): the header — the brand, the inbox, the one search bar and "Deliver to" (the location
 * sheet) — with the department strip under it; what's under way (`GET /me/upcoming`) for a signed-in person; the
 * delivery cut-off strip of the next pooled run (`GET /public/shop` → `run`, its order-by time counting down in the
 * commerce colour); the maker of the week (one featured local shop: its photo, story and two actions) and its two side
 * tiles (book a pro, start a group order); the departments and services tiles; S-152's rewards near you; "Popular
 * near …" as product cards with Add to cart; top-rated pros; the sellers' line.
 */
export function Home() {
  const f = useShopFormat();
  const { t, locale } = f;
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const market = useMarket();
  const landing = useQuery({
    queryKey: ['shop', 'landing', market.city?.toLowerCase() ?? '', locale],
    queryFn: () => shop().landing(market.city, locale),
    enabled: market.ready,
    staleTime: 60_000,
  });
  const week = useQuery({ queryKey: ['shop', 'upcoming'], queryFn: () => nullOn404(() => shop().upcoming()), enabled: signedIn, staleTime: 60_000 });

  const refresh = () => void Promise.all([landing.refetch(), signedIn ? week.refetch() : null]);
  // a city, not the person's street ("Popular near Sampleville", "Tonight's pooled run to Sampleville")
  const place = market.city ?? landing.data?.market;
  const active = week.data?.items.map((w) => ({ w, to: appRoute(w.href) })).find((x) => !!x.to);
  const departments = landing.data?.departments ?? [];

  return (
    <Screen
      testID="home"
      padded={false}
      top={
        <PlaceBar
          lead={
            <View accessible accessibilityRole="image" accessibilityLabel={brand.name} style={styles.brand} testID="logo">
              <LogoMark size={SIZE['header-mark']} />
              <Text style={styles.wordmark} numberOfLines={1} maxFontSizeMultiplier={CAP.control} importantForAccessibility="no">
                {brand.name}
              </Text>
            </View>
          }
          actions={<RoundButton icon="bell" label={t('shop.home.notifications')} onPress={() => router.push('/notifications')} testID="home-inbox" />}
          below={<DepartmentStrip departments={departments} />}
        />
      }
      refreshControl={<RefreshControl refreshing={landing.isRefetching} onRefresh={refresh} />}
    >
      {landing.data?.run && landing.data.served ? <CutoffStrip run={landing.data.run} place={place ?? landing.data.market} /> : null}

      {active?.to ? (
        <View style={styles.pad}>
          <Card
            onPress={() => router.push(active.to as Href)}
            label={[t('lt.home.underway'), active.w.title, active.w.subtitle, active.w.state].filter(Boolean).join(', ')}
            style={styles.active}
            testID="home-active"
          >
            <Icon name={/booking|provider/.test(active.to) ? 'calendar-check' : 'truck'} size={TYPE.price} color={semantic['status-info-fg']} />
            <View style={styles.flex}>
              <Text style={textStyles.caps}>{t('lt.home.underway')}</Text>
              <Text style={textStyles.sub} numberOfLines={2}>
                {active.w.title}
              </Text>
              <Text style={textStyles.meta} numberOfLines={1}>
                {[active.w.subtitle, active.w.state].filter(Boolean).join(' · ')}
              </Text>
            </View>
            <Icon name="caret-right" size={TYPE.sub} />
          </Card>
        </View>
      ) : null}

      <View style={styles.pad}>
        <MakerHero landing={landing.data} loading={landing.isPending} />
      </View>

      {landing.isPending ? (
        <View testID="loading" accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={[styles.pad, styles.loading]}>
          <Skeleton height={space[6]} width="50%" />
          <Skeleton height={space[16] * 2} />
        </View>
      ) : landing.isError && !landing.data ? (
        <View style={styles.pad}>
          <ErrorState error={landing.error} onRetry={() => void landing.refetch()} />
        </View>
      ) : (
        <>
          {landing.isError ? (
            <View style={styles.pad}>
              <ErrorState error={landing.error} onRetry={() => void landing.refetch()} />
            </View>
          ) : null}
          {landing.data && !landing.data.served ? (
            <View style={styles.pad}>
              <Body tone="muted">{t('shop.home.notServed', { place: landing.data.market })}</Body>
            </View>
          ) : null}
          {landing.data && landing.data.departments.length === 0 ? (
            <View style={styles.pad}>
              <Body tone="muted">{t('shop.home.noShops')}</Body>
            </View>
          ) : null}

          <View style={[styles.pad, styles.tiles]}>
            {departments.length > 0 ? (
              <Tile
                title={t('rv.tile.shop')}
                more={t('rv.tile.shopMore')}
                onMore={() => router.navigate('/services')}
                items={departments.slice(0, 4).map((d) => ({ key: d.slug, label: d.name, slot: slugSlot('shop', d.slug), icon: iconFor('shop'), onPress: () => openDepartment(d) }))}
                testID="home-departments"
              />
            ) : null}
            <Tile
              title={t('rv.tile.services')}
              more={t('rv.tile.servicesMore')}
              onMore={() => router.navigate('/services')}
              items={SERVICE_TILES.slice(0, 4).map(([key, slug]) => ({ key: slug, label: t(key), slot: slugSlot('service', slug), icon: iconFor('service'), onPress: () => router.push(`/services/${slug}` as Href) }))}
              testID="home-services"
            />
          </View>

          <RewardsNear />

          {landing.data?.popular.length ? (
            <View style={styles.section} testID="home-popular">
              <View style={styles.pad}>
                <SectionHead title={place ? t('rv.rail.popular', { place }) : t('rv.rail.popularAnywhere')} link={t('rv.rail.seeAll')} onLink={() => router.push('/search')} />
              </View>
              <Strip edge>
                {landing.data.popular.slice(0, 10).map((p) => (
                  <View key={p.offerId} style={styles.railCard}>
                    <ShopProductCard p={p} />
                  </View>
                ))}
              </Strip>
            </View>
          ) : null}

          <PopularServices />
        </>
      )}

      <View style={styles.pad}>
        <Card sunken style={styles.sell}>
          <Text style={textStyles.sub}>{t('lt.home.sellTitle')}</Text>
          <Body tone="small">{t('lt.home.sellSub')}</Body>
        </Card>
      </View>
    </Screen>
  );
}

/**
 * "Popular services near you": the live categories with the most verified providers (`GET /public/services`, the
 * Browse tab's), as the kit's service cards — the category's photo, its name and how many pros come to you.
 */
function PopularServices() {
  const { t, locale } = useShopFormat();
  const api = servicesApi(services().api);
  const landing = useQuery({ queryKey: ['services', 'landing', locale], queryFn: () => api.landing(locale === 'fr-CA' ? 'fr' : 'en'), staleTime: 60_000 });
  const popular = (landing.data?.groups ?? []).flatMap((g) => g.items).filter((i) => i.providers > 0).sort((a, b) => b.providers - a.providers).slice(0, 8);
  if (popular.length === 0) return null;
  return (
    <View style={styles.section} testID="home-pros">
      <View style={styles.pad}>
        <SectionHead title={t('rv.rail.pros')} link={t('rv.rail.seeServices')} onLink={() => router.navigate('/services')} />
      </View>
      <Strip edge>
        {popular.map((i) => {
          const photo = photoFor(slugSlot('service', i.slug) ?? [], 'square');
          const name = nameIn(i.names, locale);
          return (
            <View key={i.slug} style={styles.railCard}>
              <ServiceCard
                title={name}
                meta={t('rv.card.pros', { n: i.providers })}
                photo={photo ? <Photo slot={[]} photo={photo} crop="square" style={StyleSheet.absoluteFill} /> : undefined}
                icon={iconFor('service')}
                onPress={() => router.push(`/services/${i.slug}` as Href)}
                label={`${name}, ${t('rv.card.pros', { n: i.providers })}`}
                testID={`home-pro-${i.slug}`}
              />
            </View>
          );
        })}
      </Strip>
    </View>
  );
}

const openDepartment = (d: DepartmentTile) => router.push({ pathname: '/search', params: { department: d.slug, name: d.name } });

/**
 * The department strip under the header (a.html's grey strip): All · Services · Shop, then the market's departments —
 * the kit's underline tabs on the well grey, "All" current on Home; each opens its landing.
 */
function DepartmentStrip({ departments }: { departments: readonly DepartmentTile[] }) {
  const { t } = useShopFormat();
  const options = [
    { value: 'all', label: t('rv.dept.all') },
    { value: 'services', label: t('rv.dept.services') },
    { value: 'shop', label: t('rv.dept.shop') },
    ...departments.slice(0, 8).map((d) => ({ value: `d:${d.slug}`, label: d.name })),
  ];
  return (
    <View style={styles.strip} testID="home-dept-strip">
      <UnderlineTabs
        label={t('rv.dept.label')}
        options={options}
        value="all"
        onChange={(v) => {
          if (v === 'services') router.navigate('/services');
          else if (v === 'shop') router.push({ pathname: '/search', params: { kind: 'product' } });
          else if (v.startsWith('d:')) {
            const d = departments.find((x) => `d:${x.slug}` === v);
            if (d) openDepartment(d);
          }
        }}
      />
    </View>
  );
}

/**
 * The delivery cut-off strip (a.html's promo strip): "Tonight's pooled run to <place> closes at 5:30 p.m. Mix any
 * shops for one $2.99 delivery." and the time left in the commerce colour (the cut-off timer's role).
 */
function CutoffStrip({ run, place }: { run: Run; place: string }) {
  const f = useShopFormat();
  const { t } = f;
  const left = useMinutesLeft(run.orderBy);
  const when = f.which(run);
  const title = when === 'later' ? t('rv.cutoff.later', { place, time: f.time(run.orderBy), weekday: f.weekday(run.orderBy) }) : t(`rv.cutoff.${when}` as MessageKey, { place, time: f.time(run.orderBy) });
  const sub = t('rv.cutoff.sub', { fee: f.fee(run.feeCents) });
  const leftText = left === null ? '' : left >= 60 ? t('rv.cutoff.leftHours', { h: Math.floor(left / 60), m: left % 60 }) : t('rv.cutoff.leftMinutes', { m: left });
  return (
    <View style={styles.cutoff} accessible accessibilityRole="summary" accessibilityLabel={[title, sub, leftText].join(' ')} testID="home-run">
      <Icon name="truck" size={TYPE.price} color={semantic['status-info-fg']} />
      <View style={styles.flex}>
        <Text style={styles.cutoffText}>
          <Text style={styles.cutoffStrong}>{title}</Text> {sub}
        </Text>
        {left !== null && left > 0 ? (
          <View style={styles.cutoffLeft}>
            <Icon name="timer" size={TYPE.body} color={semantic.commerce} />
            <Text style={styles.cutoffTimer}>{leftText}</Text>
          </View>
        ) : null}
      </View>
    </View>
  );
}

/**
 * The maker of the week (b.html's hero in Plainsight): the shop's photo in the grey well, the caps kicker, the
 * headline, the story from its own numbers, "Shop <name>" (primary) and "More <department>" (secondary); then the two
 * side tiles. No shop to feature (not served yet): the same frame with a way into Shop and Services.
 */
function MakerHero({ landing, loading }: { landing: Landing | null | undefined; loading: boolean }) {
  const f = useShopFormat();
  const { t } = f;
  const maker = landing?.served ? pickMaker(landing.shops, landing.run) : null;
  const run = maker && landing?.run && maker.run?.windowId === landing.run.windowId ? landing.run : null;
  const tierWord = maker ? t(`rv.tier.${maker.tier === 'master' || maker.tier === 'trusted' ? maker.tier : 'registered'}` as MessageKey) : '';
  const words = maker ? { name: maker.name, department: maker.departmentName, products: maker.products, area: landing?.market ?? '', tier: tierWord } : null;
  const when = run ? f.which(run) : null;
  const slot = maker ? slugSlot('shop', maker.departmentSlug) : null;
  const stock = photoFor([slot, 'banner/shop'], 'card');
  return (
    <View style={styles.hero} accessibilityLabel={t('rv.hero.label')} testID="home-hero">
      {loading ? (
        <View accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.heroCopy}>
          <Skeleton height={space[16] * 3} />
          <Skeleton height={space[3]} width="50%" />
          <Skeleton height={space[6]} width="90%" />
        </View>
      ) : (
        <>
          <View style={styles.heroMedia}>
            {maker?.imageUrl ? (
              <PhotoWell source={photoSource(maker.imageUrl)} inset={0} fit="cover" style={styles.heroWell} />
            ) : stock ? (
              <Photo slot={[]} photo={stock} style={styles.heroPhoto} />
            ) : (
              <PhotoWell icon={iconFor('shop')} caption={maker?.name} style={styles.heroWell} />
            )}
          </View>
          <View style={styles.heroCopy}>
            {maker && words ? (
              <>
                <Text style={textStyles.caps}>{t('rv.hero.kicker', { department: maker.departmentName })}</Text>
                <Text accessibilityRole="header" style={styles.heroTitle} testID="home-hero-title">
                  {run && when ? t(`rv.hero.${when}` as MessageKey, { name: maker.name, weekday: f.weekday(run.startsAt) }) : t('rv.hero.titleNoRun', { name: maker.name, tier: tierWord })}
                </Text>
                <Body tone="muted">{run ? t('rv.hero.story', { ...words, time: f.time(run.orderBy) }) : t('rv.hero.storyNoRun', words)}</Body>
                <View style={styles.heroActions}>
                  <Button
                    label={t('rv.hero.shop', { name: maker.name })}
                    onPress={() => router.push({ pathname: '/search', params: { department: maker.departmentSlug, name: maker.departmentName } })}
                    testID="hero-shop"
                  />
                  <Button label={t('rv.hero.more', { department: maker.departmentName.toLowerCase() })} variant="secondary" onPress={() => router.navigate('/services')} testID="hero-more" />
                </View>
              </>
            ) : (
              <>
                <Text style={textStyles.caps}>{t('rv.hero.label')}</Text>
                <Text accessibilityRole="header" style={styles.heroTitle} testID="home-hero-title">
                  {t('rv.hero.fallbackTitle')}
                </Text>
                <Body tone="muted">{t('rv.hero.fallbackStory')}</Body>
                <View style={styles.heroActions}>
                  <Button label={t('rv.hero.fallbackShop')} onPress={() => router.push({ pathname: '/search', params: { kind: 'product' } })} testID="hero-shop" />
                  <Button label={t('rv.hero.fallbackServices')} variant="secondary" onPress={() => router.navigate('/services')} testID="hero-more" />
                </View>
              </>
            )}
          </View>
        </>
      )}
      <SideTile slot="hero/home-side-b" title={t('rv.side.proTitle')} text={t('rv.side.proText')} onPress={() => router.navigate('/services')} testID="hero-side-pro" />
      <SideTile slot="banner/food" title={t('rv.side.groupTitle')} text={t('rv.side.groupText')} onPress={() => router.push('/group')} testID="hero-side-group" />
    </View>
  );
}

function SideTile({ slot, title, text, onPress, testID }: { slot: string; title: string; text: string; onPress: () => void; testID?: string }) {
  const photo = photoFor(slot, 'square');
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={`${title}, ${text}`} onPress={onPress} style={({ pressed }) => [styles.side, pressed && styles.pressed]} testID={testID}>
      <View style={styles.sidePic}>{photo ? <Photo slot={[]} photo={photo} crop="square" style={StyleSheet.absoluteFill} /> : <PhotoWell icon="basket" inset={0} />}</View>
      <View style={styles.flex}>
        <Text style={textStyles.sub}>{title}</Text>
        <Text style={textStyles.meta}>{text}</Text>
      </View>
    </Pressable>
  );
}

/** A home tile (a.html's category tiles): the title, four photos in grey wells with their names, and the tile's link. */
function Tile({ title, more, onMore, items, testID }: { title: string; more: string; onMore: () => void; items: Array<{ key: string; label: string; slot: string | null; icon: ReturnType<typeof iconFor>; onPress: () => void }>; testID?: string }) {
  return (
    <View style={styles.tile} testID={testID}>
      <Text accessibilityRole="header" style={textStyles.sub}>
        {title}
      </Text>
      <View style={styles.tileGrid}>
        {items.map((it) => {
          const photo = photoFor(it.slot ? [it.slot] : [], 'square');
          return (
            <Pressable key={it.key} accessibilityRole="button" accessibilityLabel={it.label} onPress={it.onPress} style={({ pressed }) => [styles.tileItem, pressed && styles.pressed]}>
              <View style={styles.tilePic}>{photo ? <Photo slot={[]} photo={photo} crop="square" style={StyleSheet.absoluteFill} /> : <PhotoWell icon={it.icon} inset={0} />}</View>
              <Text style={textStyles.meta} numberOfLines={1}>
                {it.label}
              </Text>
            </Pressable>
          );
        })}
      </View>
      <Pressable accessibilityRole="link" accessibilityLabel={more} onPress={onMore} style={styles.tileMore}>
        <Text style={[textStyles.label, textStyles.link]}>{more}</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, minWidth: 0, gap: LINE.bold },
  pad: { paddingHorizontal: space[4], gap: space[4] },
  pressed: { opacity: 0.85 },
  brand: { flexDirection: 'row', alignItems: 'center', gap: space[2], minHeight: MIN_TARGET },
  wordmark: { ...StyleSheet.flatten(textStyles.section), flexShrink: 1 },
  strip: { backgroundColor: semantic.well, borderTopWidth: LINE.hair, borderTopColor: semantic.border },
  cutoff: { flexDirection: 'row', alignItems: 'flex-start', gap: space[2], marginTop: -space[3], paddingHorizontal: space[4], paddingVertical: space[3], backgroundColor: semantic['status-info-bg'] },
  cutoffText: { ...StyleSheet.flatten(textStyles.label), fontFamily: textStyles.body.fontFamily, color: semantic['status-info-fg'] },
  cutoffStrong: { fontFamily: textStyles.section.fontFamily },
  cutoffLeft: { flexDirection: 'row', alignItems: 'center', gap: space[1], paddingTop: space[1] },
  cutoffTimer: { ...StyleSheet.flatten(textStyles.label), fontFamily: textStyles.section.fontFamily, color: semantic.commerce },
  active: { flexDirection: 'row', alignItems: 'center', gap: space[3], backgroundColor: semantic['status-info-bg'], borderColor: semantic['status-info-bg'] },
  hero: { gap: space[3], padding: space[3], borderRadius: radius.panel, borderWidth: LINE.hair, borderColor: semantic['border-strong'], backgroundColor: semantic.surface },
  heroMedia: { borderRadius: radius.image, overflow: 'hidden' },
  heroWell: { aspectRatio: 4 / 3 },
  heroPhoto: { width: '100%', aspectRatio: 4 / 3, borderRadius: radius.image },
  heroCopy: { gap: space[2] },
  heroTitle: textStyles.pageTitle,
  heroActions: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2], paddingTop: space[1] },
  side: { flexDirection: 'row', alignItems: 'center', gap: space[3], minHeight: MIN_TARGET, padding: space[2], borderRadius: radius.panel, backgroundColor: semantic.well },
  sidePic: { width: SIZE.rail, height: SIZE.rail, borderRadius: radius.image, overflow: 'hidden', backgroundColor: semantic.well },
  loading: { gap: space[3] },
  section: { gap: space[2] },
  tiles: { flexDirection: 'row', alignItems: 'stretch', gap: space[2.5] },
  tile: { flex: 1, minWidth: 0, gap: space[2], padding: space[3], borderRadius: radius.panel, borderWidth: LINE.hair, borderColor: semantic['border-strong'], backgroundColor: semantic.surface },
  tileGrid: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', rowGap: space[2] },
  tileItem: { width: '47%', gap: space[1], minHeight: MIN_TARGET },
  tilePic: { width: '100%', aspectRatio: 1, borderRadius: radius.image, overflow: 'hidden', backgroundColor: semantic.well },
  tileMore: { minHeight: MIN_TARGET, justifyContent: 'center', marginTop: 'auto' },
  railCard: { width: RAIL_CARD },
  sell: { marginTop: space[2] },
});
