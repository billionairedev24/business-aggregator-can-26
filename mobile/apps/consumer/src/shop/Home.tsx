import { useQuery } from '@tanstack/react-query';
import { router, type Href } from 'expo-router';
import { Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';

import { ApiError, Icon, Logo, MIN_TARGET, colors, fonts, radius, semantic, space, type IconName } from '@northline/mobile-kit';

import type { MessageKey } from '../i18n';
import { useAuth } from '../auth/AuthProvider';
import { Body } from '../ui/primitives';
import { RoundButton, Screen } from '../ui/screen';
import { ErrorState, Skeleton } from '../ui/states';
import { appRoute, shop, useMarket, useShopFormat } from './common';
import { Circle, ProductTile, SectionHead, Strip, departmentIcon } from './parts';

/** The service categories Home offers as circles (category slugs of the S-100 pages), with their icons. */
const SERVICE_TILES: ReadonlyArray<[MessageKey, string, IconName]> = [
  ['shop.svc.mechanic', 'mobile-mechanic', 'wrench'],
  ['shop.svc.cleaning', 'house-cleaning', 'broom'],
  ['shop.svc.plumber', 'plumber', 'drop'],
  ['shop.svc.bar', 'cocktail-and-mocktail-bar', 'martini'],
  ['shop.svc.realtor', 'real-estate-agent', 'house-line'],
  ['shop.svc.barber', 'barber-and-hair', 'scissors'],
  ['shop.svc.tutor', 'tutor-k-12', 'graduation-cap'],
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
 * Home (redesign spec § 2.4 → MA:180 HOME): the logo with the inbox and account buttons, "Deliver to {place}", the
 * search pill, the order or booking under way as a dark banner (`GET /me/upcoming`'s first item that has a screen),
 * the Shop's departments as circles, the pooled run as the hero card (`GET /public/shop` → `run`), "Popular near you"
 * (the landing's products) and the services as circles beside a "Book a pro" card. The time-of-day greeting, the
 * "Trusted near you" tier rows, "Your week" and the Plus price are gone (thrash list items 2 and 13).
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

  return (
    <Screen testID="home" refreshControl={<RefreshControl refreshing={landing.isRefetching} onRefresh={refresh} />}>
      <View style={styles.top}>
        <Logo size={30} style={styles.flex} />
        <RoundButton icon="bell" label={t('shop.home.notifications')} onPress={() => router.push('/notifications')} testID="home-inbox" />
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={signedIn ? t('rd.home.account') : t('rd.home.signIn')}
          onPress={() => router.navigate('/account')}
          style={[styles.avatar, !initials && styles.avatarGuest]}
          testID="home-account"
        >
          {initials ? <Text style={styles.initials}>{initials}</Text> : <Icon name="user" size={22} />}
        </Pressable>
      </View>

      <Pressable
        accessibilityRole="button"
        // S-109: a name without glyphs; the 13 pt line gets a 44 pt touch area through hitSlop
        accessibilityLabel={place ? t('shop.home.placeLabel', { place }) : t('shop.home.setAddressLabel')}
        accessibilityHint={t('shop.home.placeHint')}
        hitSlop={{ top: 12, bottom: 12 }}
        onPress={() => router.push('/location?next=/home')}
        style={styles.place}
        testID="home-place"
      >
        <Icon name="map-pin" size={16} />
        <Text style={styles.placeText} numberOfLines={1}>
          {place ? (
            <>
              <Text style={styles.muted}>{t('rd.home.deliverTo')} </Text>
              <Text style={styles.strong}>{place}</Text>
            </>
          ) : (
            <Text style={styles.strong}>{t('shop.home.setAddressLabel')}</Text>
          )}
        </Text>
        <Icon name="caret-down" size={14} />
      </Pressable>

      <Pressable accessibilityRole="search" accessibilityLabel={t('rd.search.placeholder')} onPress={() => router.push('/search')} style={styles.search} testID="home-search">
        <Icon name="magnifying-glass" size={20} />
        <Text style={styles.searchText} numberOfLines={1}>
          {t('rd.search.placeholder')}
        </Text>
      </Pressable>

      {active?.to ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={[active.w.title, active.w.subtitle, active.w.state].filter(Boolean).join(', ')}
          onPress={() => router.push(active.to as Href)}
          style={styles.banner}
          testID="home-active"
        >
          <Icon name={/booking|provider/.test(active.to) ? 'calendar-check' : 'truck'} size={26} color={semantic['action-accent']} />
          <View style={styles.flex}>
            <Text style={styles.bannerTitle} numberOfLines={2}>
              {active.w.title}
            </Text>
            <Text style={styles.bannerSub} numberOfLines={1}>
              {[active.w.subtitle, active.w.state].filter(Boolean).join(' · ')}
            </Text>
          </View>
          <Icon name="caret-right" size={18} color={colors.headerText} />
        </Pressable>
      ) : null}

      {landing.isPending ? (
        <View testID="loading" accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.loading}>
          <Skeleton height={72} />
          <Skeleton height={180} />
          <Skeleton height={24} width="40%" />
        </View>
      ) : landing.isError && !landing.data ? (
        <ErrorState error={landing.error} onRetry={() => void landing.refetch()} />
      ) : (
        <>
          {landing.isError ? <ErrorState error={landing.error} onRetry={() => void landing.refetch()} /> : null}
          {landing.data && landing.data.departments.length > 0 ? (
            <Strip testID="home-departments">
              {landing.data.departments.slice(0, 8).map((d) => (
                <Circle key={d.slug} label={d.name} icon={departmentIcon(d.slug)} onPress={() => router.push({ pathname: '/search', params: { department: d.slug, name: d.name } })} />
              ))}
              <Circle label={t('shop.home.services')} icon="calendar-check" onPress={() => router.navigate('/services')} />
            </Strip>
          ) : null}

          <View style={styles.hero} testID="home-run" accessible accessibilityRole="summary">
            <View style={styles.heroArt}>
              <Icon name={run ? 'truck' : 'storefront'} size={64} color={semantic['action-accent']} />
            </View>
            <View style={styles.heroText}>
              <View style={styles.pill}>
                <Text style={styles.pillText}>{run ? t(run.day === 'today' ? 'shop.home.runTonight' : 'shop.home.runNext') : t('rd.home.heroKicker')}</Text>
              </View>
              <Text style={styles.heroTitle}>{run ? t('rd.home.runTitle', { time: f.time(run.startsAt) }) : t('rd.home.heroTitle', { place: place ?? '' })}</Text>
              <Text style={styles.heroSub}>
                {run
                  ? [t('rd.home.runSub', { fee: f.fee(run.feeCents), orderBy: f.time(run.orderBy) }), run.households > 0 ? t('shop.home.neighbours', { n: run.households }) : null].filter(Boolean).join(' · ')
                  : t('rd.home.heroSub')}
              </Text>
            </View>
          </View>
          {landing.data && !landing.data.served ? <Body tone="muted">{t('shop.home.notServed', { place: landing.data.market })}</Body> : null}
          {landing.data && landing.data.departments.length === 0 ? <Body tone="muted">{t('shop.home.noShops')}</Body> : null}

          {landing.data?.popular.length ? (
            <View style={styles.section} testID="home-popular">
              <SectionHead title={t('rd.home.popular')} link={t('rd.common.seeAll')} onLink={() => router.push('/search')} />
              <Strip>
                {landing.data.popular.slice(0, 10).map((p) => (
                  <ProductTile
                    key={p.offerId}
                    name={p.name}
                    price={f.money(p.priceCents)}
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

      <View style={styles.section} testID="home-services">
        <SectionHead title={t('rd.home.services')} link={t('rd.common.seeAll')} onLink={() => router.navigate('/services')} />
        <Strip>
          {SERVICE_TILES.map(([key, slug, icon]) => (
            <Circle key={slug} label={t(key)} icon={icon} onPress={() => router.push(`/services/${slug}` as Href)} />
          ))}
        </Strip>
        <Pressable accessibilityRole="button" accessibilityLabel={`${t('rd.home.bookTitle')}, ${t('rd.home.bookSub')}`} onPress={() => router.navigate('/services')} style={styles.card}>
          <Icon name="calendar-check" size={28} />
          <View style={styles.flex}>
            <Text style={styles.cardTitle}>{t('rd.home.bookTitle')}</Text>
            <Text style={styles.cardSub}>{t('rd.home.bookSub')}</Text>
          </View>
          <Icon name="caret-right" size={18} />
        </Pressable>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, minWidth: 0 },
  top: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  avatar: { width: MIN_TARGET, height: MIN_TARGET, borderRadius: MIN_TARGET / 2, backgroundColor: semantic['action-accent'], alignItems: 'center', justifyContent: 'center' },
  avatarGuest: { backgroundColor: semantic['surface-muted'] },
  initials: { fontFamily: fonts.bold, fontSize: 15, color: semantic['action-accent-text'] },
  place: { flexDirection: 'row', alignItems: 'center', gap: 6, minHeight: 20, alignSelf: 'flex-start', maxWidth: '100%' },
  placeText: { flexShrink: 1, fontFamily: fonts.regular, fontSize: 13, color: colors.text },
  muted: { color: colors.textMuted },
  strong: { fontFamily: fonts.bold, color: colors.text },
  search: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space[2],
    minHeight: 48,
    paddingHorizontal: space[4],
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.borderControl,
    backgroundColor: colors.surface,
  },
  searchText: { flex: 1, fontFamily: fonts.regular, fontSize: 15, color: colors.textMuted },
  banner: { flexDirection: 'row', alignItems: 'center', gap: space[3], padding: 14, minHeight: MIN_TARGET, borderRadius: radius.md, backgroundColor: colors.headerBg },
  bannerTitle: { fontFamily: fonts.bold, fontSize: 14, color: colors.headerText },
  bannerSub: { fontFamily: fonts.regular, fontSize: 12, color: semantic['header-muted'] },
  loading: { gap: space[3] },
  hero: { minHeight: 180, borderRadius: radius.lg, overflow: 'hidden', backgroundColor: colors.headerBg, flexDirection: 'row' },
  heroText: { flex: 1, padding: space[5], gap: space[2], justifyContent: 'center' },
  heroArt: { position: 'absolute', right: 0, top: 0, bottom: 0, width: '38%', alignItems: 'center', justifyContent: 'center', backgroundColor: semantic['action-primary'] },
  pill: { alignSelf: 'flex-start', paddingHorizontal: 8, paddingVertical: 3, borderRadius: radius.pill, backgroundColor: semantic['action-accent'] },
  pillText: { fontFamily: fonts.bold, fontSize: 10, letterSpacing: 0.6, textTransform: 'uppercase', color: semantic['action-accent-text'] },
  heroTitle: { maxWidth: '62%', fontFamily: fonts.black, fontSize: 22, lineHeight: 25, color: colors.headerText },
  heroSub: { maxWidth: '62%', fontFamily: fonts.regular, fontSize: 13, lineHeight: 18, color: semantic['header-muted'] },
  section: { gap: space[3] },
  card: { flexDirection: 'row', alignItems: 'center', gap: space[3], padding: space[4], minHeight: MIN_TARGET, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surface },
  cardTitle: { fontFamily: fonts.bold, fontSize: 15, color: colors.text },
  cardSub: { fontFamily: fonts.regular, fontSize: 13, color: colors.textMuted },
});
