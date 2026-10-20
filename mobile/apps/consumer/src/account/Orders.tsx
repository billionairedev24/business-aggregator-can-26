import { useQuery } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { router } from 'expo-router';
import { useState } from 'react';
import { Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';

import { Icon, colors, radius, semantic, space, type IconName } from '@northline/mobile-kit';

import type { ActivityItem } from '../api/account';
import type { RequestRow } from '../api/hire';
import { config } from '../config';
import { hire } from '../messages/Messages';
import { useAuth } from '../auth/AuthProvider';
import { useShopFormat } from '../shop/common';
import { Chip } from '../shop/parts';
import { Tag, Title, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, LoadingList, QueryView, SignInPrompt } from '../ui/states';
import { KEYS, account, worded } from './common';
import { targetOf, type Target } from './routes';

export type OrdersView = 'active' | 'past' | 'refunds' | 'quotes';
const VIEWS: readonly OrdersView[] = ['active', 'past', 'refunds', 'quotes'];
const inView = (i: ActivityItem, v: OrdersView) => (v === 'refunds' ? !!i.caseRef : v === 'active' ? i.active : !i.active);

/**
 * Activity tab (redesign spec § 2.4: Home · Browse · Activity · Cart · Account — orders and bookings live here and in
 * Account, never in a top nav; MA:268 ORDERS cards). D1 Orders & bookings (design 01 `orders`; signed in): every order, food order, booking and open
 * quote request in one list (`GET /me/activity`, active first), filtered "Active · n" / "Past" / "Refunds" (rows with
 * a refund case or dispute). A row opens the other journeys' screens by route (`targetOf`); pull to refresh. S-153:
 * "Quote requests" lists every request with what became of it (`GET /me/quote-requests`).
 */
export function Orders() {
  const f = useShopFormat();
  const { t } = f;
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const list = useQuery({ queryKey: KEYS.activity, queryFn: () => account().activity(), enabled: signedIn, staleTime: 30_000 });
  const [view, setView] = useState<OrdersView>('active');

  if (!signedIn) {
    return (
      <Screen testID="orders">
        <Title>{t('account.orders.title')}</Title>
        <SignInPrompt message={t('account.orders.signIn')} />
      </Screen>
    );
  }

  if (view === 'quotes') {
    return (
      <Screen testID="orders">
        <Title>{t('account.orders.title')}</Title>
        <Views view={view} setView={setView} label={(v) => (v === 'active' ? t('account.orders.active', { n: (list.data ?? []).filter((i) => i.active).length }) : t(`account.orders.${v}`))} />
        <QuoteRequests />
      </Screen>
    );
  }

  const items = list.data ?? [];
  const active = items.filter((i) => i.active).length;
  const label = (v: OrdersView) => (v === 'active' ? t('account.orders.active', { n: active }) : t(`account.orders.${v}`));
  const shown = items.filter((i) => inView(i, view));

  return (
    <Screen testID="orders" refreshControl={<RefreshControl refreshing={list.isRefetching} onRefresh={() => void list.refetch()} />}>
      <Title>{t('account.orders.title')}</Title>
      <Views view={view} setView={setView} label={label} />
      <QueryView
        query={list}
        skeleton={<LoadingList rows={5} height={56} />}
        isEmpty={() => shown.length === 0}
        empty={
          view === 'refunds' ? (
            <EmptyState draw={{ main: 'Receipt', side: 'ShieldCheck' }} message={t('account.orders.emptyRefunds')} />
          ) : (
            <EmptyState draw={{ main: 'ShoppingBag', side: 'CalendarCheck' }} message={t(view === 'active' ? 'account.orders.emptyActive' : 'account.orders.emptyPast')} action={t('account.orders.startShopping')} onAction={() => router.navigate('/home')} />
          )
        }
      >
        {() => (
          <View accessibilityRole="list" style={styles.list}>
            {shown.map((i) => (
              <Row key={`${i.kind}-${i.id}`} item={i} f={f} />
            ))}
          </View>
        )}
      </QueryView>
    </Screen>
  );
}

/** An activity row's title: an order by how it comes ("Delivery · 2 shops"), a booking or quote by its own title. */
export function activityTitle(item: ActivityItem, t: ReturnType<typeof useShopFormat>['t']): string {
  return item.kind === 'order'
    ? t(item.delivery === 'direct' ? 'account.orders.direct' : 'account.orders.pooled', { n: item.shops })
    : item.kind === 'food'
      ? t('account.orders.food')
      : item.title;
}

const KIND_ICON: Record<ActivityItem['kind'], IconName> = { order: 'package', food: 'package', booking: 'calendar-check', quote: 'clipboard-text' };

function Row({ item, f }: { item: ActivityItem; f: ReturnType<typeof useShopFormat> }) {
  const { t } = f;
  const title = activityTitle(item, t);
  const when = item.active ? `${f.date(item.when)} ${f.range(item.when, item.whenEnd)}` : f.date(item.when);
  const sub = [item.ref, when, item.with.join(', ')].filter(Boolean).join(' · ');
  const state = item.caseRef?.open ? t('account.status.case', { number: item.caseRef.number }) : worded(t, 'account.status', item.status);
  const tone = item.tone === 'accent' ? 'accent' : item.tone === 'accent-2' ? 'accent2' : 'neutral';
  const amount = item.amountCents > 0 ? f.money(item.amountCents) : t('account.orders.noAmount');
  const target = targetOf(item);
  const open = () => {
    if (!target) return;
    if ('route' in target) router.push(target.route as never);
    else void Linking.openURL(target.site);
  };
  return (
    <Pressable
      accessibilityRole={target && 'site' in target ? 'link' : 'button'}
      accessibilityLabel={`${title}, ${sub}, ${state}, ${amount}`}
      accessibilityHint={target && 'site' in target ? t('common.opensInBrowser') : undefined}
      disabled={!target}
      onPress={open}
      style={({ pressed }) => [styles.row, pressed && styles.pressed]}
      testID={`activity-${item.id}`}
    >
      <Icon name={KIND_ICON[item.kind]} size={24} color={item.active ? colors.link : colors.textMuted} />
      <View style={styles.text}>
        <Text style={[type.body, type.strong]}>{title}</Text>
        <Text style={type.small}>{sub}</Text>
        <Tag label={state} tone={tone} small />
      </View>
      <Text style={[type.body, type.strong]}>{amount}</Text>
    </Pressable>
  );
}

function Views({ view, setView, label }: { view: OrdersView; setView: (v: OrdersView) => void; label: (v: OrdersView) => string }) {
  const { t } = useShopFormat();
  return (
    <View style={styles.chips} accessibilityRole="radiogroup" accessibilityLabel={t('account.orders.show')}>
      {VIEWS.map((v) => (
        <Chip key={v} role="radio" label={label(v)} on={view === v} onPress={() => setView(v)} testID={`orders-${v}`} />
      ))}
    </View>
  );
}

/** Where a quote request opens: its one quote in the app, else the comparison on the consumer site. */
export function requestTarget(r: RequestRow): Exclude<Target, null> {
  const quote = /^\/quotes\/(?!requests\/)([^/?]+)$/.exec(r.href)?.[1];
  if (r.bookingId) return { route: `/bookings/${encodeURIComponent(r.bookingId)}/eta` };
  if (quote) return { route: `/quotes/${quote}` };
  return { site: `${config.siteOrigin}${r.href}` };
}

function QuoteRequests() {
  const f = useShopFormat();
  const { t } = f;
  const list = useQuery({ queryKey: ['hire', 'quote-requests'], queryFn: () => hire().requests(), staleTime: 30_000 });
  return (
    <QueryView
      query={list}
      skeleton={<LoadingList rows={4} height={56} />}
      isEmpty={(items) => items.length === 0}
      empty={<EmptyState draw={{ main: 'Wrench', side: 'CalendarCheck' }} message={t('hire.requests.empty')} action={t('hire.requests.browse')} onAction={() => router.navigate('/services')} />}
    >
      {(items) => (
        <View accessibilityRole="list" style={styles.list}>
          {items.map((r) => {
            const target = requestTarget(r);
            const sub = [r.ref, f.date(r.createdAt), t('hire.requests.providers', { n: r.providers }), r.photoCount > 0 ? t('hire.requests.photos', { n: r.photoCount }) : null].filter(Boolean).join(' · ');
            const state = t(`hire.requests.status.${r.status}`, { n: r.quotes });
            const amount = r.lowestCents != null ? t('hire.requests.from', { amount: f.money(r.lowestCents) }) : '';
            return (
              <Pressable
                key={r.id}
                accessibilityRole={'site' in target ? 'link' : 'button'}
                accessibilityLabel={[r.title, sub, state, amount].filter(Boolean).join(', ')}
                accessibilityHint={'site' in target ? t('common.opensInBrowser') : undefined}
                onPress={() => ('route' in target ? router.push(target.route as never) : void Linking.openURL(target.site))}
                style={({ pressed }) => [styles.row, pressed && styles.pressed]}
                testID={`request-${r.id}`}
              >
                <Icon name="clipboard-text" size={24} color={r.status === 'quoted' || r.status === 'waiting' ? colors.link : colors.textMuted} />
                <View style={styles.text}>
                  <Text style={[type.body, type.strong]}>{r.title}</Text>
                  <Text style={type.small}>{sub}</Text>
                  <Tag label={state} tone={r.status === 'quoted' ? 'accent2' : r.status === 'accepted' ? 'accent' : 'neutral'} small />
                </View>
                {amount ? <Text style={[type.body, type.strong]}>{amount}</Text> : null}
              </Pressable>
            );
          })}
        </View>
      )}
    </QueryView>
  );
}

const styles = StyleSheet.create({
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
  list: { gap: space[3] },
  row: { flexDirection: 'row', alignItems: 'flex-start', gap: space[3], padding: 14, minHeight: 56, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.surface },
  pressed: { backgroundColor: semantic['surface-hover'] },
  text: { flex: 1, minWidth: 0, gap: 4 },
});
