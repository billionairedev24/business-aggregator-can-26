import { useMutation, useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { ApiError, MIN_TARGET, colors, fonts, radius, space } from '@northline/mobile-kit';

import type { Calendar, ProviderPage } from '../api/services';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n';
import { useDeliveryLocation } from '../location/DeliveryLocation';
import { Body, Button, Field, Notice, Title, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, errorMessage, QueryView, SignInPrompt, Skeleton } from '../ui/states';
import { useDraft, type BookingDraft } from './draft';
import { dayParts } from './format';
import { Chip, Panel, useServicesApi, WizardSteps, type T } from './parts';

const SPOTS = 4;

/** Where the job happens: the address, the vehicle's spot or the way in, and access instructions (the api's rules). */
export function checkWhere(p: ProviderPage, kind: string | undefined, d: BookingDraft, t: T) {
  const errors: { address?: string; spot?: string; access?: string } = {};
  if (kind === 'appointment') return errors;
  if (!d.address.trim()) errors.address = t('services.err.address');
  if (d.spot === undefined) errors.spot = t('services.err.option');
  if (d.access.trim().length < 3) errors.access = t('services.err.access');
  return errors;
}

/**
 * C5 Choose time (design 01 `book_slot`): the provider's live calendar for the service, in the business's time zone
 * (the api's `timeZone`), then where. "Review booking" holds the slot for 10 minutes (signed in; guests are asked to
 * sign in first, and the wizard's answers wait in memory).
 */
export function BookTime({ slug }: { slug: string }) {
  const { t, locale } = useI18n();
  const api = useServicesApi();
  const lang = locale === 'fr-CA' ? 'fr' : 'en';
  const [draft] = useDraft(slug);
  const page = useQuery({ queryKey: ['services', 'provider', slug, lang], queryFn: () => api.provider(slug, lang), staleTime: 60_000 });
  const serviceId = draft.serviceId;
  const calendar = useQuery({
    queryKey: ['services', 'slots', slug, serviceId],
    queryFn: () => api.slots(slug, serviceId!),
    enabled: !!serviceId,
    staleTime: 15_000,
  });
  return (
    <Screen title={t('title.book_slot')} testID="book-time">
      <WizardSteps at={2} />
      {!serviceId ? (
        <EmptyState message={t('services.book.pickServiceFirst')} action={t('services.book.chooseService')} onAction={() => router.replace(`/book/${slug}/service`)} />
      ) : (
        <QueryView query={page} skeleton={<SlotsSkeleton />}>
          {(p) => (
            <QueryView query={calendar} skeleton={<SlotsSkeleton />}>
              {(cal) => <Form p={p} cal={cal} refetch={() => void calendar.refetch()} />}
            </QueryView>
          )}
        </QueryView>
      )}
    </Screen>
  );
}

function Form({ p, cal, refetch }: { p: ProviderPage; cal: Calendar; refetch: () => void }) {
  const { t, locale, time } = useI18n();
  const { status } = useAuth();
  const { location } = useDeliveryLocation();
  const api = useServicesApi();
  const [draft, update] = useDraft(p.slug);
  const service = p.services.find((s) => s.id === draft.serviceId);
  const firstOpen = cal.days.find((d) => d.slots.some((s) => s.free))?.date ?? cal.days[0]?.date;
  const [dayPicked, setDay] = useState<string | undefined>(() => (draft.startsAt ? cal.days.find((d) => d.slots.some((s) => s.startsAt === draft.startsAt))?.date : undefined) ?? firstOpen);
  const [errors, setErrors] = useState<{ slot?: string; address?: string; spot?: string; access?: string }>({});
  const day = cal.days.find((d) => d.date === dayPicked);

  useEffect(() => {
    // the saved delivery address is the likely place (the person can change it)
    if (!draft.address && location.status === 'saved' && location.label) update({ address: [location.street ?? location.label, location.unit].filter(Boolean).join(', ') });
  }, [draft.address, location, update]);

  const hold = useMutation({
    mutationFn: () => api.hold(p.slug, draft.serviceId!, draft.startsAt!),
    onSuccess: (h) => {
      update({ hold: h });
      router.push(`/book/${p.slug}/review`);
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        update({ startsAt: undefined });
        refetch();
      }
    },
  });

  const next = () => {
    const found = { ...(draft.startsAt ? {} : { slot: t('services.err.slot') }), ...checkWhere(p, service?.kind, draft, t) };
    setErrors(found);
    if (Object.keys(found).length) return;
    hold.mutate();
  };

  const spots = Array.from({ length: SPOTS }, (_, i) => t((p.vehicle ? `services.spot.vehicle${i}` : `services.spot.home${i}`) as 'services.spot.home0'));
  return (
    <View style={styles.form}>
      <Title>{t('services.book.when')}</Title>
      <View style={styles.days} accessibilityRole="radiogroup" accessibilityLabel={t('services.book.days')}>
        {cal.days.map((d) => {
          const parts = dayParts(d.date, locale);
          const on = d.date === dayPicked;
          const free = d.closed ? t('services.book.closed') : d.free === 0 ? t('services.book.full') : t('services.book.slots', { n: d.free });
          return (
            <Pressable
              key={d.date}
              accessibilityRole="radio"
              accessibilityLabel={`${parts.dow} ${parts.num}, ${free}`}
              accessibilityState={{ selected: on, checked: on }}
              onPress={() => setDay(d.date)}
              style={[styles.day, on && styles.dayOn]}
              testID={`day-${d.date}`}
            >
              <Text style={styles.dow}>{parts.dow}</Text>
              <Text style={styles.num}>{parts.num}</Text>
              <Text style={styles.free}>{free}</Text>
            </Pressable>
          );
        })}
      </View>
      <View style={styles.times} accessibilityLabel={t('services.book.times')}>
        {day && day.slots.length > 0 ? (
          day.slots.map((s) => (
            <View key={s.startsAt} style={styles.time}>
              <Chip
                label={time(s.startsAt, cal.timeZone)}
                selected={draft.startsAt === s.startsAt}
                disabled={!s.free}
                hint={s.free ? undefined : t('services.book.taken')}
                onPress={() => {
                  update({ startsAt: s.startsAt });
                  setErrors((e) => ({ ...e, slot: undefined }));
                }}
                testID={`slot-${s.startsAt}`}
              />
            </View>
          ))
        ) : (
          <Body tone="muted">{t('services.book.noTimes')}</Body>
        )}
      </View>
      {errors.slot ? <Text accessibilityRole="alert" style={type.error}>{errors.slot}</Text> : null}
      <Panel>
        <Body tone="small">{t('services.book.instant', { name: p.name })}</Body>
      </Panel>
      {service?.kind === 'appointment' ? null : (
        <>
          <Field label={t('services.book.where')} value={draft.address} onChangeText={(v) => update({ address: v })} error={errors.address} autoComplete="street-address" testID="field-address" />
          <Text style={styles.label}>{p.vehicle ? t('services.spot.vehicle') : t('services.spot.home')}</Text>
          <View style={styles.spots} accessibilityRole="radiogroup">
            {spots.map((label, i) => (
              <Chip key={label} label={label} selected={draft.spot === i} onPress={() => update({ spot: i })} testID={`spot-${i}`} />
            ))}
          </View>
          {errors.spot ? <Text accessibilityRole="alert" style={type.error}>{errors.spot}</Text> : null}
          <Field
            label={t('services.book.access')}
            placeholder={t('services.book.accessPlaceholder')}
            hint={t('services.book.accessPrivate')}
            value={draft.access}
            onChangeText={(v) => update({ access: v })}
            error={errors.access}
            testID="field-access"
          />
          <Body tone="small">{t('services.book.accessPrivate')}</Body>
        </>
      )}
      {hold.isError ? <Notice message={errorMessage(hold.error, t)} testID="hold-error" /> : null}
      {status === 'signedIn' ? (
        <Button label={t('services.book.reviewBooking')} large busy={hold.isPending} onPress={next} testID="book-next" />
      ) : (
        <SignInPrompt message={t('services.book.signInToHold')} />
      )}
    </View>
  );
}

function SlotsSkeleton() {
  const { t } = useI18n();
  return (
    <View accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.form} testID="loading">
      <Skeleton height={28} width="60%" />
      <View style={styles.days}>
        {[0, 1, 2, 3, 4].map((i) => (
          <View key={i} style={styles.flex}>
            <Skeleton height={64} />
          </View>
        ))}
      </View>
      <Skeleton height={100} />
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  form: { gap: space[3], paddingTop: space[3] },
  days: { flexDirection: 'row', gap: 6 },
  day: { flex: 1, minHeight: MIN_TARGET, paddingVertical: space[2], paddingHorizontal: 6, borderRadius: radius.md, borderWidth: 1, borderColor: colors.divider },
  dayOn: { borderColor: colors.accent, backgroundColor: colors.accent100 },
  dow: { fontFamily: fonts.body, fontSize: 11, letterSpacing: 0.6, textTransform: 'uppercase', color: colors.neutral700 },
  num: { fontFamily: fonts.bodyStrong, fontSize: 20, color: colors.text },
  free: { fontFamily: fonts.body, fontSize: 10, color: colors.neutral700 },
  times: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
  time: { width: '31%' },
  label: { fontFamily: fonts.body, fontSize: 13, color: colors.neutral800 },
  spots: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
});
