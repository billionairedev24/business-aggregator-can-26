import { useQuery } from '@tanstack/react-query';
import { router, type Href } from 'expo-router';
import { Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';

import { ApiError, Icon, LogoMark, MIN_TARGET, brand, colors, fonts, radius, semantic, shadow, space, Scene, slugSlot } from '@northline/mobile-kit';

import type { MessageKey } from '../i18n';
import { useAuth } from '../auth/AuthProvider';
import { PlaceBar } from '../location/PlaceBar';
import { Body, Card } from '../ui/primitives';
import { RoundButton, Screen } from '../ui/screen';
import { ErrorState, Skeleton } from '../ui/states';
import { appRoute, shop, useMarket, useShopFormat } from './common';
import { ProductTile, SectionHead, Strip, Tile } from './parts';
import { RewardsNear } from './RewardsNear';

/** The service categories Home offers as illustrated tiles (category slugs of the S-100 pages). */
const SERVICE_TILES: ReadonlyArray<[MessageKey, string]> = [
  ['shop.svc.mechanic', 'mobile-mechanic'],
  ['shop.svc.cleaning', 'house-cleaning'],
  ['shop.svc.plumber', 'plumber'],
  ['shop.svc.bar', 'cocktail-and-mocktail-bar'],
  ['shop.svc.realtor', 'real-estate-agent'],
  ['shop.svc.barber', 'barber-and-hair'],
  ['shop.svc.tutor', 'tutor-k-12'],
];

const nullOn404 = async <T,>(call: () => Promise<T | null>): Promise<T | null> => {
  try {
    return await call();
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 401)) return null;
    throw e;
  }
};

/**
 * Home (light & photo-first; the consumer web's home on a phone): the top bar — the brand's mark, where things come
 * from (opens the location sheet), the inbox and the account — and the one search pill; what's under way
 * (`GET /me/upcoming`'s first item that has a screen) as a light card; the hero (the display face, an illustrated
 * collage, "every pro and shop verified"); "Book a service" and "Shop local" as rails of illustrated tiles; tonight's
 * pooled run (`GET /public/shop` → `run`); S-152's rewards near you; "Popular near you" as photo-first product cards;
 * the sellers' line.
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
  const me = useQuery({ queryKey: ['me'], queryFn: () => shop().me(), enabled: signedIn, staleTime: 300_000 });
  const week = useQuery({ queryKey: ['shop', 'upcoming'], queryFn: () => nullOn404(() => shop().upcoming()), enabled: signedIn, staleTime: 60_000 });

  const run = landing.data?.run;
  const refresh = () => void Promise.all([landing.refetch(), signedIn ? week.refetch() : null]);
  const place = market.label ?? landing.data?.market;
  const initials = me.data ? `${me.data.firstName.charAt(0)}${me.data.lastName.charAt(0)}`.toUpperCase() : '';
  const active = week.data?.items.map((w) => ({ w, to: appRoute(w.href) })).find((x) => !!x.to);
  const departments = landing.data?.departments ?? [];

  return (
    <Screen
      testID="home"
      padded={false}
      top={
        <PlaceBar
          lead={
            <View accessible accessibilityRole="image" accessibilityLabel={brand.name} style={styles.mark} testID="logo">
              <LogoMark size={32} />
            </View>
          }
          actions={
            <>
              <RoundButton icon="bell" label={t('shop.home.notifications')} onPress={() => router.push('/notifications')} testID="home-inbox" />
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={signedIn ? t('rd.home.account') : t('rd.home.signIn')}
                onPress={() => router.navigate('/account')}
                style={[styles.avatar, !initials && styles.avatarGuest]}
                testID="home-account"
              >
                {initials ? <Text style={styles.initials}>{initials}</Text> : <Icon name="user" size={20} />}
              </Pressable>
            </>
          }
        />
      }
      refreshControl={<RefreshControl refreshing={landing.isRefetching} onRefresh={refresh} />}
    >
      <View style={styles.pad}>
        {active?.to ? (
          <Card
            onPress={() => router.push(active.to as Href)}
            label={[t('lt.home.underway'), active.w.title, active.w.subtitle, active.w.state].filter(Boolean).join(', ')}
            style={styles.active}
            testID="home-active"
          >
            <View style={styles.activeIcon}>
              <Icon name={/booking|provider/.test(active.to) ? 'calendar-check' : 'truck'} size={24} color={semantic['action-primary']} />
            </View>
            <View style={styles.flex}>
              <Text style={styles.activeKicker}>{t('lt.home.underway')}</Text>
              <Text style={styles.activeTitle} numberOfLines={2}>
                {active.w.title}
              </Text>
              <Text style={styles.activeSub} numberOfLines={1}>
                {[active.w.subtitle, active.w.state].filter(Boolean).join(' · ')}
              </Text>
            </View>
            <Icon name="caret-right" size={18} />
          </Card>
        ) : null}

        <View style={styles.hero} testID="home-hero">
          <Text accessibilityRole="header" style={styles.heroTitle}>
            {t('lt.home.heroTitle')}
          </Text>
          <Body tone="muted">{t('lt.home.heroSub')}</Body>
          <View style={styles.collage}>
            <Scene slot="hero/home-main" subject="mobile-mechanic" style={styles.collageMain} />
            <View style={styles.collageSide}>
              <Scene slot="hero/home-side-a" subject="bakery" crop="square" style={styles.collageSmall} />
              <Scene slot="hero/home-side-b" subject="house-cleaning" crop="square" style={styles.collageSmall} />
            </View>
            <View style={styles.badge}>
              <Icon name="seal-check" size={18} filled color={semantic.success} />
              <Text style={styles.badgeText} numberOfLines={1}>
                {t('lt.home.badge')}
              </Text>
            </View>
          </View>
        </View>
      </View>

      <View style={styles.section} testID="home-services">
        <View style={styles.pad}>
          <SectionHead title={t('lt.home.tileServices')} link={t('lt.home.allServices')} onLink={() => router.navigate('/services')} />
        </View>
        <Strip edge>
          {SERVICE_TILES.map(([key, slug]) => (
            <Tile key={slug} label={t(key)} slot={slugSlot('service', slug)} subject={slug} width={148} onPress={() => router.push(`/services/${slug}` as Href)} />
          ))}
        </Strip>
      </View>

      {landing.isPending ? (
        <View testID="loading" accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={[styles.pad, styles.loading]}>
          <Skeleton height={24} width="50%" />
          <Skeleton height={120} />
          <Skeleton height={180} />
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
          {departments.length > 0 ? (
            <View style={styles.section} testID="home-departments">
              <View style={styles.pad}>
                <SectionHead title={t('lt.home.tileShop')} link={t('lt.home.allDepartments')} onLink={() => router.navigate('/services')} />
              </View>
              <Strip edge>
                {departments.slice(0, 8).map((d) => (
                  <Tile key={d.slug} label={d.name} slot={slugSlot('shop', d.slug)} subject={d.slug} width={148} onPress={() => router.push({ pathname: '/search', params: { department: d.slug, name: d.name } })} />
                ))}
              </Strip>
            </View>
          ) : null}

          <View style={styles.pad}>
            <Card style={styles.run} testID="home-run">
              <View style={styles.runText} accessible accessibilityRole="summary">
                <Text style={styles.runKicker}>{run ? t(run.day === 'today' ? 'shop.home.runTonight' : 'shop.home.runNext') : t('rd.home.heroKicker')}</Text>
                <Text style={styles.runTitle}>{run ? t('rd.home.runTitle', { time: f.time(run.startsAt) }) : t('rd.home.heroTitle', { place: place ?? '' })}</Text>
                <Text style={styles.runSub}>
                  {run
                    ? [t('rd.home.runSub', { fee: f.fee(run.feeCents), orderBy: f.time(run.orderBy) }), run.households > 0 ? t('shop.home.neighbours', { n: run.households }) : null].filter(Boolean).join(' · ')
                    : t('rd.home.heroSub')}
                </Text>
              </View>
              <Scene slot={run ? 'banner/order-complete' : 'banner/shop'} subject={run ? 'courier' : 'shop'} crop="square" style={styles.runArt} />
            </Card>
            {landing.data && !landing.data.served ? <Body tone="muted">{t('shop.home.notServed', { place: landing.data.market })}</Body> : null}
            {landing.data && landing.data.departments.length === 0 ? <Body tone="muted">{t('shop.home.noShops')}</Body> : null}
          </View>

          <RewardsNear />

          {landing.data?.popular.length ? (
            <View style={styles.section} testID="home-popular">
              <View style={styles.pad}>
                <SectionHead title={t('rd.home.popular')} link={t('rd.common.seeAll')} onLink={() => router.push('/search')} />
              </View>
              <Strip edge>
                {landing.data.popular.slice(0, 10).map((p) => (
                  <ProductTile
                    key={p.offerId}
                    name={p.name}
                    price={f.money(p.priceCents)}
                    meta={p.shopName}
                    imageUrl={p.imageUrl}
                    label={[p.name, p.shopName, f.money(p.priceCents)].join(', ')}
                    onPress={() => router.push({ pathname: '/product/[id]', params: { id: p.productId, offer: p.offerId } })}
                  />
                ))}
              </Strip>
            </View>
          ) : null}
        </>
      )}

      <View style={styles.pad}>
        <Card sunken style={styles.sell}>
          <Text style={styles.sellTitle}>{t('lt.home.sellTitle')}</Text>
          <Body tone="small">{t('lt.home.sellSub')}</Body>
        </Card>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, minWidth: 0 },
  pad: { paddingHorizontal: space[4], gap: space[4] },
  mark: { width: 36, height: MIN_TARGET, alignItems: 'center', justifyContent: 'center' },
  avatar: { width: MIN_TARGET, height: MIN_TARGET, borderRadius: radius.round, backgroundColor: semantic['action-primary'], alignItems: 'center', justifyContent: 'center' },
  avatarGuest: { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.borderStrong },
  initials: { fontFamily: fonts.bold, fontSize: 15, color: semantic['action-primary-text'] },
  active: { flexDirection: 'row', alignItems: 'center', gap: space[3], backgroundColor: semantic['surface-brand'], borderColor: semantic['focus-ring'] },
  activeIcon: { width: MIN_TARGET, height: MIN_TARGET, borderRadius: radius.round, backgroundColor: colors.surface, alignItems: 'center', justifyContent: 'center' },
  activeKicker: { fontFamily: fonts.bold, fontSize: 12, letterSpacing: 0.4, textTransform: 'uppercase', color: semantic['badge-brand-text'] },
  activeTitle: { fontFamily: fonts.semibold, fontSize: 16, lineHeight: 21, color: colors.text },
  activeSub: { fontFamily: fonts.regular, fontSize: 13, color: colors.textMuted },
  hero: { gap: space[3], paddingTop: space[2] },
  heroTitle: { fontFamily: fonts.display, fontSize: 32, lineHeight: 37, letterSpacing: -0.4, color: colors.text },
  collage: { flexDirection: 'row', gap: space[2], height: 196, marginTop: space[2] },
  collageMain: { flex: 1.35, borderRadius: radius.lg },
  collageSide: { flex: 1, gap: space[2] },
  collageSmall: { flex: 1, borderRadius: radius.lg },
  badge: {
    position: 'absolute',
    left: space[3],
    bottom: space[3],
    maxWidth: '86%',
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: space[3],
    paddingVertical: 6,
    borderRadius: radius.control,
    backgroundColor: colors.surface,
    ...shadow.md,
  },
  badgeText: { flexShrink: 1, fontFamily: fonts.semibold, fontSize: 13, color: colors.text },
  section: { gap: space[2], paddingTop: space[3] },
  loading: { gap: space[3], paddingTop: space[3] },
  run: { flexDirection: 'row', alignItems: 'center', gap: space[3], marginTop: space[5], padding: 0, overflow: 'hidden' },
  runText: { flex: 1, gap: 4, padding: space[4] },
  runKicker: { fontFamily: fonts.bold, fontSize: 12, letterSpacing: 0.4, textTransform: 'uppercase', color: semantic['badge-accent-text'] },
  runTitle: { fontFamily: fonts.display, fontSize: 22, lineHeight: 27, color: colors.text },
  runSub: { fontFamily: fonts.regular, fontSize: 13, lineHeight: 18, color: colors.textMuted },
  runArt: { width: 116, alignSelf: 'stretch' },
  sell: { marginTop: space[5] },
  sellTitle: { fontFamily: fonts.display, fontSize: 18, lineHeight: 24, color: colors.text },
});
