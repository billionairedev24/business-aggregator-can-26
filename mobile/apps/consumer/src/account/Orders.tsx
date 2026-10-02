import { useQuery } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { router } from 'expo-router';
import { useState } from 'react';
import { Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';

import { colors, space } from '@northline/mobile-kit';

import type { ActivityItem } from '../api/account';
import { useAuth } from '../auth/AuthProvider';
import { useShopFormat } from '../shop/common';
import { Chip } from '../shop/parts';
import { Tag, Title, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, LoadingList, QueryView, SignInPrompt } from '../ui/states';
import { KEYS, account, worded } from './common';
import { targetOf } from './routes';

export type OrdersView = 'active' | 'past' | 'refunds';
const VIEWS: readonly OrdersView[] = ['active', 'past', 'refunds'];
const inView = (i: ActivityItem, v: OrdersView) => (v === 'refunds' ? !!i.caseRef : v === 'active' ? i.active : !i.active);

/**
 * D1 Orders & bookings (design 01 `orders`, the Orders tab; signed in): every order, food order, booking and open
 * quote request in one list (`GET /me/activity`, active first), filtered "Active · n" / "Past" / "Refunds" (rows with
 * a refund case or dispute). A row opens the other journeys' screens by route (`targetOf`); pull to refresh.
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

  const items = list.data ?? [];
  const active = items.filter((i) => i.active).length;
  const label = (v: OrdersView) => (v === 'active' ? t('account.orders.active', { n: active }) : t(`account.orders.${v}`));
  const shown = items.filter((i) => inView(i, view));

  return (
    <Screen testID="orders" refreshControl={<RefreshControl refreshing={list.isRefetching} onRefresh={() => void list.refetch()} />}>
      <Title>{t('account.orders.title')}</Title>
      <View style={styles.chips} accessibilityRole="radiogroup" accessibilityLabel={t('account.orders.show')}>
        {VIEWS.map((v) => (
          <Chip key={v} role="radio" label={label(v)} on={view === v} onPress={() => setView(v)} testID={`orders-${v}`} />
        ))}
      </View>
      <QueryView
        query={list}
        skeleton={<LoadingList rows={5} height={56} />}
        isEmpty={() => shown.length === 0}
        empty={
          view === 'refunds' ? (
            <EmptyState message={t('account.orders.emptyRefunds')} />
          ) : (
            <EmptyState message={t(view === 'active' ? 'account.orders.emptyActive' : 'account.orders.emptyPast')} action={t('account.orders.startShopping')} onAction={() => router.navigate('/home')} />
          )
        }
      >
        {() => (
          <View accessibilityRole="list">
            {shown.map((i) => (
              <Row key={`${i.kind}-${i.id}`} item={i} f={f} />
            ))}
          </View>
        )}
      </QueryView>
    </Screen>
  );
}

function Row({ item, f }: { item: ActivityItem; f: ReturnType<typeof useShopFormat> }) {
  const { t } = f;
  const title =
    item.kind === 'order'
      ? t(item.delivery === 'direct' ? 'account.orders.direct' : 'account.orders.pooled', { n: item.shops })
      : item.kind === 'food'
        ? t('account.orders.food')
        : item.title;
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
      <View style={styles.text}>
        <Text style={[type.body, type.strong]}>{title}</Text>
        <Text style={type.small}>{sub}</Text>
      </View>
      <View style={styles.right}>
        <Tag label={state} tone={tone} />
        <Text style={type.small}>{amount}</Text>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  row: { flexDirection: 'row', justifyContent: 'space-between', gap: space[3], paddingVertical: space[3], minHeight: 56 },
  pressed: { backgroundColor: colors.accent100 },
  text: { flex: 1, minWidth: 0, gap: 2 },
  right: { alignItems: 'flex-end', gap: 4, flexShrink: 0, maxWidth: '45%' },
});
