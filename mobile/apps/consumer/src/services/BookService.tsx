import { useMutation, useQuery } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { router } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { ApiError, space } from '@northline/mobile-kit';

import type { ProviderPage, ProviderService } from '../api/services';
import { useAuth } from '../auth/AuthProvider';
import { config } from '../config';
import { useI18n } from '../i18n';
import { useDeliveryLocation } from '../location/DeliveryLocation';
import { Body, Button, Field, Link, Notice, Option, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { errorMessage, QueryView, SignInPrompt, Skeleton } from '../ui/states';
import { parseVehicle, useDraft } from './draft';
import { Panel, useServicesApi, WizardSteps, type T } from './parts';
import { servicePrice } from './Provider';

/** Kinds the app books itself: a visit or an appointment at a fixed price (hours, events and consultations: the web). */
export const appBookable = (s: ProviderService) => s.instantBook && s.pricingMode === 'fixed' && (s.kind === 'visit' || s.kind === 'appointment');

/** The rules the api applies to the first step, with its messages (docs/spec/validation-messages.fr-CA.tsv). */
export function checkDetails(p: ProviderPage, s: ProviderService | undefined, d: { vehicle: string; note: string; quote: boolean }, t: T) {
  const errors: { vehicle?: string; note?: string } = {};
  const needsNote = d.quote || s?.kind === 'visit';
  if (needsNote && d.note.trim().length < 10) errors.note = t(d.quote ? 'services.err.describeJob' : 'services.err.describe');
  if (p.vehicle && (d.quote || s?.kind === 'visit') && !parseVehicle(d.vehicle)) errors.vehicle = t('services.err.vehicle');
  return errors;
}

/**
 * C4 Choose service (design 01 `book_service`): the fixed-price menu, the vehicle (automotive) and a note for the
 * provider — or "Ask for a quote" (quoteable categories), which sends a quote request to this provider (signed in).
 * Services the app can't book itself (hourly, events, consultations) open the provider's page on the consumer site.
 */
export function BookService({ slug }: { slug: string }) {
  const { t, locale } = useI18n();
  const api = useServicesApi();
  const lang = locale === 'fr-CA' ? 'fr' : 'en';
  const page = useQuery({ queryKey: ['services', 'provider', slug, lang], queryFn: () => api.provider(slug, lang), staleTime: 60_000 });
  return (
    <Screen title={t('title.book_service')} testID="book-service">
      <WizardSteps at={1} />
      <QueryView query={page} skeleton={<FormSkeleton />}>
        {(p) => <Form p={p} />}
      </QueryView>
    </Screen>
  );
}

function Form({ p }: { p: ProviderPage }) {
  const { t, locale, time, day } = useI18n();
  const { status } = useAuth();
  const { location } = useDeliveryLocation();
  const api = useServicesApi();
  const [draft, update] = useDraft(p.slug);
  const [errors, setErrors] = useState<{ vehicle?: string; note?: string }>({});
  const chosen = p.services.find((s) => s.id === draft.serviceId) ?? p.services.find(appBookable) ?? p.services[0];
  const quoteMode = draft.quote && p.quoteable && !!p.category;
  const bookable = !!chosen && appBookable(chosen);

  const ask = useMutation({
    mutationFn: () => {
      const v = parseVehicle(draft.vehicle);
      return api.askQuote({
        category: p.category!.slug,
        providers: [p.slug],
        description: [chosen && chosen.pricingMode === 'quote' ? chosen.name : null, draft.note.trim()].filter(Boolean).join(' — '),
        ...(p.vehicle && v ? { vehicle: { year: v.year, make: v.make, model: v.model } } : {}),
        ...(location.zone ? { area: location.zone } : {}),
      });
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 422) setErrors({ note: e.fieldMessage('description'), vehicle: e.fieldMessage('vehicle') });
    },
  });

  const next = () => {
    const found = checkDetails(p, chosen, { ...draft, quote: quoteMode }, t);
    setErrors(found);
    if (Object.keys(found).length) return;
    if (quoteMode) return ask.mutate();
    update({ serviceId: chosen!.id });
    router.push(`/book/${p.slug}/time`);
  };

  if (ask.data) {
    return (
      <View style={styles.form} testID="quote-sent">
        <Title>{t('services.quote.sentTitle')}</Title>
        <Body tone="muted">{t('services.quote.sentBody', { ref: ask.data.ref, name: p.name, when: `${day(ask.data.respondBy, p.timeZone)} ${time(ask.data.respondBy, p.timeZone)}` })}</Body>
        <Button label={t('services.quote.seeOrders')} large onPress={() => router.replace('/orders')} />
        <Button label={t('services.done')} tone="ghost" onPress={() => router.replace('/services')} />
      </View>
    );
  }

  const title = quoteMode ? t('services.quote.title') : p.vehicle ? t('services.book.whatCar') : t('services.book.what');
  const generic = (e: unknown) => (e instanceof ApiError && e.status === 422 ? null : errorMessage(e, t));
  return (
    <View style={styles.form}>
      <Title>{title}</Title>
      <View style={styles.options} accessibilityRole="radiogroup">
        {p.services.map((s) => (
          <Option
            key={s.id}
            selected={s.id === chosen?.id}
            title={s.name}
            description={s.included ?? undefined}
            tag={servicePrice(s, locale, t)}
            onPress={() => {
              update({ serviceId: s.id, quote: s.pricingMode === 'quote' || !s.instantBook ? p.quoteable : draft.quote });
              setErrors({});
            }}
            testID={`service-${s.id}`}
          />
        ))}
      </View>
      {p.vehicle ? (
        <Field
          label={t('services.book.vehicle')}
          placeholder={t('services.book.vehiclePlaceholder')}
          value={draft.vehicle}
          onChangeText={(v) => update({ vehicle: v })}
          error={errors.vehicle}
          autoCapitalize="words"
          testID="field-vehicle"
        />
      ) : null}
      <Field
        label={t('services.book.note', { name: p.name })}
        placeholder={t(p.vehicle ? 'services.book.notePlaceholderCar' : 'services.book.notePlaceholder')}
        value={draft.note}
        onChangeText={(v) => update({ note: v })}
        error={errors.note}
        multiline
        testID="field-note"
      />
      {chosen && !bookable && !quoteMode ? (
        <Panel>
          <Body>{t('services.book.onWeb', { service: chosen.name })}</Body>
          <Link label={`${t('services.book.openWeb')} ${t('common.opensInBrowser')}`} onPress={() => void Linking.openURL(`${config.siteOrigin}/providers/${p.slug}/book`)} testID="book-on-web" />
        </Panel>
      ) : null}
      {p.quoteable && p.category ? (
        quoteMode ? (
          <Link label={t('services.quote.back')} onPress={() => update({ quote: false })} testID="book-instead" />
        ) : (
          <View style={styles.inline}>
            <Body tone="small">{t('services.book.notSure')}</Body>
            <Link label={t('services.book.askQuote')} hint={t('services.book.askQuoteHint')} onPress={() => update({ quote: true })} testID="ask-quote" />
          </View>
        )
      ) : null}
      {ask.isError && generic(ask.error) ? <Notice message={generic(ask.error)!} /> : null}
      {quoteMode && status !== 'signedIn' ? (
        <SignInPrompt message={t('services.quote.signIn')} />
      ) : (
        <Button
          label={quoteMode ? t('services.quote.send') : t('services.book.chooseTime')}
          large
          busy={ask.isPending}
          disabled={!quoteMode && !bookable}
          onPress={next}
          testID="book-next"
        />
      )}
    </View>
  );
}

function FormSkeleton() {
  const { t } = useI18n();
  return (
    <View accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.form} testID="loading">
      <Skeleton height={28} width="70%" />
      {[0, 1, 2].map((i) => (
        <Skeleton key={i} height={60} />
      ))}
      <Skeleton height={48} />
    </View>
  );
}

const styles = StyleSheet.create({
  form: { gap: space[3], paddingTop: space[3] },
  options: { gap: space[2] },
  inline: { gap: 0 },
});
