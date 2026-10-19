import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useState, type ReactNode } from 'react';
import { Image, StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, Path } from 'react-native-svg';

import { Icon, colors, fonts, radius, semantic, shadow, space, Scene } from '@northline/mobile-kit';

import { CourierTip } from '../aftercare/CourierTip';
import { ReviewPanel } from '../aftercare/Review';
import type { OrderTracking } from '../api/shop';
import { useAuth } from '../auth/AuthProvider';
import type { MessageKey } from '../i18n';
import { PushPrompt } from '../push/PushPrompt';
import { Body, Button, Notice, Tag, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { QueryView, SignInPrompt, Skeleton, errorMessage } from '../ui/states';
import { backToTab, shop, useShopFormat } from './common';
import { Ladder, Panel, shopStyles } from './parts';

/**
 * Live tracking: React Native has no EventSource, so instead of `GET /me/orders/{id}/events` (SSE) the order is read
 * again every 15 s while a screen shows it (DECISIONS S-99) — the stream's 25 s keep-alive and the courier's position
 * updates come at about that pace anyway.
 */
export const TRACK_POLL_MS = 15_000;

export function useOrder(orderId: string, poll = false) {
  const { status } = useAuth();
  return useQuery({
    queryKey: ['shop', 'order', orderId],
    queryFn: () => shop().order(orderId),
    enabled: status === 'signedIn' && !!orderId,
    refetchInterval: poll ? TRACK_POLL_MS : false,
  });
}

/** The order's screens: the header ("Order NL-48213"), guests asked to sign in, the order's states around `children`. */
function OrderScreen({ id, title, testID, footer, children }: { id: string; /** null: no header (Order confirmed) */ title?: string | null; testID: string; footer?: (o: OrderTracking) => ReactNode; children: (o: OrderTracking, updatedAt: number) => ReactNode }) {
  const { t } = useShopFormat();
  const { status } = useAuth();
  const order = useOrder(id, testID === 'track');
  const ref = order.data?.ref ?? id;
  const header = title === null ? undefined : (title ?? t('title.track', { ref }));
  if (status !== 'signedIn') {
    return (
      <Screen title={header} testID={testID}>
        <SignInPrompt message={t('shop.order.signIn')} />
      </Screen>
    );
  }
  return (
    <Screen title={header} testID={testID} footer={order.data && footer ? footer(order.data) : undefined}>
      <QueryView
        query={order}
        skeleton={
          <View testID="loading" accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.gap}>
            <Skeleton height={72} width={72} round />
            <Skeleton height={32} width="80%" />
            <Skeleton height={16} />
            <Skeleton height={120} />
          </View>
        }
      >
        {(o) => (o ? children(o, order.dataUpdatedAt) : null)}
      </QueryView>
    </Screen>
  );
}

/** "tonight 6:00 p.m.–9:00 p.m." / "by about 6:52 p.m." / "soon". */
function useWhen() {
  const f = useShopFormat();
  return (o: OrderTracking) => {
    if (o.delivery.kind === 'direct') return o.delivery.etaAt ? f.t('shop.when.direct', { time: f.time(o.delivery.etaAt) }) : f.t('shop.when.soon');
    return f.window(o.delivery, 'shop.when');
  };
}

function useSteps() {
  const { t } = useShopFormat();
  return (o: OrderTracking) =>
    o.steps.map((s) => ({
      key: s.key,
      state: s.state,
      label:
        s.key === 'packing'
          ? t('shop.step.packing', { packed: o.shops.filter((x) => x.packed).length, shops: o.shops.length })
          : t(`shop.step.${s.key}` as MessageKey),
    }));
}

/**
 * B7 Order confirmed (design 01 `confirmed`): the tick, "Order placed. Arriving {window}.", the order's ref, total and
 * shops, the status ladder (paid → shops packing → courier picks up → delivered, you confirm) — "shops are not paid
 * yet" — then Track order / Back to home. `GET /me/orders/{id}`.
 */
export function Confirmed() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const f = useShopFormat();
  const { t } = f;
  const when = useWhen();
  const steps = useSteps();
  return (
    <OrderScreen
      id={id}
      testID="confirmed"
      title={null}
      footer={() => (
        <>
          <Button label={t('shop.order.track')} large onPress={() => router.push({ pathname: '/orders/[id]/track', params: { id } })} testID="confirmed-track" />
          <Button label={t('shop.order.backHome')} tone="ghost" onPress={() => backToTab('/home')} />
        </>
      )}
    >
      {(o) => (
        <View style={styles.gap}>
          <Scene slot="banner/order-complete" subject="courier" draw={{ main: 'ShoppingBag', side: 'Truck' }} fit="contain" style={styles.confirmArt} />
          <View style={styles.placed} accessibilityRole="alert">
            <Icon name="check-circle" filled size={22} color={semantic.success} />
            <Text style={[styles.body, styles.strong, styles.flex]}>{t('rd.order.placed')}</Text>
          </View>
          {/* the sentence's own full stop, not a second one after "p.m." */}
          <Title>{t('shop.order.placed', { when: when(o).replace(/\.$/, '') })}</Title>
          <Body tone="muted">{t('shop.order.sub', { ref: o.ref ?? '', total: f.money(o.totalCents), shops: o.shops.length })}</Body>
          <PushPrompt what="order" />
          <Ladder steps={steps(o)} />
        </View>
      )}
    </OrderScreen>
  );
}

/**
 * B8 Track (design 01 `track`): the pooled run drawn as the design's schematic map (no map SDK: where the courier is,
 * in words — stops before yours), "Arriving {time}", the ref and run, the state, the courier, the drop-off PIN and the
 * ladder; read again every 15 s. Delivered → "See the delivery". Deep links (S-102) open `/orders/{id}/track`.
 */
export function Track() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const f = useShopFormat();
  const { t } = f;
  const when = useWhen();
  const steps = useSteps();
  return (
    <OrderScreen
      id={id}
      testID="track"
      footer={(o) =>
        o.state === 'delivered' || o.state === 'confirmed' ? (
          <Button label={t('shop.track.see')} large onPress={() => router.push({ pathname: '/orders/[id]/delivered', params: { id } })} testID="track-delivered" />
        ) : null
      }
    >
      {(o, updatedAt) => {
        const c = o.courier;
        const eta = c?.eta ?? o.delivery.etaAt;
        const where = c ? (c.stopsBefore === 0 ? t('shop.track.next') : t('shop.track.stopsBefore', { n: c.stopsBefore })) : t(`shop.state.${o.state}` as MessageKey);
        const minutes = eta ? Math.max(0, Math.round((new Date(eta).getTime() - updatedAt) / 60_000)) : null;
        const ref = o.ref ?? id;
        return (
          <View style={styles.gap}>
            <View style={styles.head}>
              <Text style={styles.small}>{ref}</Text>
              <Text accessibilityRole="header" style={styles.headline}>
                {t('shop.track.arriving', { when: eta ? f.time(eta) : when(o) })}
              </Text>
            </View>
            <View accessible accessibilityRole="image" accessibilityLabel={t('shop.track.map', { where })} style={styles.map}>
              <Svg width="100%" height="100%" viewBox="0 0 402 200" preserveAspectRatio="xMidYMid slice">
                {/* a quiet street grid under the run (no map SDK: a schematic) */}
                <Path d="M0 60 H402 M0 140 H402 M70 0 V200 M180 0 V200 M300 0 V200" stroke={colors.surface} strokeWidth={10} />
                <Path d="M0 100 H402 M240 0 V200" stroke={colors.surface} strokeWidth={6} />
                <Path d="M30 170 L110 170 L110 110 L240 110 L240 50 L340 50" fill="none" stroke={colors.actionPrimary} strokeWidth={4} strokeDasharray="8 6" />
                <Circle cx={30} cy={170} r={8} fill={colors.textMuted} />
                <Circle cx={110} cy={110} r={8} fill={colors.textMuted} />
                <Circle cx={240} cy={c && c.stopsBefore === 0 ? 70 : 110} r={11} fill={semantic['action-accent']} stroke={colors.surface} strokeWidth={3} />
                <Circle cx={340} cy={50} r={9} fill={colors.surface} stroke={colors.text} strokeWidth={3} />
              </Svg>
              <View style={styles.etaPill}>
                <Text style={styles.etaText}>{minutes !== null && c ? t('rd.order.minutes', { n: minutes }) : where}</Text>
              </View>
            </View>
            <View style={styles.stateRow}>
              <Body tone="small" style={styles.flex}>
                {o.delivery.kind === 'direct'
                  ? t('shop.track.direct', { ref })
                  : minutes !== null && c
                    ? t('shop.track.away', { ref, n: minutes })
                    : t('shop.track.pooled', { ref })}
              </Body>
              <Tag label={t(`shop.state.${o.state}` as MessageKey)} tone="accent" />
            </View>
            <Ladder steps={steps(o)} />
            <View style={styles.courier} testID="track-courier">
              <View style={styles.courierRow}>
              <View style={styles.courierAvatar}>
                {c?.courierName ? <Text style={styles.courierInitials}>{initialsOf(c.courierName)}</Text> : <Icon name="truck" size={22} color={semantic['action-accent-text']} />}
              </View>
              <View style={styles.flex}>
                <Text style={styles.courierName}>{c?.courierName ? t('shop.track.courier', { name: c.courierName }) : t('shop.track.noCourier')}</Text>
                {c?.pin ? <Text style={styles.courierSub}>{t('rd.order.pinNote')}</Text> : null}
              </View>
              </View>
              {c?.pin ? (
                <View style={styles.pinBox}>
                  <Text style={styles.pin} accessibilityLabel={t('shop.track.pin', { pin: c.pin })}>
                    {c.pin}
                  </Text>
                </View>
              ) : null}
            </View>
            <Body tone="small">{t('shop.track.live', { time: f.time(new Date(updatedAt).toISOString()) })}</Body>
          </View>
        );
      }}
    </OrderScreen>
  );
}

/**
 * B9 Delivered · confirm (design 01 `delivered`): the courier's proof, "Delivered at {time}", when the shops are paid
 * without a confirmation (the api's `paysShopsAt` — 7 days for goods, CLAUDE.md; the design's "24 h" is older), All
 * good (`POST /me/orders/{id}/confirm` releases the shops' escrow) or Something's wrong (the report). The courier's
 * door photo comes from `GET /me/orders/{id}/proof-photo` (a 5-minute signed URL, mobile gaps part 1) when the proof is
 * a photo — never for a delivery with an ID check (the api answers 404). Mobile gaps part 2: the courier's tip after
 * the delivery (up to 7 days, 100 % to the courier) and a review of each shop (`GET|POST /me/reviews`).
 */
export function Delivered() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const f = useShopFormat();
  const { t } = f;
  const qc = useQueryClient();
  const confirm = useMutation({
    mutationFn: () => shop().confirm(id),
    onSuccess: (o) => {
      if (o) qc.setQueryData(['shop', 'order', id], o);
      void qc.invalidateQueries({ queryKey: ['shop', 'order', id] });
    },
  });
  return (
    <OrderScreen
      id={id}
      testID="delivered"
      footer={() => <Button label={t('shop.delivered.viewOrder')} tone="ghost" onPress={() => backToTab('/orders')} />}
    >
      {(o) => {
        if (!o.deliveredAt) {
          return (
            <View style={styles.gap}>
              <Body tone="muted">{t('shop.delivered.notYet')}</Body>
              <Button label={t('shop.order.track')} tone="secondary" onPress={() => router.replace({ pathname: '/orders/[id]/track', params: { id } })} />
            </View>
          );
        }
        const at = f.time(o.deliveredAt);
        const done = !!o.confirmedAt || o.state === 'confirmed';
        return (
          <View style={styles.gap}>
            <Proof orderId={o.orderId} kind={o.deliveryProof ?? null} caption={t(`shop.delivered.proof.${o.deliveryProof ?? 'none'}` as MessageKey, { time: at })} />
            <Title>{t('shop.delivered.title', { time: at })}</Title>
            <Body tone="muted">{o.paysShopsAt ? t('shop.delivered.body', { date: f.date(o.paysShopsAt) }) : t('shop.delivered.bodyNoDate')}</Body>
            {done ? (
              <Panel tone="accent" testID="delivered-thanks">
                <Text style={[styles.body, shopStyles.accentText]}>{t('shop.delivered.thanks', { shops: o.shops.length })}</Text>
              </Panel>
            ) : (
              <>
                {confirm.error ? <Notice message={errorMessage(confirm.error, t)} /> : null}
                <View style={styles.actions}>
                  <Button label={t('shop.delivered.allGood')} large busy={confirm.isPending} disabled={o.canConfirm === false} onPress={() => confirm.mutate()} style={styles.flex} testID="delivered-confirm" />
                  <Button label={t('shop.delivered.wrong')} tone="secondary" onPress={() => router.push({ pathname: '/problem/[kind]/[id]', params: { kind: 'order', id } })} testID="delivered-problem" />
                </View>
              </>
            )}
            <CourierTip orderId={o.orderId} />
            <ReviewPanel kind="order" id={o.orderId} />
          </View>
        );
      }}
    </OrderScreen>
  );
}

/** The door photo when there is one to show, else the placeholder naming the proof (PIN, signature, none). */
function Proof({ orderId, kind, caption }: { orderId: string; kind: string | null; caption: string }) {
  const { t } = useShopFormat();
  const [broken, setBroken] = useState(false);
  const photo = useQuery({
    queryKey: ['shop', 'order', orderId, 'proof-photo'],
    queryFn: () => shop().proofPhoto(orderId),
    enabled: kind === 'photo',
    retry: false,
    // the link works 5 minutes: a screen left open reads a fresh one before it lapses
    staleTime: 4 * 60_000,
    refetchInterval: 4 * 60_000,
  });
  if (photo.data && !broken) {
    return (
      <View style={styles.gapSmall}>
        <Image
          source={{ uri: photo.data.url }}
          style={styles.photo}
          resizeMode="cover"
          accessibilityLabel={t('shop.delivered.photoAlt')}
          onError={() => setBroken(true)}
          testID="proof-photo"
        />
        <Text style={styles.small}>{caption}</Text>
      </View>
    );
  }
  return (
    <View style={styles.proof} testID="proof-placeholder">
      <Scene slot="empty/proof-photo" subject="courier" draw={{ main: 'Package', side: kind === 'pin' ? 'Key' : 'ShieldCheck' }} fit="contain" style={styles.proofArt} />
      <Text style={styles.proofText}>{caption}</Text>
    </View>
  );
}

/** "Kwame B." → "KB" (the courier card's avatar). */
const initialsOf = (name: string) =>
  name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');

const styles = StyleSheet.create({
  gap: { gap: space[4] },
  head: { gap: 2 },
  headline: { fontFamily: fonts.display, fontSize: 26, lineHeight: 32, color: colors.text },
  placed: { flexDirection: 'row', alignItems: 'center', gap: space[2], paddingHorizontal: 14, paddingVertical: space[3], borderRadius: radius.lg, backgroundColor: semantic['success-bg'] },
  strong: { fontFamily: fonts.bold },
  stateRow: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  etaPill: { position: 'absolute', right: 12, bottom: 12, paddingHorizontal: 12, paddingVertical: 6, borderRadius: radius.pill, backgroundColor: colors.surface, ...shadow.md },
  etaText: { fontFamily: fonts.bold, fontSize: 13, color: semantic['action-primary'] },
  courier: { gap: space[3], padding: space[4], borderRadius: radius.lg, borderWidth: 1, borderColor: semantic['border-card'], backgroundColor: colors.surface, ...shadow.sm },
  courierRow: { flexDirection: 'row', alignItems: 'center', gap: space[3] },
  courierAvatar: { width: 48, height: 48, borderRadius: 24, backgroundColor: semantic['action-primary'], alignItems: 'center', justifyContent: 'center' },
  courierInitials: { fontFamily: fonts.bold, fontSize: 16, color: semantic['action-primary-text'] },
  courierName: { fontFamily: fonts.semibold, fontSize: 16, color: colors.text },
  courierSub: { fontFamily: fonts.regular, fontSize: 13, color: colors.textMuted },
  pinBox: { alignItems: 'center', paddingVertical: space[3], borderRadius: radius.lg, backgroundColor: semantic['surface-brand'] },
  pin: { fontFamily: fonts.display, fontSize: 34, letterSpacing: 8, color: semantic['badge-brand-text'] },
  gapSmall: { gap: space[1] },
  confirmArt: { height: 150, borderRadius: radius.lg },
  photo: { height: 240, borderRadius: radius.lg, backgroundColor: semantic['surface-sunken'] },
  flex: { flex: 1 },
  actions: { flexDirection: 'row', gap: 10, flexWrap: 'wrap' },
  map: { height: 200, borderRadius: radius.lg, backgroundColor: semantic['surface-sunken'], overflow: 'hidden' },
  proof: { borderRadius: radius.lg, backgroundColor: semantic['surface-sunken'], overflow: 'hidden', paddingBottom: space[3], gap: space[2] },
  proofArt: { height: 160 },
  proofText: { fontFamily: fonts.medium, fontSize: 14, color: colors.text, textAlign: 'center', paddingHorizontal: space[3] },
  body: { fontFamily: fonts.regular, fontSize: 15, color: colors.text },
  small: { fontFamily: fonts.regular, fontSize: 13, color: colors.textMuted },
});
