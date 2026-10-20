import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { StyleSheet, Text, View } from 'react-native';

import { Icon, colors, fonts, radius, semantic, shadow, space, Scene, slugSlot } from '@northline/mobile-kit';

import { nameIn, type ProviderPage, type ProviderService, type Review } from '../api/services';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n';
import { Body, Button, Link, Notice, Section, Tag, type } from '../ui/primitives';
import { GUTTER, Header, RoundButton, Screen } from '../ui/screen';
import { errorMessage, QueryView, Skeleton } from '../ui/states';
import { updateDraft } from './draft';
import { price } from './format';
import { brand, FAVOURITES_KEY, favouritesChanged, Mark, Row, useServicesApi } from './parts';

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
 * C3 Provider profile (design 01 `provider`; light & photo-first): the cover first, edge to edge (the category's drawn
 * scene until providers upload covers), Back over it, the business's mark over the cover's edge ringed in its brand
 * colour, the name in the display face and the verified seal; the verified figures as stat cards (rating, on time,
 * disputes, re-book), credentials, the services as cards with their prices, review cards with stars (more on request),
 * "Book a visit" and favourites (signed in) in the sticky bar. Guests may look.
 */
export function Provider({ slug }: { slug: string }) {
  const { t, locale } = useI18n();
  const api = useServicesApi();
  const lang = locale === 'fr-CA' ? 'fr' : 'en';
  const page = useQuery({ queryKey: ['services', 'provider', slug, lang], queryFn: () => api.provider(slug, lang), staleTime: 60_000 });
  return (
    <Screen
      testID="provider"
      padded={false}
      top={page.data ? undefined : <Header title={t('title.provider')} />}
      footer={page.data ? <Actions p={page.data} /> : undefined}
    >
      <QueryView query={page} skeleton={<ProfileSkeleton />}>
        {(p) => <Profile p={p} />}
      </QueryView>
    </Screen>
  );
}

function Profile({ p }: { p: ProviderPage }) {
  const { t, locale } = useI18n();
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
      <View testID="provider-hero">
        <Scene slot={[slugSlot('service', p.category?.slug), 'banner/services']} subject={p.category?.slug ?? p.name} fit="contain" style={styles.cover} />
        <View style={styles.overlay}>
          <RoundButton icon="caret-left" label={t('common.back')} onPress={() => (router.canGoBack() ? router.back() : router.replace('/home'))} onPhoto testID="header-back" />
        </View>
        <View style={[styles.avatar, { borderColor: brand(null) }]}>
          <Mark name={p.name} color={colors.surface} ink={colors.text} size={64} />
        </View>
      </View>
      <View style={styles.pad}>
      <View style={styles.idBlock}>
        <Text accessibilityRole="header" style={styles.heroName}>
          {p.name}
        </Text>
        <Text style={type.small}>{line}</Text>
        {p.tier === 'master' || p.tier === 'trusted' ? (
          <View style={styles.seal}>
            <Icon name="seal-check" filled size={16} color={semantic.success} />
            <Text style={styles.sealText}>{t('rd.provider.verified')}</Text>
          </View>
        ) : null}
      </View>
      {figures.length > 0 ? (
        <View style={styles.figures}>
          {figures.map((f) => (
            <View key={f.label} style={styles.figureCard} accessible accessibilityLabel={`${f.value} ${f.label}`}>
              <Text style={styles.figure}>{f.value}</Text>
              <Text style={type.small} numberOfLines={2}>
                {f.label}
              </Text>
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
      </View>
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
          <View style={styles.stars}>
            {[1, 2, 3, 4, 5].map((n) => (
              <Icon key={n} name="star" size={14} filled={n <= r.rating} color={n <= r.rating ? colors.rating : colors.borderStrong} />
            ))}
          </View>
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
  const favourites = useQuery({ queryKey: FAVOURITES_KEY, queryFn: () => api.favourites(), enabled: signedIn, staleTime: 60_000 });
  const isFavourite = !!favourites.data?.items.some((f) => f.merchantId === p.merchantId);
  const toggle = useMutation({
    mutationFn: (on: boolean) => api.favourite(p.merchantId, on),
    onSuccess: () => favouritesChanged(qc),
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
    <View accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={[styles.reviews, styles.pad]} testID="loading">
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
  flex: { flex: 1, minWidth: 0, gap: 2 },
  pad: { paddingHorizontal: GUTTER, gap: space[4] },
  cover: { height: 200, marginTop: -space[3] },
  overlay: { position: 'absolute', top: 0, left: GUTTER, right: GUTTER, flexDirection: 'row' },
  avatar: { position: 'absolute', left: GUTTER, bottom: -36, width: 76, height: 76, borderRadius: radius.lg + 4, borderWidth: 3, backgroundColor: colors.surface, alignItems: 'center', justifyContent: 'center', overflow: 'hidden', ...shadow.md },
  idBlock: { gap: 4, paddingTop: 44 },
  seal: { flexDirection: 'row', alignItems: 'center', gap: 4, alignSelf: 'flex-start', paddingHorizontal: 10, paddingVertical: 4, marginTop: 4, borderRadius: radius.pill, backgroundColor: semantic['success-bg'] },
  sealText: { fontFamily: fonts.semibold, fontSize: 13, color: semantic.success },
  heroName: { fontFamily: fonts.display, fontSize: 28, lineHeight: 34, color: colors.text },
  figures: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
  figureCard: { flexGrow: 1, flexBasis: '45%', gap: 2, padding: space[3], borderRadius: radius.lg, borderWidth: 1, borderColor: semantic['border-card'], backgroundColor: colors.surface },
  figure: { fontFamily: fonts.display, fontSize: 22, color: colors.text },
  stars: { flexDirection: 'row', gap: 2 },
  tags: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  sectionHead: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', paddingTop: space[2] },
  menuRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: space[3] },
  reviews: { gap: space[3], paddingTop: space[2] },
  review: { gap: 6, padding: space[4], borderRadius: radius.lg, backgroundColor: semantic['surface-sunken'] },
  quote: {},
  actions: { flexDirection: 'row', gap: 10 },
});
