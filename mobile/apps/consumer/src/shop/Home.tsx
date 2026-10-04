import { useQuery } from '@tanstack/react-query';
import { router, type Href } from 'expo-router';
import { Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';

import { ApiError, MIN_TARGET, colors, fonts, radius, space } from '@northline/mobile-kit';

import type { MessageKey } from '../i18n';
import { useAuth } from '../auth/AuthProvider';
import { Body, Tag } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { ErrorState, Skeleton } from '../ui/states';
import { appRoute, shop, useMarket, useShopFormat } from './common';
import { Panel, Thumb, shopStyles } from './parts';

/** Design 01's service tiles (`svcCats`) and the S-100 category page each opens. */
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
 * B1 Home (design 01 `home`): where you are (tap to change), the greeting, search, tonight's pooled run as the hero
 * (`GET /public/shop` → `run`: why delivery is cheap), the Shop's departments, the service tiles, "Trusted near you"
 * (`GET /public/home` — the design's "Because you booked …" has no api: the consumer web's list stands in) and, signed
 * in, "Your week" (`GET /me/upcoming`). Plus members see the run free (`GET /me/account-summary`).
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
  const city = market.city ?? landing.data?.market;
  const home = useQuery({ queryKey: ['shop', 'home', city?.toLowerCase() ?? ''], queryFn: () => shop().home(city!), enabled: !!city, staleTime: 60_000 });
  const me = useQuery({ queryKey: ['me'], queryFn: () => shop().me(), enabled: signedIn, staleTime: 300_000 });
  const week = useQuery({ queryKey: ['shop', 'upcoming'], queryFn: () => nullOn404(() => shop().upcoming()), enabled: signedIn, staleTime: 60_000 });
  const summary = useQuery({ queryKey: ['account', 'summary'], queryFn: () => nullOn404(() => shop().accountSummary()), enabled: signedIn, staleTime: 60_000 });

  const hour = (() => {
    try {
      return Number(new Intl.DateTimeFormat('en-CA', { hour: 'numeric', hourCycle: 'h23', ...(f.zone ? { timeZone: f.zone } : {}) }).format(new Date()));
    } catch {
      return new Date().getHours();
    }
  })();
  const greeting = t(hour < 12 ? 'shop.home.morning' : hour < 17 ? 'shop.home.afternoon' : 'shop.home.evening');
  const name = me.data?.firstName;
  const run = landing.data?.run;
  const plus = !!summary.data?.plus;
  const refresh = () => void Promise.all([landing.refetch(), home.refetch(), signedIn ? week.refetch() : null]);
  const place = market.label ?? landing.data?.market;

  return (
    <Screen testID="home" refreshControl={<RefreshControl refreshing={landing.isRefetching} onRefresh={refresh} />}>
      <View style={styles.top}>
        <View style={shopStyles.rowText}>
          <Pressable
            accessibilityRole="button"
            // S-109: a name without the ▾ glyph (read out as "down-pointing triangle"); the 32 pt kicker gets a 48 pt
            // touch area through hitSlop
            accessibilityLabel={place ? t('shop.home.placeLabel', { place }) : t('shop.home.setAddressLabel')}
            accessibilityHint={t('shop.home.placeHint')}
            hitSlop={{ top: 8, bottom: 8 }}
            onPress={() => router.push('/location?next=/home')}
            style={styles.place}
            testID="home-place"
          >
            <Text style={styles.placeText}>{place ? t('shop.home.place', { place }) : t('shop.home.setAddress')}</Text>
          </Pressable>
          <Text accessibilityRole="header" style={styles.greeting}>
            {name ? t('shop.home.greetingNamed', { greeting, name }) : greeting}
          </Text>
        </View>
        <Pressable accessibilityRole="button" accessibilityLabel={t('shop.home.notifications')} onPress={() => router.push('/notifications')} style={styles.bell}>
          <Text style={styles.bellText}>🔔</Text>
        </Pressable>
      </View>

      <Pressable accessibilityRole="search" accessibilityLabel={t('shop.home.search')} onPress={() => router.push('/search')} style={styles.search} testID="home-search">
        <Text style={styles.searchText} numberOfLines={1}>
          {t('shop.home.search')}
        </Text>
      </Pressable>

      {landing.isPending ? (
        <View testID="loading" accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.loading}>
          <Skeleton height={62} />
          <Skeleton height={24} width="40%" />
          <Skeleton height={140} />
        </View>
      ) : landing.isError && !landing.data ? (
        <ErrorState error={landing.error} onRetry={() => void landing.refetch()} />
      ) : (
        <>
          {landing.isError ? <ErrorState error={landing.error} onRetry={() => void landing.refetch()} /> : null}
          {run ? (
            <Panel tone="accent" style={styles.run} testID="home-run">
              <View style={shopStyles.rowText}>
                <Text style={styles.runKicker}>{t(run.day === 'today' ? 'shop.home.runTonight' : 'shop.home.runNext')}</Text>
                <Text style={styles.runLine}>
                  {t('shop.home.runLine', { time: f.time(run.startsAt), fee: plus ? t('shop.home.freeWithPlus') : f.fee(run.feeCents), orderBy: f.time(run.orderBy) })}
                </Text>
              </View>
              {run.households > 0 ? <Text style={styles.runSide}>{t('shop.home.neighbours', { n: run.households })}</Text> : null}
            </Panel>
          ) : null}
          {landing.data && !landing.data.served ? <Body tone="muted">{t('shop.home.notServed', { place: landing.data.market })}</Body> : null}

          <View style={shopStyles.head}>
            <Text accessibilityRole="header" style={styles.h3}>
              {t('shop.home.shop')}
            </Text>
            <Pressable accessibilityRole="link" onPress={() => router.push('/search')} style={styles.link}>
              <Text style={styles.linkText}>{t('shop.home.allShops')}</Text>
            </Pressable>
          </View>
          {landing.data && landing.data.departments.length === 0 ? (
            <Body tone="muted">{t('shop.home.noShops')}</Body>
          ) : (
            <View style={styles.grid}>
              {(landing.data?.departments ?? []).slice(0, 7).map((d) => (
                <Tile key={d.slug} label={d.name} onPress={() => router.push({ pathname: '/search', params: { department: d.slug, name: d.name } })} />
              ))}
              <Tile label={t('shop.home.more')} onPress={() => router.push('/search')} />
            </View>
          )}
        </>
      )}

      <View style={shopStyles.head}>
        <Text accessibilityRole="header" style={styles.h3}>
          {t('shop.home.services')}
        </Text>
        <Pressable accessibilityRole="link" onPress={() => router.push('/services')} style={styles.link}>
          <Text style={styles.linkText}>{t('shop.home.allServices')}</Text>
        </Pressable>
      </View>
      <View style={styles.grid}>
        {SERVICE_TILES.map(([key, slug]) => (
          <Tile key={slug} label={t(key)} onPress={() => router.push(`/services/${slug}` as Href)} />
        ))}
        <Tile label={t('shop.home.more')} onPress={() => router.push('/services')} />
      </View>

      {signedIn && week.data?.items.length ? (
        <View style={shopStyles.section} testID="home-week">
          <Text accessibilityRole="header" style={styles.h3}>
            {t('shop.home.week')}
          </Text>
          {week.data.items.map((w) => {
            const to = appRoute(w.href);
            return (
              <Pressable key={w.id} accessibilityRole={to ? 'button' : undefined} disabled={!to} onPress={() => to && router.push(to as Href)} style={shopStyles.row}>
                <View style={shopStyles.rowText}>
                  <Text style={[styles.body, shopStyles.strong]}>{w.title}</Text>
                  {w.subtitle ? <Text style={styles.small}>{w.subtitle}</Text> : null}
                </View>
                <Tag label={w.state} tone={w.tone === 'accent-2' ? 'accent2' : w.tone} small />
              </Pressable>
            );
          })}
        </View>
      ) : null}

      {home.data?.trusted.length ? (
        <View style={shopStyles.section} testID="home-trusted">
          <Text accessibilityRole="header" style={styles.h3}>
            {t('shop.home.trusted')}
          </Text>
          {home.data.trusted.slice(0, 4).map((p) => (
            <Pressable
              key={p.merchantId}
              accessibilityRole="button"
              accessibilityLabel={[p.name, f.tier(p.tier), p.category?.name, t('shop.ratingSpoken', { rating: p.rating.toFixed(1) })].filter(Boolean).join(', ')}
              disabled={!p.slug}
              onPress={() => p.slug && router.push(`/providers/${p.slug}` as Href)}
              style={shopStyles.row}
            >
              <Thumb size={52} />
              <View style={shopStyles.rowText}>
                <View style={styles.nameRow}>
                  <Text style={[styles.body, shopStyles.strong]}>{p.name}</Text>
                  <Tag label={f.tier(p.tier)} tone="accent" small />
                </View>
                {p.category ? <Text style={styles.small}>{p.category.name}</Text> : null}
              </View>
              <Text style={[styles.small, shopStyles.strong]}>{t('shop.rating', { rating: p.rating.toFixed(1) })}</Text>
            </Pressable>
          ))}
        </View>
      ) : null}
    </Screen>
  );
}

function Tile({ label, onPress }: { label: string; onPress: () => void }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} onPress={onPress} style={({ pressed }) => [styles.tile, pressed && styles.tilePressed]}>
      <Text style={styles.tileText} numberOfLines={2}>
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  top: { flexDirection: 'row', alignItems: 'center', gap: space[3] },
  place: { minHeight: 32, justifyContent: 'center', alignSelf: 'flex-start' },
  placeText: { fontFamily: fonts.body, fontSize: 12, letterSpacing: 1, textTransform: 'uppercase', color: colors.neutral700 },
  greeting: { fontFamily: fonts.heading, fontSize: 26, lineHeight: 30, letterSpacing: -0.5, color: colors.text },
  bell: { width: MIN_TARGET, height: MIN_TARGET, borderRadius: radius.md, backgroundColor: colors.surface, alignItems: 'center', justifyContent: 'center' },
  bellText: { fontSize: 15 },
  search: { minHeight: MIN_TARGET, justifyContent: 'center', paddingHorizontal: 14, borderRadius: radius.md, borderWidth: 1, borderColor: colors.neutral300, backgroundColor: colors.surface },
  searchText: { fontFamily: fonts.body, fontSize: 15, color: colors.neutral700 },
  loading: { gap: space[3] },
  run: { flexDirection: 'row', alignItems: 'center', gap: space[3] },
  runKicker: { fontFamily: fonts.body, fontSize: 11, letterSpacing: 1, textTransform: 'uppercase', color: colors.accent800 },
  runLine: { fontFamily: fonts.bodyStrong, fontSize: 15, color: colors.accent900 },
  runSide: { fontFamily: fonts.body, fontSize: 13, color: colors.accent800 },
  h3: { fontFamily: fonts.heading, fontSize: 20, lineHeight: 24, color: colors.text, paddingTop: space[3] },
  link: { minHeight: MIN_TARGET, justifyContent: 'flex-end', paddingBottom: 2 },
  linkText: { fontFamily: fonts.body, fontSize: 13, color: colors.accent700 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  tile: { width: '23%', flexGrow: 1, minHeight: 64, padding: 6, borderRadius: radius.md, backgroundColor: colors.surface, alignItems: 'center', justifyContent: 'center' },
  tilePressed: { backgroundColor: colors.accent100 },
  tileText: { fontFamily: fonts.body, fontSize: 12, lineHeight: 15, textAlign: 'center', color: colors.text },
  nameRow: { flexDirection: 'row', alignItems: 'center', gap: 8, flexWrap: 'wrap' },
  body: { fontFamily: fonts.body, fontSize: 15, color: colors.text },
  small: { fontFamily: fonts.body, fontSize: 13, color: colors.neutral700 },
});
