import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useState } from 'react';
import { RefreshControl, StyleSheet, Text, View } from 'react-native';

import { colors, fonts, space } from '@northline/mobile-kit';

import type { ActivityItem, NotificationPrefs } from '../api/services';
import { useAuth } from '../auth/AuthProvider';
import { useI18n, type MessageKey } from '../i18n';
import { Link, Notice, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, errorMessage, QueryView, SignInPrompt, Skeleton } from '../ui/states';
import { clock } from './format';
import { Chip, Row, useServicesApi } from './parts';

export type Inbox = 'all' | 'bookings' | 'orders' | 'offers';
const INBOXES: readonly Inbox[] = ['all', 'bookings', 'orders', 'offers'];
const inInbox = (i: ActivityItem, box: Inbox) =>
  box === 'all' || (box === 'bookings' && (i.kind === 'booking' || i.kind === 'quote')) || (box === 'orders' && (i.kind === 'order' || i.kind === 'food'));

/** "now", "12 min", "Yesterday", "3 d" — how long ago, as the design's list says it. */
export function ago(iso: string, t: (k: MessageKey, p?: Record<string, number>) => string, now = Date.now()) {
  const min = Math.max(0, Math.round((now - Date.parse(iso)) / 60_000));
  if (min < 2) return t('services.ago.now');
  if (min < 60) return t('services.ago.min', { n: min });
  const h = Math.round(min / 60);
  if (h < 24) return t('services.ago.hours', { n: h });
  const d = Math.round(h / 24);
  return d === 1 ? t('services.ago.yesterday') : t('services.ago.days', { n: d });
}

/** Where a row opens: the booking (its deep-link screen), the order's tracking (Journey B), else Orders (Journey D). */
const target = (i: ActivityItem) => (i.kind === 'booking' ? `/bookings/${i.id}` : i.kind === 'order' ? `/orders/${i.id}/track` : '/orders');

/**
 * C8 Notifications (design 01 `notifications`): bookings, orders and quotes in one inbox (the api's activity, newest
 * first), filtered as the design's tags; the quiet hours under it, switched on or off here (`PUT /me/notifications`).
 * Offers have no feed yet: that filter is empty.
 */
export function Notifications() {
  const { t, locale } = useI18n();
  const { status } = useAuth();
  const api = useServicesApi();
  const qc = useQueryClient();
  const signedIn = status === 'signedIn';
  const activity = useQuery({ queryKey: ['services', 'activity'], queryFn: () => api.activity(), enabled: signedIn, staleTime: 30_000 });
  const prefs = useQuery({ queryKey: ['services', 'notification-prefs'], queryFn: () => api.notificationPrefs(), enabled: signedIn, staleTime: 60_000 });
  const quiet = useMutation({
    mutationFn: (on: boolean) => api.setQuietHours(on),
    onSuccess: (p) => qc.setQueryData<NotificationPrefs>(['services', 'notification-prefs'], p),
  });
  const [box, setBox] = useState<Inbox>('all');

  const footer = prefs.data ? (
    <View style={styles.footer}>
      <Text style={type.small} testID="quiet-hours">
        {prefs.data.quietOn
          ? t('services.notifications.quiet', { from: clock(prefs.data.quietFrom, locale), to: clock(prefs.data.quietTo, locale) })
          : t('services.notifications.quietOff')}
      </Text>
      {quiet.isError ? <Notice message={errorMessage(quiet.error, t)} /> : null}
      <Link
        label={prefs.data.quietOn ? t('services.notifications.turnOff') : t('services.notifications.turnOn')}
        onPress={() => quiet.mutate(!prefs.data!.quietOn)}
        testID="quiet-toggle"
      />
    </View>
  ) : undefined;

  return (
    <Screen
      title={t('title.notifications')}
      testID="notifications"
      footer={signedIn ? footer : undefined}
      refreshControl={signedIn ? <RefreshControl refreshing={activity.isRefetching} onRefresh={() => void activity.refetch()} accessibilityLabel={t('common.retry')} /> : undefined}
    >
      {!signedIn ? (
        <SignInPrompt message={t('services.notifications.signIn')} />
      ) : (
        <>
          <View style={styles.chips} accessibilityRole="tablist">
            {INBOXES.map((b) => (
              <Chip key={b} label={t(`services.notifications.${b}`)} selected={box === b} onPress={() => setBox(b)} testID={`inbox-${b}`} />
            ))}
          </View>
          <QueryView
            query={activity}
            skeleton={<InboxSkeleton />}
            isEmpty={(d) => d.items.filter((i) => inInbox(i, box)).length === 0}
            empty={<EmptyState message={t(box === 'offers' ? 'services.notifications.noOffers' : 'services.notifications.empty')} />}
          >
            {(d) =>
              d.items
                .filter((i) => inInbox(i, box))
                .map((i) => <Item key={`${i.kind}-${i.id}`} i={i} />)
            }
          </QueryView>
        </>
      )}
    </Screen>
  );
}

function Item({ i }: { i: ActivityItem }) {
  const { t } = useI18n();
  const name = i.with[0] ?? '';
  const known = ['packing', 'ready', 'on_the_way', 'delivered', 'paid', 'cooking', 'requested', 'booked', 'escrow', 'deposit_held', 'on_site', 'completed', 'done', 'waiting', 'quote_ready', 'declined', 'expired', 'refunded', 'cancelled', 'case'];
  const title = known.includes(i.status) ? t(`services.activity.${i.status}` as 'services.activity.done', { name }) : name || i.title;
  const body = [i.title, i.ref].filter(Boolean).join(' · ');
  const when = ago(i.when, t);
  const dot = i.tone === 'accent-2' ? colors.accent2 : i.tone === 'accent' && i.active ? colors.accent : colors.neutral400;
  return (
    <Row onPress={() => router.push(target(i))} label={`${title}. ${body}. ${when}`} testID={`notification-${i.id}`}>
      <View style={styles.item}>
        <View style={[styles.dot, { backgroundColor: dot }]} />
        <View style={styles.flex}>
          <Text style={[type.body, styles.title]}>{title}</Text>
          <Text style={type.small}>{body}</Text>
        </View>
        <Text style={type.small}>{when}</Text>
      </View>
    </Row>
  );
}

function InboxSkeleton() {
  const { t } = useI18n();
  return (
    <View accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.list} testID="loading">
      {[0, 1, 2, 3, 4].map((i) => (
        <View key={i} style={styles.item}>
          <Skeleton height={10} width={10} round />
          <View style={styles.flex}>
            <Skeleton height={16} width="70%" />
            <Skeleton height={12} width="90%" />
          </View>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  list: { gap: space[4] },
  item: { flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
  dot: { width: 10, height: 10, borderRadius: 5, marginTop: 6 },
  title: { fontFamily: fonts.bodyStrong },
  footer: { gap: 2 },
});
