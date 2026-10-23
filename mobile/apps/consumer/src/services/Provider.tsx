import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useRef, useState } from 'react';
import { StyleSheet, Text, View, type ScrollView } from 'react-native';

import {
  Button as KitButton,
  Icon,
  IconButton,
  LINE,
  OptionCard,
  Photo,
  PhotoWell,
  Price,
  Rating,
  SIZE,
  StatusTag,
  Stars,
  TYPE,
  UnderlineTabs,
  photoFor,
  radius,
  semantic,
  slugSlot,
  space,
  textStyles,
} from '@northline/mobile-kit';

import { nameIn, type ProviderPage, type ProviderService, type Review } from '../api/services';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n';
import { Body, Link, Notice } from '../ui/primitives';
import { iconFor } from '../ui/commerce';
import { Screen } from '../ui/screen';
import { errorMessage, QueryView, Skeleton } from '../ui/states';
import { updateDraft } from './draft';
import { price } from './format';
import { FAVOURITES_KEY, favouritesChanged, useServicesApi } from './parts';

/** "45 min" / "1 h 30" — a service's length. */
export function duration(min: number, t: (k: 'services.minutes' | 'services.hours', p: Record<string, number>) => string) {
  return min < 60 ? t('services.minutes', { n: min }) : t('services.hours', { h: Math.floor(min / 60), m: min % 60 });
}

/** A service's price as the menu says it: "$89.00", "$60.00/h", "Quote". */
export function servicePrice(s: ProviderService, locale: 'en' | 'fr-CA', t: (k: 'services.perHour' | 'services.quote', p?: Record<string, string>) => string) {
  if (s.pricingMode === 'quote' || s.priceCents == null) return t('services.quote');
  return s.pricingMode === 'hourly' ? t('services.perHour', { price: price(s.priceCents, locale) }) : price(s.priceCents, locale);
}

/** The service a visit starts with: the first instant-book fixed price, else the first. */
const firstService = (p: ProviderPage) => p.services.find((s) => s.instantBook && s.pricingMode !== 'quote') ?? p.services[0];

type Section = 'services' | 'reviews';

/**
 * C3 Provider (E-15 A · Plainsight, design/revamp/a.html "Service provider with booking" on a phone): the provider's
 * photo in the grey well beside the caps line (what they do · where), the name, who they are, the rating and the
 * verified figures, the credentials as success tags; the underline tabs (Services · Reviews, with counts) scroll to
 * their section; "Choose a service" as option cards (a radio mark, the price on the right, the chosen one an ink
 * edge); how paying works; the reviews (more on request). The sticky bar keeps the chosen service's price and Book in
 * reach; favourites (signed in) are the heart in the header. Guests may look.
 */
export function Provider({ slug }: { slug: string }) {
  const { t } = useI18n();
  const api = useServicesApi();
  const lang = useI18n().locale === 'fr-CA' ? 'fr' : 'en';
  const page = useQuery({ queryKey: ['services', 'provider', slug, lang], queryFn: () => api.provider(slug, lang), staleTime: 60_000 });
  const [chosenId, setChosenId] = useState<string>();
  const scroll = useRef<ScrollView>(null);
  const chosen = page.data ? (page.data.services.find((s) => s.id === chosenId) ?? firstService(page.data)) : undefined;
  return (
    <Screen
      testID="provider"
      title={t('title.provider')}
      action={page.data ? <Favourite p={page.data} /> : undefined}
      scrollRef={scroll}
      footer={page.data ? <BookBar p={page.data} service={chosen} /> : undefined}
    >
      <QueryView query={page} skeleton={<ProfileSkeleton />}>
        {(p) => <Profile p={p} chosen={chosen} onChoose={setChosenId} scroll={scroll} />}
      </QueryView>
    </Screen>
  );
}

function Profile({ p, chosen, onChoose, scroll }: { p: ProviderPage; chosen?: ProviderService; onChoose: (id: string) => void; scroll: React.RefObject<ScrollView | null> }) {
  const { t, locale } = useI18n();
  const category = p.category ? nameIn(p.category.names, locale) : null;
  const since = new Date(p.since).getUTCFullYear();
  const kicker = [category, p.city].filter(Boolean).join(' · ');
  const figures = [
    p.onTimePct != null ? t('rv.provider.onTime', { pct: Math.round(p.onTimePct) }) : null,
    p.disputePct != null ? t('rv.provider.disputes', { pct: p.disputePct }) : null,
    p.rebookPct != null ? t('rv.provider.rebook', { pct: Math.round(p.rebookPct) }) : null,
  ].filter(Boolean);
  const photo = photoFor([slugSlot('service', p.category?.slug), 'banner/services'], 'square');
  const [section, setSection] = useState<Section>('services');
  const at = useRef<Record<Section, number>>({ services: 0, reviews: 0 });
  const go = (s: Section) => {
    setSection(s);
    scroll.current?.scrollTo({ y: Math.max(0, at.current[s] - space[2]), animated: true });
  };
  return (
    <>
      <View style={styles.id} testID="provider-hero">
        <View style={styles.pic}>{photo ? <Photo slot={[]} photo={photo} crop="square" style={StyleSheet.absoluteFill} /> : <PhotoWell icon={iconFor('service')} inset={0} />}</View>
        <View style={styles.idText}>
          {kicker ? <Text style={textStyles.caps}>{kicker}</Text> : null}
          <Text accessibilityRole="header" style={textStyles.pageTitle}>
            {p.name}
          </Text>
          <Text style={textStyles.meta}>{[category, p.city, t('services.provider.since', { year: since })].filter(Boolean).join(' · ')}</Text>
          {p.reviewCount > 0 ? <Rating value={p.rating} count={p.reviewCount} /> : <Body tone="small">{t('services.newProvider')}</Body>}
          {figures.length ? <Text style={textStyles.meta}>{figures.join(' · ')}</Text> : null}
        </View>
      </View>
      <View style={styles.tags}>
        {p.tier === 'master' || p.tier === 'trusted' ? <StatusTag label={t('rd.provider.verified')} tone="success" /> : null}
        {p.verifiedFacts.map((f) => (
          <StatusTag key={f} label={f} tone="success" />
        ))}
        {p.services.some((s) => s.instantBook) ? <StatusTag label={t('services.instantBook')} tone="info" /> : null}
      </View>

      <View style={styles.tabs}>
        <UnderlineTabs
          label={t('rv.provider.sections')}
          value={section}
          onChange={go}
          options={[
            { value: 'services', label: t('rv.provider.tabServices'), count: p.services.length },
            { value: 'reviews', label: t('rv.provider.tabReviews'), count: p.reviewCount },
          ]}
          testID="provider-tab"
        />
      </View>

      <View style={styles.section} onLayout={(e) => (at.current.services = e.nativeEvent.layout.y)}>
        <View style={styles.sectionHead}>
          <Text accessibilityRole="header" style={textStyles.section}>
            {t('rv.provider.choose')}
          </Text>
          <Text style={textStyles.meta}>{t('services.provider.inclTravel')}</Text>
        </View>
        {p.services.length === 0 ? <Body tone="muted">{t('services.provider.noServices')}</Body> : null}
        <View style={styles.options} accessibilityRole="radiogroup" accessibilityLabel={t('rv.provider.choose')}>
          {p.services.map((s) => (
            <OptionCard
              key={s.id}
              title={s.name}
              description={[duration(s.durationMin, t), s.included].filter(Boolean).join(' · ')}
              right={<Text style={textStyles.bodyStrong}>{servicePrice(s, locale, t)}</Text>}
              label={`${s.name}, ${duration(s.durationMin, t)}, ${servicePrice(s, locale, t)}`}
              selected={s.id === chosen?.id}
              onPress={() => onChoose(s.id)}
              testID={`menu-${s.id}`}
            />
          ))}
        </View>
        {chosen ? (
          <View style={styles.how}>
            <View style={styles.howLine}>
              <Icon name="wrench" size={TYPE.price} />
              <View style={styles.flex}>
                <Text style={textStyles.bodyStrong}>{chosen.name}</Text>
                <Text style={textStyles.meta}>{[duration(chosen.durationMin, t), servicePrice(chosen, locale, t)].join(' · ')}</Text>
              </View>
            </View>
            <View style={styles.howLine}>
              <Icon name="lock-simple" size={TYPE.sub} color={semantic['text-muted']} />
              <Text style={[textStyles.meta, styles.flex]}>{t('rv.provider.noCharge')}</Text>
            </View>
          </View>
        ) : null}
      </View>

      <View style={styles.section} onLayout={(e) => (at.current.reviews = e.nativeEvent.layout.y)}>
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
    <>
      <Text accessibilityRole="header" style={textStyles.section}>
        {t('services.provider.reviews', { n: p.reviewCount })}
      </Text>
      {items.length === 0 ? <Body tone="muted">{t('services.provider.noReviews')}</Body> : null}
      {items.map((r) => (
        <View key={r.id} style={styles.review} accessible>
          <Stars value={r.rating} />
          {r.text ? <Text style={textStyles.body}>“{r.text}”</Text> : null}
          <Text style={textStyles.meta}>
            {[r.author, t(r.refType === 'order' ? 'services.provider.verifiedOrder' : 'services.provider.verifiedBooking'), day(r.createdAt, p.timeZone), t('services.stars', { n: r.rating })].filter(Boolean).join(' · ')}
          </Text>
        </View>
      ))}
      {more.isError ? <Notice message={errorMessage(more.error, t)} /> : null}
      {hasMore ? <Link label={t('services.provider.moreReviews')} onPress={() => void (more.data ? more.fetchNextPage() : more.refetch())} testID="more-reviews" /> : null}
    </>
  );
}

/** The heart in the header: a guest is asked to sign in first; signed in, it adds or removes the favourite. */
function Favourite({ p }: { p: ProviderPage }) {
  const { t } = useI18n();
  const { status } = useAuth();
  const api = useServicesApi();
  const qc = useQueryClient();
  const signedIn = status === 'signedIn';
  const favourites = useQuery({ queryKey: FAVOURITES_KEY, queryFn: () => api.favourites(), enabled: signedIn, staleTime: 60_000 });
  const isFavourite = !!favourites.data?.items.some((f) => f.merchantId === p.merchantId);
  const toggle = useMutation({ mutationFn: (on: boolean) => api.favourite(p.merchantId, on), onSuccess: () => favouritesChanged(qc) });
  return (
    <IconButton
      icon="heart"
      filled={isFavourite}
      color={isFavourite ? semantic.commerce : undefined}
      boxed
      label={isFavourite ? t('services.provider.favourited') : t('services.provider.favourite')}
      hint={signedIn ? (toggle.isError ? errorMessage(toggle.error, t) : undefined) : t('services.signInFirst')}
      onPress={() => (signedIn ? toggle.mutate(!isFavourite) : router.push('/sign-in'))}
      testID="favourite"
    />
  );
}

/** The sticky bar: the chosen service's price (or "Quote") and its length, then Book (a quote request for quoted work). */
function BookBar({ p, service }: { p: ProviderPage; service?: ProviderService }) {
  const { t } = useI18n();
  const quote = !!service && (service.pricingMode === 'quote' || !service.instantBook);
  return (
    <View style={styles.bar}>
      <View style={styles.flex}>
        {service && service.pricingMode !== 'quote' && service.priceCents != null ? (
          <Price cents={service.priceCents} per={service.pricingMode === 'hourly' ? t('rv.provider.perHour') : undefined} />
        ) : (
          <Text style={textStyles.price}>{t('services.quote')}</Text>
        )}
        {service ? (
          <Text style={textStyles.meta} numberOfLines={1}>
            {[service.name, duration(service.durationMin, t)].join(' · ')}
          </Text>
        ) : null}
      </View>
      <KitButton
        label={quote ? t('rv.provider.requestQuote') : t('rv.provider.book')}
        size="lg"
        disabled={!service}
        style={styles.barButton}
        onPress={() => {
          if (!service) return;
          updateDraft(p.slug, { serviceId: service.id, quote });
          router.push(`/book/${p.slug}/service`);
        }}
        testID="book-visit"
      />
    </View>
  );
}

function ProfileSkeleton() {
  const { t } = useI18n();
  return (
    <View accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.section} testID="loading">
      <Skeleton height={SIZE.rail + space[6]} />
      <Skeleton height={space[10]} width="80%" />
      <Skeleton height={space[4]} width="60%" />
      {[0, 1, 2].map((i) => (
        <Skeleton key={i} height={space[12]} />
      ))}
    </View>
  );
}

const PIC = SIZE.rail + space[6];

const styles = StyleSheet.create({
  flex: { flex: 1, minWidth: 0, gap: LINE.bold },
  id: { flexDirection: 'row', gap: space[4], alignItems: 'flex-start' },
  pic: { width: PIC, height: PIC, borderRadius: radius.image, overflow: 'hidden', backgroundColor: semantic.well },
  idText: { flex: 1, minWidth: 0, gap: space[1] },
  tags: { flexDirection: 'row', flexWrap: 'wrap', gap: space[1.5] },
  tabs: { marginTop: space[1] },
  section: { gap: space[3] },
  sectionHead: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', gap: space[2] },
  options: { gap: space[2] },
  how: { gap: space[3], padding: space[4], borderRadius: radius.panel, backgroundColor: semantic.well },
  howLine: { flexDirection: 'row', alignItems: 'center', gap: space[3] },
  review: { gap: space[1.5], paddingVertical: space[3], borderTopWidth: LINE.hair, borderTopColor: semantic.border },
  bar: { flexDirection: 'row', alignItems: 'center', gap: space[3] },
  barButton: { flex: 1.2 },
});
