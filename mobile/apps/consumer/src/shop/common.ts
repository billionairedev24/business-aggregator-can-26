import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef } from 'react';

import { ApiError, type Locale } from '@northline/mobile-kit';

import { geoApi } from '../api/geo';
import { EMPTY_CART, shopApi, type Cart, type Day } from '../api/shop';
import { useAuth } from '../auth/AuthProvider';
import { useI18n, type MessageKey } from '../i18n';
import { useDeliveryLocation } from '../location/DeliveryLocation';
import { services } from '../services';

/** Journey B's api on the app's client. */
export const shop = () => shopApi(services().api);

/** `Intl` money in the person's language: 7,50 $ / $7.50 (CAD, `*_cents / 100`). */
export function formatMoney(cents: number, locale: Locale): string {
  return new Intl.NumberFormat(locale === 'fr-CA' ? 'fr-CA' : 'en-CA', { style: 'currency', currency: 'CAD' }).format(cents / 100);
}

/** The hour (0–23) of an instant in a time zone (for "tonight"). */
function hourIn(iso: string, timeZone?: string): number {
  try {
    return Number(new Intl.DateTimeFormat('en-CA', { hour: 'numeric', hourCycle: 'h23', ...(timeZone ? { timeZone } : {}) }).format(new Date(iso)));
  } catch {
    return new Date(iso).getHours();
  }
}

/**
 * Where the person shops (the delivery location, S-98) and its market's time zone from the region model (never a zone
 * in code): `city` is the consumer web's `market` (a city, for the Shop's pages and checkout), `province` the search
 * api's `market`.
 */
export function useMarket() {
  const { location } = useDeliveryLocation();
  const { locale } = useI18n();
  const regions = useQuery({
    queryKey: ['geo', 'regions', locale],
    queryFn: () => geoApi(services().api).regions(locale === 'fr-CA' ? 'fr' : 'en'),
    staleTime: 3_600_000,
  });
  const ready = location.status !== 'locating';
  const city = ready ? location.city : undefined;
  const zone =
    regions.data?.markets.find((m) => !!city && m.city.toLowerCase() === city.toLowerCase())?.timeZone ?? regions.data?.platformTimeZone ?? undefined;
  const province = location.province && /^[A-Z]{2}$/.test(location.province) ? location.province : undefined;
  return { ready, city, province, zone, location, label: location.label ?? city };
}

/** Money, times and the design's delivery-window words, in the person's language and the market's zone. */
export function useShopFormat() {
  const i18n = useI18n();
  const { zone } = useMarket();
  return useMemo(() => {
    const { t, locale } = i18n;
    const money = (cents: number) => formatMoney(cents, locale);
    const time = (iso?: string | null) => i18n.time(iso, zone);
    const date = (iso?: string | null) => i18n.day(iso, zone);
    const range = (start?: string | null, end?: string | null) => (start && end ? `${time(start)}–${time(end)}` : time(start));
    const weekday = (iso: string) => {
      try {
        return new Intl.DateTimeFormat(locale === 'fr-CA' ? 'fr-CA' : 'en-CA', { weekday: 'long', ...(zone ? { timeZone: zone } : {}) }).format(new Date(iso));
      } catch {
        return date(iso);
      }
    };
    /** "Tonight 6:00 p.m.–9:00 p.m." (`prefix` win = a choice's name, when = inside a sentence). */
    const window = (w: { day?: Day | null; startsAt?: string | null; endsAt?: string | null }, prefix: 'shop.win' | 'shop.when') => {
      if (!w.startsAt) return t('shop.when.soon');
      const day = w.day ?? 'later';
      const which = day === 'today' ? (hourIn(w.startsAt, zone) >= 17 ? 'tonight' : 'today') : day;
      return t(`${prefix}.${which}` as MessageKey, { range: range(w.startsAt, w.endsAt), weekday: weekday(w.startsAt) });
    };
    const fee = (cents: number) => (cents === 0 ? t('shop.free') : money(cents));
    const tier = (code?: string | null) => {
      const k = `shop.tier.${(code ?? '').toLowerCase()}` as MessageKey;
      const s = t(k);
      return s === k ? (code ?? '') : s;
    };
    const tax = (type: string, percent: number) => {
      const p = new Intl.NumberFormat(locale === 'fr-CA' ? 'fr-CA' : 'en-CA', { maximumFractionDigits: 3 }).format(percent);
      return ['gst', 'hst', 'pst', 'qst', 'rst'].includes(type) ? t(`shop.tax.${type}` as MessageKey, { percent: p }) : t('shop.tax.other', { type: type.toUpperCase(), percent: p });
    };
    return { t, locale, zone, money, time, date, range, window, fee, tier, tax };
  }, [i18n, zone]);
}

/** The api's English rule messages in the person's language (the api words them in English only). */
const SERVER_MESSAGES: Record<string, MessageKey> = {
  'Enter the street address.': 'shop.srv.street',
  'Enter the city.': 'shop.srv.city',
  'Choose a Canadian province or territory.': 'shop.srv.province',
  'Enter a Canadian postal code, like T2P 1B5.': 'shop.srv.postal',
  'Keep the unit under 20 characters.': 'shop.srv.unit',
  'Keep delivery notes under 200 characters.': 'shop.srv.note',
  'Choose a delivery window.': 'shop.srv.window',
  'Choose a quantity from 1 to 99.': 'shop.srv.qty',
  "This item isn't available any more.": 'shop.srv.unavailable',
  'Choose an option.': 'shop.srv.option',
  'This item is sold out.': 'shop.srv.soldOut',
};
export function serverMessage(message: string, t: (k: MessageKey) => string): string {
  const key = SERVER_MESSAGES[message];
  return key ? t(key) : message;
}

/** `code` of a problem answer (step_up_required, out_of_stock…). */
export const problemCode = (e: unknown) => (e instanceof ApiError ? (e.code ?? (typeof e.body.code === 'string' ? e.body.code : undefined)) : undefined);

// ── the cart (MOBILE_PLAN § Contracts › Cart count: query key ['cart']) ─────────────────────────────────────────────
export const CART_KEY = ['cart'] as const;

export function useCart() {
  const { locale } = useI18n();
  return useQuery({
    queryKey: CART_KEY,
    queryFn: async (): Promise<Cart> => {
      try {
        return (await shop().cart(locale)) ?? EMPTY_CART;
      } catch (e) {
        // no cart yet (or nobody to own one): an empty cart, as the consumer web does
        if (e instanceof ApiError && (e.status === 404 || e.status === 401)) return EMPTY_CART;
        throw e;
      }
    },
    staleTime: 10_000,
  });
}

/**
 * The tab bar's badge: units in the cart. Lives in the tabs layout, so it also refreshes the cart when the person signs
 * in or out (the api merges the guest's cart at sign-in) or changes language (item names).
 */
export function useCartCount(): number {
  const cart = useCart();
  const { status } = useAuth();
  const { locale } = useI18n();
  const qc = useQueryClient();
  const seen = useRef(`${status}|${locale}`);
  useEffect(() => {
    const now = `${status}|${locale}`;
    if (seen.current !== now) void qc.invalidateQueries({ queryKey: CART_KEY });
    seen.current = now;
  }, [status, locale, qc]);
  return cart.data?.itemCount ?? 0;
}

export function useChangeCartLine() {
  const qc = useQueryClient();
  const { locale } = useI18n();
  return useMutation({
    mutationFn: ({ itemId, qty }: { itemId: string; qty: number }) => shop().changeLine(itemId, qty, locale),
    onSuccess: (cart) => {
      if (cart) qc.setQueryData(CART_KEY, cart);
      else void qc.invalidateQueries({ queryKey: CART_KEY });
      void qc.invalidateQueries({ queryKey: ['shop', 'checkout'] });
    },
  });
}

export function useAddToCart() {
  const qc = useQueryClient();
  const { locale } = useI18n();
  return useMutation({
    mutationFn: (item: { offerId: string; variantId?: string; qty: number }) => shop().addToCart(item, locale),
    onSuccess: (cart) => {
      if (cart) qc.setQueryData(CART_KEY, cart);
      else void qc.invalidateQueries({ queryKey: CART_KEY });
    },
  });
}

/** A consumer-web path from the api ("Your week" rows) → the app's route for it, when the app has one. */
export function appRoute(href: string): string | null {
  const path = href.split('?')[0] ?? '';
  const booking = /^\/providers\/[^/]+\/book$/.test(path) ? new URLSearchParams(href.split('?')[1] ?? '').get('booking') : null;
  if (booking) return `/bookings/${encodeURIComponent(booking)}/booked`;
  let m = /^\/orders\/([^/]+)$/.exec(path);
  if (m) return `/orders/${m[1]}/track`;
  m = /^\/account\/problem\/(order|food|booking)\/([^/]+)$/.exec(path);
  if (m) return `/problem/${m[1]}/${m[2]}`;
  m = /^\/providers\/([^/]+)$/.exec(path);
  if (m) return `/providers/${m[1]}`;
  m = /^\/quotes\/(?!requests\/)([^/]+)$/.exec(path);
  if (m) return `/quotes/${m[1]}`;
  return null;
}
