import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { StyleSheet, Text, View } from 'react-native';

import { colors, fonts, space } from '@northline/mobile-kit';

import { nameIn, type ProviderPage, type ProviderService, type Review } from '../api/services';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n';
import { Body, Button, Link, Notice, Section, Tag, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { errorMessage, QueryView, Skeleton } from '../ui/states';
import { updateDraft } from './draft';
import { price } from './format';
import { Mark, Row, useServicesApi, useTier } from './parts';

/** "45 min" / "1 h 30" — a service's length. */
export function duration(min: number, t: (k: 'services.minutes' | 'services.hours', p: Record<string, number>) => string) {
  return min < 60 ? t('services.minutes', { n: min }) : t('services.hours', { h: Math.floor(min / 60), m: min % 60 });
}

/** A service's price as the menu shows it: "$89", "$60/h", "Quote". */
export function servicePrice(s: ProviderService, locale: 'en' | 'fr-CA', t: (k: 'services.perHour' | 'services.quote', p?: Record<string, string>) => string) {
  if (s.pricingMode === 'quote' || s.priceCents == null) return t('services.quote');
  return s.pricingMode === 'hourly' ? t('services.perHour', { price: price(s.priceCents, locale) }) : price(s.priceCents, locale);
}

/**
 * C3 Provider profile (design 01 `provider`): the provider-branded hero, Northline-verified figures (rating, on time,
 * disputes, re-book), credentials, the services with fixed prices, real reviews (more on request), "Book a visit" and
 * favourites (signed in). Guests may look.
 */
export function Provider({ slug }: { slug: string }) {
  const { t, locale } = useI18n();
  const api = useServicesApi();
  const lang = locale === 'fr-CA' ? 'fr' : 'en';
  const page = useQuery({ queryKey: ['services', 'provider', slug, lang], queryFn: () => api.provider(slug, lang), staleTime: 60_000 });
  return (
    <Screen title={page.data?.name ?? t('title.provider')} testID="provider" footer={page.data ? <Actions p={page.data} /> : undefined}>
      <QueryView query={page} skeleton={<ProfileSkeleton />}>
        {(p) => <Profile p={p} />}
      </QueryView>
    </Screen>
  );
}

function Profile({ p }: { p: ProviderPage }) {
  const { t, locale } = useI18n();
  const tier = useTier()(p.tier);
  const category = p.category ? nameIn(p.category.names, locale) : null;
  const since = new Date(p.since).getUTCFullYear();
  const line = [category, p.city, t('services.provider.since', { year: since })].filter(Boolean).join(' · ');
  const figures = [
    p.reviewCount > 0 ? { value: p.rating.toFixed(1), label: t('services.provider.verified', { n: p.reviewCount }) } : null,
    p.onTimePct != null ? { value: `${Math.round(p.onTimePct)}%`, label: t('services.provider.onTime') } : null,
    p.disputePct != null ? { value: `${p.disputePct}%`, label: t('services.provider.disputes') } : null,
    p.rebookPct != null ? { value: `${Math.round(p.rebookPct)}%`, label: t('services.provider.rebook') } : null,
  ].filter((f): f is { value: string; label: string } => !!f);
  const book = (s: ProviderService) => {
    updateDraft(p.slug, { serviceId: s.id, quote: s.pricingMode === 'quote' || !s.instantBook });
    router.push(`/book/${p.slug}/service`);
  };
  return (
    <>
      <View style={styles.hero} testID="provider-hero">
        <View style={styles.heroTop}>
          <View style={styles.heroMark}>
            <Mark name={p.name} color={colors.surface} ink={colors.text} />
          </View>
          <View style={styles.heroTag}>
            <Text style={styles.heroTagText}>{t('services.provider.tier', { tier: tier.label })}</Text>
          </View>
        </View>
        <Text accessibilityRole="header" style={styles.heroName}>{p.name}</Text>
        <Text style={styles.heroLine}>{line}</Text>
      </View>
      {figures.length > 0 ? (
        <View style={styles.figures}>
          {figures.map((f) => (
            <View key={f.label} accessible accessibilityLabel={`${f.value} ${f.label}`}>
              <Text style={styles.figure}>{f.value}</Text>
              <Text style={type.small}>{f.label}</Text>
            </View>
          ))}
        </View>
      ) : (
        <Body tone="small">{t('services.newProvider')}</Body>
      )}
      <View style={styles.tags}>
        {p.verifiedFacts.map((f) => (
          <Tag key={f} label={f} tone="accent" />
        ))}
        {p.services.some((s) => s.instantBook) ? <Tag label={t('services.instantBook')} /> : null}
      </View>
      <View style={styles.sectionHead}>
        <Section>{t('services.provider.menu')}</Section>
        <Body tone="small">{t('services.provider.inclTravel')}</Body>
      </View>
      {p.services.length === 0 ? <Body tone="muted">{t('services.provider.noServices')}</Body> : null}
      {p.services.map((s) => (
        <Row key={s.id} onPress={() => book(s)} label={`${s.name}, ${duration(s.durationMin, t)}, ${servicePrice(s, locale, t)}`} testID={`menu-${s.id}`}>
          <View style={styles.menuRow}>
            <View style={styles.flex}>
              <Text style={type.body}>{s.name}</Text>
              <Text style={type.small}>{duration(s.durationMin, t)}</Text>
            </View>
            <Text style={[type.body, type.strong]}>{servicePrice(s, locale, t)}</Text>
          </View>
        </Row>
      ))}
      <Reviews p={p} />
    </>
  );
}

function Reviews({ p }: { p: ProviderPage }) {
  const { t, day } = useI18n();
  const api = useServicesApi();
  const more = useInfiniteQuery({
    queryKey: ['services', 'reviews', p.slug],
    queryFn: ({ pageParam }) => api.reviews(p.slug, pageParam),
    initialPageParam: p.reviews.nextOffset ?? 0,
    getNextPageParam: (last) => last.nextOffset ?? undefined,
    enabled: false,
  });
  const items: Review[] = [...p.reviews.items, ...(more.data?.pages.flatMap((pg) => pg.items) ?? [])];
  const hasMore = more.data ? more.hasNextPage : p.reviews.nextOffset != null;
  return (
    <View style={styles.reviews}>
      <Section>{t('services.provider.reviews', { n: p.reviewCount })}</Section>
      {items.length === 0 ? <Body tone="muted">{t('services.provider.noReviews')}</Body> : null}
      {items.map((r) => (
        <View key={r.id} style={styles.review} accessible>
          {r.text ? <Text style={[type.body, styles.quote]}>“{r.text}”</Text> : null}
          <Text style={type.small}>
            {[r.author, t(r.refType === 'order' ? 'services.provider.verifiedOrder' : 'services.provider.verifiedBooking'), day(r.createdAt, p.timeZone), t('services.stars', { n: r.rating })]
              .filter(Boolean)
              .join(' · ')}
          </Text>
        </View>
      ))}
      {more.isError ? <Notice message={errorMessage(more.error, t)} /> : null}
      {hasMore ? (
        <Link label={t('services.provider.moreReviews')} onPress={() => void (more.data ? more.fetchNextPage() : more.refetch())} testID="more-reviews" />
      ) : null}
    </View>
  );
}

function Actions({ p }: { p: ProviderPage }) {
  const { t } = useI18n();
  const { status } = useAuth();
  const api = useServicesApi();
  const qc = useQueryClient();
  const signedIn = status === 'signedIn';
  const favourites = useQuery({ queryKey: ['services', 'favourites'], queryFn: () => api.favourites(), enabled: signedIn, staleTime: 60_000 });
  const isFavourite = !!favourites.data?.items.some((f) => f.merchantId === p.merchantId);
  const toggle = useMutation({
    mutationFn: (on: boolean) => api.favourite(p.merchantId, on),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['services', 'favourites'] }),
  });
  const bookable = p.services.length > 0;
  return (
    <>
      {toggle.isError ? <Notice message={errorMessage(toggle.error, t)} /> : null}
      <View style={styles.actions}>
        <Button
          label={t('services.provider.book')}
          large
          disabled={!bookable}
          style={styles.flex}
          onPress={() => {
            const first = p.services.find((s) => s.instantBook && s.pricingMode !== 'quote') ?? p.services[0];
            updateDraft(p.slug, { serviceId: first?.id, quote: !!first && (first.pricingMode === 'quote' || !first.instantBook) });
            router.push(`/book/${p.slug}/service`);
          }}
          testID="book-visit"
        />
        <Button
          label={isFavourite ? t('services.provider.favourited') : t('services.provider.favourite')}
          tone="secondary"
          large
          busy={toggle.isPending}
          hint={signedIn ? undefined : t('services.signInFirst')}
          onPress={() => (signedIn ? toggle.mutate(!isFavourite) : router.push('/sign-in'))}
          testID="favourite"
        />
      </View>
    </>
  );
}

function ProfileSkeleton() {
  const { t } = useI18n();
  return (
    <View accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.reviews} testID="loading">
      <Skeleton height={150} />
      <Skeleton height={40} width="80%" />
      <Skeleton height={16} width="60%" />
      {[0, 1, 2].map((i) => (
        <Skeleton key={i} height={44} />
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  hero: { marginHorizontal: -24, marginTop: -space[4], paddingHorizontal: 24, paddingTop: 22, paddingBottom: 24, backgroundColor: colors.accent, gap: 2 },
  heroTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: space[4] },
  heroMark: { borderRadius: 12, overflow: 'hidden' },
  heroTag: { backgroundColor: colors.surface, borderRadius: 999, paddingHorizontal: 10, paddingVertical: 3 },
  heroTagText: { fontFamily: fonts.bodyStrong, fontSize: 12, color: colors.text },
  heroName: { fontFamily: fonts.heading, fontSize: 28, lineHeight: 32, color: colors.onAccent },
  heroLine: { fontFamily: fonts.body, fontSize: 14, color: colors.accent100 },
  figures: { flexDirection: 'row', flexWrap: 'wrap', gap: 22 },
  figure: { fontFamily: fonts.bodyStrong, fontSize: 20, color: colors.text },
  tags: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  sectionHead: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', paddingTop: space[2] },
  menuRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: space[3] },
  reviews: { gap: space[3], paddingTop: space[2] },
  review: { gap: 4 },
  quote: { fontStyle: 'italic' },
  actions: { flexDirection: 'row', gap: 10 },
});
