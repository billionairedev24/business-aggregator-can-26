import { ArrowsClockwise, BowlFood, ShieldCheck, ShoppingBag, Storefront, Truck, Wrench } from '@phosphor-icons/react';
import { HomeHero, useFormatters, useLocale, type HomeHeroRun, type HomeHeroTrust } from '@northline/ui';
import { cuisineSlot, departmentSlot, serviceSlot } from '@northline/media';
import { Scene } from '../art/Scene';
import { useZone } from '../location/regions';
import { useRenderNow } from '../../lib/renderClock';
import type { Landing } from '../shop/api';
import { clock, runWhen, weekday } from '../shop/format';
import { minutesUntil, useNow } from '../shop/run';
import { useHomeT } from './messages';

const PICTURE_SIZES = '(min-width: 768px) 32vw, 100vw';

/**
 * The home's "tonight" hero band (S-188 brand layer, design/revamp/hybrid/index.html): "Everything <place> needs,
 * tonight." — the place is the visitor's neighbourhood or city (a message parameter, never written in; "Everything you
 * need, tonight." until it is known) — the trust line, the run card (it replaces the cut-off strip on the home: the next
 * pooled run, its closing time and the time left on the yellow sticker), then the three department photo tiles. The
 * countdown starts from the render clock (lib/renderClock: the server's render and the browser's first render agree)
 * and then ticks in the browser.
 */
export function HomeBand({ place, landing, services, kitchens }: { place: string | undefined; landing: Landing | null | undefined; services: number | undefined; kitchens: number | undefined }) {
  const t = useHomeT();
  const { locale } = useLocale();
  const { money } = useFormatters();
  const zone = useZone(landing?.market);
  const rendered = useRenderNow().getTime();
  const ticking = useNow();
  const now = ticking ?? rendered;

  const trust: HomeHeroTrust[] = [
    { key: 'held', icon: <ShieldCheck size={18} />, label: t('trustHeld') },
    { key: 'refund', icon: <ArrowsClockwise size={18} />, label: t('trustRefund') },
  ];
  if (landing?.served && landing.shopCount > 0) {
    trust.push({ key: 'shops', icon: <Storefront size={18} />, label: t('trustShops', { count: landing.shopCount, area: landing.market }), wide: true });
  }

  const r = landing?.served ? landing.run : null;
  const left = r ? minutesUntil(r.orderBy, now) : 0;
  let run: HomeHeroRun | null | undefined;
  if (landing === undefined) run = undefined;
  else if (r && left > 0) {
    const when = runWhen(r, zone);
    run = {
      icon: <Truck size={16} />,
      kicker: t('runKicker', { when, weekday: weekday(r.startsAt, locale, zone) }),
      title: t('runCloses', { time: clock(r.orderBy, locale, zone) }),
      left: t('runLeft', { hours: Math.floor(left / 60), minutes: left % 60 }),
      note: t('runNote', { fee: money(r.feeCents) }),
    };
  } else run = { icon: <Truck size={16} />, kicker: t('runNoneKicker'), title: t('runNoneTitle'), note: t('runNoneNote') };

  const count = (n: number | undefined) => !!n && n > 0;
  const shops = landing?.served ? landing.shopCount : undefined;
  return (
    <HomeHero
      title={place ? t('bandTitle', { place }) : t('bandTitleAnywhere')} accent={t('bandAccent')}
      trust={trust} run={run} runLabel={t('runLabel')} loadingLabel={t('runLoading')} departmentsLabel={t('deptsLabel')}
      departments={[
        {
          key: 'services', href: '/services', icon: <Wrench size={15} />, kicker: t('deptServicesKicker'), title: t('deptServices'),
          meta: count(services) ? t('deptServicesMeta', { count: services! }) : t('deptServicesMetaAny'),
          picture: <Scene slot={[serviceSlot('barber-and-hair'), 'banner/services']} subject="barber" crop="wide" sizes={PICTURE_SIZES} priority />,
        },
        {
          key: 'shop', href: '/shop', icon: <ShoppingBag size={15} />, kicker: t('deptShopKicker'), title: t('deptShop'),
          meta: count(shops) ? t('deptShopMeta', { count: shops! }) : t('deptShopMetaAny'),
          picture: <Scene slot={[departmentSlot('produce'), 'banner/shop']} subject="produce" crop="wide" sizes={PICTURE_SIZES} priority />,
        },
        {
          key: 'food', href: '/food', icon: <BowlFood size={15} />, kicker: t('deptFoodKicker'), title: t('deptFood'),
          meta: count(kitchens) ? t('deptFoodMeta', { count: kitchens! }) : t('deptFoodMetaAny'),
          picture: <Scene slot={[cuisineSlot('vietnamese'), 'banner/food']} subject="vietnamese" crop="wide" sizes={PICTURE_SIZES} priority />,
        },
      ]}
    />
  );
}
