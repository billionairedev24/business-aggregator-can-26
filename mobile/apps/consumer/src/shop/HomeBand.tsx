import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { StyleSheet } from 'react-native';

import { HomeHero, Photo, cuisineSlot, slugSlot, type HomeHeroRun } from '@northline/mobile-kit';

import { servicesApi } from '../api/services';
import type { Landing } from '../api/shop';
import type { MessageKey } from '../i18n';
import { services } from '../services';
import { useMinutesLeft, useShopFormat } from './common';

/**
 * The home's "tonight" hero band (S-188 brand layer, design/revamp/hybrid "Consumer home · phone (390)"; the web's
 * `HomeBand`): "Everything <place> needs, tonight." — the place is the market's city (a message parameter, never
 * written in; "Everything you need, tonight." until it is known) — the trust line, the run card (it replaces the
 * cut-off strip on the home: the next pooled run, its closing time and the time left on the yellow sticker, counting
 * down from the first render and then every minute), then the three department photo tiles (Services, Shop, and Food
 * as the group order: the app has no kitchen list).
 */
export function HomeBand({ place, landing }: { place: string | undefined; landing: Landing | null | undefined }) {
  const f = useShopFormat();
  const { t, locale } = f;
  const api = servicesApi(services().api);
  // the same query as "Popular services" below: its count of verified providers
  const pros = useQuery({ queryKey: ['services', 'landing', locale], queryFn: () => api.landing(locale === 'fr-CA' ? 'fr' : 'en'), staleTime: 60_000 }).data?.providers;

  const r = landing?.served ? landing.run : null;
  const left = useMinutesLeft(r?.orderBy);
  let run: HomeHeroRun | undefined;
  if (landing === undefined) run = undefined;
  else if (r && left !== null && left > 0) {
    const when = f.which(r);
    run = {
      kicker: when === 'later' ? t('band.run.later', { weekday: f.weekday(r.startsAt ?? r.orderBy) }) : t(`band.run.${when}` as MessageKey),
      title: t('band.runCloses', { time: f.time(r.orderBy) }),
      left: left >= 60 ? t('band.leftHours', { h: Math.floor(left / 60), m: left % 60 }) : t('band.leftMinutes', { m: left }),
      note: t('band.runNote', { fee: f.fee(r.feeCents) }),
    };
  } else run = { kicker: t('band.runNoneKicker'), title: t('band.runNoneTitle'), note: t('band.runNoneNote') };

  const shops = landing?.served ? landing.shopCount : 0;
  return (
    <HomeHero
      title={place ? t('band.title', { place }) : t('band.titleAnywhere')}
      accent={t('band.accent')}
      trust={[
        { key: 'held', icon: 'shield-check', label: t('band.trustHeld') },
        { key: 'refund', icon: 'arrows-clockwise', label: t('band.trustRefund') },
      ]}
      run={run}
      runLabel={t('band.runLabel')}
      loadingLabel={t('band.runLoading')}
      departmentsLabel={t('band.depts')}
      departments={[
        {
          key: 'services',
          icon: 'wrench',
          kicker: t('band.servicesKicker'),
          title: t('band.services'),
          meta: pros ? t('band.servicesMeta', { count: pros }) : t('band.servicesMetaAny'),
          photo: <Photo slot={[slugSlot('service', 'barber-and-hair'), 'banner/services']} crop="wide" style={StyleSheet.absoluteFill} />,
          onPress: () => router.navigate('/services'),
          testID: 'band-services',
        },
        {
          key: 'shop',
          icon: 'shopping-bag',
          kicker: t('band.shopKicker'),
          title: t('band.shop'),
          meta: shops > 0 ? t('band.shopMeta', { count: shops }) : t('band.shopMetaAny'),
          photo: <Photo slot={[slugSlot('shop', 'produce'), 'banner/shop']} crop="wide" style={StyleSheet.absoluteFill} />,
          onPress: () => router.push({ pathname: '/search', params: { kind: 'product' } }),
          testID: 'band-shop',
        },
        {
          key: 'food',
          icon: 'bowl-food',
          kicker: t('band.foodKicker'),
          title: t('band.food'),
          meta: t('band.foodMeta'),
          photo: <Photo slot={[cuisineSlot('vietnamese'), 'banner/food']} crop="wide" style={StyleSheet.absoluteFill} />,
          onPress: () => router.push('/group'),
          testID: 'band-food',
        },
      ]}
      testID="home-band"
    />
  );
}
