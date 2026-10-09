import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Redirect, router } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, Path } from 'react-native-svg';

import { colors, fonts, space } from '@northline/mobile-kit';

import type { Booking } from '../api/services';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n';
import { PushPrompt } from '../push/PushPrompt';
import { Body, Button, Checkbox, Field, Notice, Section, Tag, Title, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { errorMessage, Loading, QueryView, SignInPrompt, Skeleton } from '../ui/states';
import { hoursUntil, money } from './format';
import { Chip, favouritesChanged, Kicker, Panel, useServicesApi, type T } from './parts';

/** The live states refresh on their own (the push says so too, S-102); the rest only on demand. */
const LIVE: ReadonlySet<string> = new Set(['confirmed', 'en_route', 'on_site']);

export function useBooking(id: string) {
  const api = useServicesApi();
  const { status } = useAuth();
  return useQuery({
    queryKey: ['services', 'booking', id],
    queryFn: () => api.booking(id),
    enabled: status === 'signedIn',
    refetchInterval: (q) => (q.state.data && (q.state.data.state === 'en_route' || q.state.data.state === 'on_site') ? 30_000 : false),
  });
}

/** Who comes: the member's first name, else the business. */
const who = (b: Booking) => b.memberFirstName ?? b.providerName;
const when = (b: Booking, day: (i: string, z: string) => string, time: (i: string, z: string) => string) => `${day(b.startsAt, b.timeZone)} · ${time(b.startsAt, b.timeZone)}`;

/** A personal booking screen's frame: the sign-in prompt for guests, the booking's states otherwise. */
function BookingScreen({ id, title, testID, children, footer }: { id: string; title?: string; testID: string; children: (b: Booking) => React.ReactNode; footer?: (b: Booking) => React.ReactNode }) {
  const { t } = useI18n();
  const { status } = useAuth();
  const booking = useBooking(id);
  return (
    <Screen title={title} testID={testID} footer={booking.data && footer ? footer(booking.data) : undefined}>
      {status !== 'signedIn' ? (
        <SignInPrompt message={t('services.booking.signIn')} />
      ) : (
        <QueryView query={booking} skeleton={<BookingSkeleton />}>
          {children}
        </QueryView>
      )}
    </Screen>
  );
}

/**
 * `/bookings/<id>` — where a booking's deep link lands (S-102: "Booking confirmed", the reminder, "on the way", "job
 * done — please sign off"): the sign-off once the job is done, the day-of screen before.
 */
export function BookingLink({ id }: { id: string }) {
  const { t } = useI18n();
  const { status } = useAuth();
  const booking = useBooking(id);
  if (status === 'loading') return <Loading />;
  if (status === 'signedIn' && booking.data) {
    const done = booking.data.state === 'completed' || booking.data.state === 'signed_off';
    return <Redirect href={done ? `/bookings/${id}/sign-off` : `/bookings/${id}/eta`} />;
  }
  return (
    <Screen title={t('title.eta', { ref: '' }).trim()} testID="booking-link">
      {status !== 'signedIn' ? <SignInPrompt message={t('services.booking.signIn')} /> : <QueryView query={booking}>{() => null}</QueryView>}
    </Screen>
  );
}

/** C7 Booked (design 01 `booked`): what happens next. */
export function Booked({ id }: { id: string }) {
  const { t, locale, day, time } = useI18n();
  return (
    <BookingScreen
      id={id}
      testID="booked"
      footer={() => (
        <>
          <Button label={t('services.booked.notifications')} large onPress={() => router.push('/notifications')} testID="see-notifications" />
          <Button label={t('services.done')} tone="ghost" onPress={() => router.replace('/home')} />
        </>
      )}
    >
      {(b) => (
        <View style={styles.stack}>
          <Svg width={72} height={72} viewBox="0 0 72 72" accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
            <Circle cx={36} cy={36} r={34} fill={colors.accent100} stroke={colors.accent} strokeWidth={2} />
            <Path d="M22 37 L32 47 L51 27" fill="none" stroke={colors.accent700} strokeWidth={4} strokeLinecap="round" strokeLinejoin="round" />
          </Svg>
          <Title>{t('services.booked.title', { name: who(b), when: when(b, day, time) })}</Title>
          <Body tone="muted">
            {b.heldCents > 0 ? t('services.booked.held', { ref: b.ref, total: money(b.heldCents, locale) }) : t('services.booked.free', { ref: b.ref })}
          </Body>
          <PushPrompt what="booking" />
          <Section>{t('services.booked.next')}</Section>
          {(['next1', 'next2', 'next3', 'next4'] as const).map((k) => (
            <Text key={k} style={[type.body, styles.next]}>· {t(`services.booked.${k}`, { name: who(b) })}</Text>
          ))}
        </View>
      )}
    </BookingScreen>
  );
}

/** The state's tag (design: "On the way" rosehip). */
function stateTag(b: Booking, t: T): { label: string; tone: 'accent' | 'accent2' | 'neutral' } {
  switch (b.state) {
    case 'en_route':
      return { label: t('services.state.en_route'), tone: 'accent2' };
    case 'on_site':
      return { label: t('services.state.on_site'), tone: 'accent' };
    case 'completed':
      return { label: t('services.state.completed'), tone: 'accent' };
    case 'signed_off':
      return { label: t('services.state.signed_off'), tone: 'neutral' };
    case 'cancelled':
      return { label: t('services.state.cancelled'), tone: 'neutral' };
    case 'disputed':
      return { label: t('services.state.disputed'), tone: 'accent2' };
    default:
      return { label: t('services.state.confirmed'), tone: 'neutral' };
  }
}

/**
 * C9 Day-of ETA (design 01 `eta`): where the job is — booked, on the way, arrived, done — with the steps and their
 * times in the business's zone, who is coming and the booking. Refreshes every 30 s while the member travels or works.
 * The live map, minutes away, vehicle and plate and access sharing need data the api doesn't have yet (DECISIONS S-100).
 */
export function Eta({ id }: { id: string }) {
  const { t, day, time } = useI18n();
  const booking = useBooking(id);
  return (
    <BookingScreen
      id={id}
      title={t('title.eta', { ref: booking.data?.ref ?? '' }).trim()}
      testID="eta"
      footer={(b) =>
        b.state === 'completed' || b.state === 'signed_off' ? (
          <Button label={t('services.eta.signOff')} large onPress={() => router.push(`/bookings/${id}/sign-off`)} testID="go-sign-off" />
        ) : null
      }
    >
      {(b) => {
        const tag = stateTag(b, t);
        const headline =
          b.state === 'en_route'
            ? t('services.eta.onTheWay', { name: who(b) })
            : b.state === 'on_site'
              ? t('services.eta.arrived', { name: who(b) })
              : b.state === 'completed' || b.state === 'signed_off'
                ? t('services.eta.done', { name: who(b) })
                : t('services.eta.booked', { name: who(b), when: when(b, day, time) });
        const steps = [
          ...b.steps.map((s) => ({ at: time(s.at, b.timeZone), label: t(`services.step.${s.type}`, { name: who(b) }) })),
        ];
        return (
          <View style={styles.stack}>
            <View style={styles.head}>
              <View style={styles.flex}>
                <Title>{headline}</Title>
                <Body tone="small">{[b.title, when(b, day, time), b.ref].join(' · ')}</Body>
              </View>
              <Tag label={tag.label} tone={tag.tone} />
            </View>
            <View style={styles.person} accessible>
              <View style={styles.avatar} />
              <View style={styles.flex}>
                <Text style={[type.body, type.strong]}>{b.memberFirstName ? `${b.memberFirstName} · ${b.providerName}` : b.providerName}</Text>
                <Text style={type.small}>{t('services.eta.verified')}</Text>
              </View>
            </View>
            {b.addressLine ? <Body tone="small">{t('services.eta.where', { address: b.addressLine })}</Body> : null}
            <View style={styles.timeline} accessibilityLabel={t('services.eta.timeline')}>
              <View style={styles.step}>
                <Text style={[type.small, styles.stepAt]}>{day(b.startsAt, b.timeZone)}</Text>
                <Text style={[type.body, styles.flex]}>{t('services.step.booked', { time: time(b.startsAt, b.timeZone) })}</Text>
              </View>
              {steps.map((s, i) => (
                <View key={i} style={styles.step}>
                  <Text style={[type.small, styles.stepAt, i === steps.length - 1 && styles.now]}>{s.at}</Text>
                  <Text style={[type.body, styles.flex, i === steps.length - 1 && type.strong]}>{s.label}</Text>
                </View>
              ))}
            </View>
            {LIVE.has(b.state) ? <Body tone="small">{t('services.eta.pushNote')}</Body> : null}
          </View>
        );
      }}
    </BookingScreen>
  );
}

/**
 * C10 Completion & sign-off (design 01 `signoff`): the member's report, photos taken, the receipt; "Release payment"
 * (`POST /me/bookings/{id}/sign-off`, the escrow releases at once) or "Raise an issue" (S-60's report, Journey B's
 * screen); the automatic release time while nothing happens. Released: "Rate …".
 */
export function SignOff({ id }: { id: string }) {
  const { t, locale } = useI18n();
  const api = useServicesApi();
  const qc = useQueryClient();
  const release = useMutation({
    mutationFn: () => api.signOff(id),
    onSuccess: (b) => {
      qc.setQueryData(['services', 'booking', id], b);
      void qc.invalidateQueries({ queryKey: ['account'] });
    },
  });
  return (
    <BookingScreen id={id} title={t('title.signoff')} testID="sign-off">
      {(b) => {
        if (b.state !== 'completed' && b.state !== 'signed_off') {
          return (
            <View style={styles.stack}>
              <Body tone="muted">{t('services.signoff.notYet', { name: who(b) })}</Body>
              <Button label={t('services.signoff.seeEta')} tone="secondary" onPress={() => router.replace(`/bookings/${id}/eta`)} />
            </View>
          );
        }
        const signed = b.state === 'signed_off';
        return (
          <View style={styles.stack}>
            <Kicker>{signed ? t('services.signoff.kickerDone') : t('services.signoff.kicker')}</Kicker>
            <Title>{t('services.signoff.title', { name: who(b), job: b.title })}</Title>
            {b.photoCount > 0 ? (
              <View style={styles.photos}>
                <Panel style={styles.photo}>
                  <Text style={type.small}>{t('services.signoff.photos', { n: b.photoCount })}</Text>
                </Panel>
              </View>
            ) : null}
            {b.report ? (
              <Text style={type.body}>
                <Text style={type.strong}>{t('services.signoff.report', { name: who(b) })}</Text> {b.report}
              </Text>
            ) : null}
            <Body tone="small">{t('services.signoff.receipt', { job: b.title, total: money(b.priceCents + b.taxCents, locale) })}</Body>
            {signed ? (
              <Panel tone="accent" testID="released">
                <Text style={[type.body, styles.released]}>
                  <Text style={type.strong}>{t('services.signoff.released')}</Text> {t('services.signoff.paid', { total: money(b.heldCents, locale), name: b.providerName })}
                </Text>
              </Panel>
            ) : (
              <>
                <Panel style={styles.heldRow}>
                  <Text style={type.body}>{t('services.book.held')}</Text>
                  <Text style={[type.body, type.strong]}>{money(b.heldCents, locale)}</Text>
                </Panel>
                {release.isError ? <Notice message={errorMessage(release.error, t)} /> : null}
                <View style={styles.actions}>
                  <Button label={t('services.signoff.release')} large busy={release.isPending} style={styles.flex} onPress={() => release.mutate()} testID="release" />
                  <Button label={t('services.signoff.issue')} tone="secondary" large onPress={() => router.push(`/problem/booking/${id}`)} testID="raise-issue" />
                </View>
                <Body tone="small">
                  {b.releasesAt ? t('services.signoff.auto', { h: hoursUntil(b.releasesAt) }) : t('services.signoff.autoNoTime')}
                </Body>
              </>
            )}
            {signed ? <Button label={t('services.signoff.rate', { name: who(b) })} large onPress={() => router.push(`/bookings/${id}/review`)} testID="rate" /> : null}
          </View>
        );
      }}
    </BookingScreen>
  );
}

const PRAISE = ['onTime', 'clear', 'fairPrice', 'clean', 'extraMile', 'friendly'] as const;

/**
 * C11 Two-way review (design 01 `review`): stars, what stood out, a note and "Add … to my favourites". Posting the
 * review has no consumer endpoint yet (MOBILE_PLAN § API gaps): the screen says so plainly and saves only the
 * favourite.
 */
export function Review({ id }: { id: string }) {
  const { t } = useI18n();
  const api = useServicesApi();
  const [stars, setStars] = useState(0);
  const [praise, setPraise] = useState<ReadonlySet<string>>(new Set());
  const [note, setNote] = useState('');
  const [favourite, setFavourite] = useState(true);
  const qc = useQueryClient();
  const save = useMutation({
    mutationFn: (b: Booking) => (favourite ? api.favourite(b.merchantId, true) : Promise.resolve(null)),
    onSuccess: () => {
      if (favourite) void favouritesChanged(qc);
      router.replace('/orders');
    },
  });
  return (
    <BookingScreen id={id} title={t('title.review')} testID="review">
      {(b) => {
        if (b.state !== 'completed' && b.state !== 'signed_off') return <Body tone="muted">{t('services.review.onlyPaid')}</Body>;
        return (
          <View style={styles.stack}>
            <Title>{t('services.review.title', { name: who(b) })}</Title>
            <Body tone="muted">{t('services.review.lede', { name: who(b) })}</Body>
            <Notice tone="info" message={t('services.review.notYet', { name: b.providerName })} testID="review-gap" />
            <View style={styles.stars} accessibilityRole="radiogroup" accessibilityLabel={t('services.review.stars')}>
              {[1, 2, 3, 4, 5].map((n) => (
                <Text
                  key={n}
                  accessibilityRole="radio"
                  accessibilityLabel={t('services.stars', { n })}
                  accessibilityState={{ checked: stars === n }}
                  onPress={() => setStars(n)}
                  style={[styles.star, stars >= n && styles.starOn]}
                  testID={`star-${n}`}
                >
                  ★
                </Text>
              ))}
            </View>
            <Kicker>{t('services.review.stoodOut')}</Kicker>
            <View style={styles.chips}>
              {PRAISE.map((k) => (
                <Chip
                  key={k}
                  label={t(`services.praise.${k}`)}
                  selected={praise.has(k)}
                  onPress={() => setPraise((s) => {
                    const next = new Set(s);
                    if (!next.delete(k)) next.add(k);
                    return next;
                  })}
                />
              ))}
            </View>
            <Field label={t('services.review.noteLabel')} placeholder={t('services.review.note')} value={note} onChangeText={setNote} multiline testID="field-review" />
            <Checkbox checked={favourite} onChange={setFavourite} label={t('services.review.favourite', { name: who(b) })} testID="review-favourite">
              <Body tone="small">{t('services.review.favourite', { name: who(b) })}</Body>
            </Checkbox>
            {save.isError ? <Notice message={errorMessage(save.error, t)} /> : null}
            <Button label={t('services.review.submit')} large busy={save.isPending} onPress={() => save.mutate(b)} testID="submit-review" />
          </View>
        );
      }}
    </BookingScreen>
  );
}

function BookingSkeleton() {
  const { t } = useI18n();
  return (
    <View accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.stack} testID="loading">
      <Skeleton height={32} width="80%" />
      <Skeleton height={16} width="60%" />
      <Skeleton height={48} />
      {[0, 1, 2].map((i) => (
        <Skeleton key={i} height={18} />
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  stack: { gap: space[3], paddingTop: space[2] },
  next: { color: colors.neutral800, fontSize: 14 },
  head: { flexDirection: 'row', alignItems: 'flex-start', gap: space[3] },
  person: { flexDirection: 'row', alignItems: 'center', gap: 14 },
  avatar: { width: 48, height: 48, borderRadius: 24, backgroundColor: colors.neutral400 },
  timeline: { gap: 10 },
  step: { flexDirection: 'row', gap: 12 },
  stepAt: { width: 72 },
  now: { color: colors.accent700, fontFamily: fonts.bodyStrong },
  photos: { flexDirection: 'row', gap: space[2] },
  photo: { flex: 1, minHeight: 80, alignItems: 'center', justifyContent: 'center' },
  released: { color: colors.accent900 },
  heldRow: { flexDirection: 'row', justifyContent: 'space-between' },
  actions: { flexDirection: 'row', gap: 10 },
  stars: { flexDirection: 'row', gap: 6 },
  star: { width: 48, height: 48, fontSize: 30, lineHeight: 48, textAlign: 'center', color: colors.neutral300 },
  starOn: { color: colors.accent2 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
});
