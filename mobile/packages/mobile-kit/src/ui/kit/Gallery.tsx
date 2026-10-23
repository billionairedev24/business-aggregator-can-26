import { useState, type ReactNode } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';

import type { Locale } from '../../i18n';
import { radius, semantic, space } from '../../theme';
import { Photo } from '../Photo';
import { Button, IconButton } from './Button';
import { Checkbox, ChoiceTiles, FilterChip, OptionCard, Segmented, Switch, UnderlineTabs } from './Choice';
import { DealBadge, Price, ProductCard, QuantityStepper, Rating, ServiceCard, StatusTag } from './Commerce';
import { BottomSheet, CardSkeleton, EmptyState, Skeleton, Toast } from './Feedback';
import { formatDateRange, formatPrice, formatTime, formatTimeRange, formatUnitPrice, unitCents } from './format';
import { KitProvider, useKit } from './KitProvider';
import { AppHeader, BottomTabBar } from './Navigation';
import { SearchBar } from './SearchBar';
import { LINE, textStyles } from './tokens';

/**
 * The kit's gallery (S-169): every shared component in its states, in English or French (the switch at the top), on
 * the palette the build selected. Both apps mount it on a dev-build-only route; `make mobile-shots` photographs it.
 * The sample dates are fixed instants so the pictures don't change from day to day.
 */
export function KitGallery({ initialLocale = 'en', onBack, bottomInset = 0, testID = 'kit-gallery' }: { initialLocale?: Locale; onBack?: () => void; bottomInset?: number; testID?: string }) {
  const [locale, setLocale] = useState<Locale>(initialLocale);
  return (
    <KitProvider locale={locale}>
      <GalleryBody locale={locale} setLocale={setLocale} onBack={onBack} bottomInset={bottomInset} testID={testID} />
    </KitProvider>
  );
}

const WINDOW_START = '2026-10-23T00:00:00Z';
const WINDOW_END = '2026-10-23T03:00:00Z';
const ZONE = 'UTC';

function GalleryBody({ locale, setLocale, onBack, bottomInset, testID }: { locale: Locale; setLocale: (l: Locale) => void; onBack?: () => void; bottomInset: number; testID: string }) {
  const { t } = useKit();
  const [tab, setTab] = useState('pack');
  const [tip, setTip] = useState('3');
  const [stock, setStock] = useState('replace');
  const [chips, setChips] = useState<Record<string, boolean>>({ vegan: false, near: false });
  const [applied, setApplied] = useState(true);
  const [slot, setSlot] = useState('0930');
  const [windowChoice, setWindowChoice] = useState('tonight');
  const [subs, setSubs] = useState(true);
  const [notify, setNotify] = useState(false);
  const [qty, setQty] = useState(1);
  const [query, setQuery] = useState('');
  const [scope, setScope] = useState('shop');
  const [sheet, setSheet] = useState(false);
  const [sort, setSort] = useState('featured');
  const [toast, setToast] = useState(true);
  const [navTab, setNavTab] = useState('home');
  const noop = () => undefined;

  return (
    <View style={styles.root} testID={testID}>
      <AppHeader title={t('gallery.title')} onBack={onBack} actions={<IconButton icon="export" label={t('gallery.share')} onPress={noop} />} />
      <ScrollView contentContainerStyle={styles.scroll}>
        <Text accessibilityRole="header" style={textStyles.pageTitle}>
          {t('gallery.title')}
        </Text>
        <Text style={[textStyles.body, styles.muted]}>{t('gallery.lead')}</Text>
        <Segmented
          label={t('gallery.language')}
          options={[
            { value: 'en', label: 'English' },
            { value: 'fr-CA', label: 'Français' },
          ]}
          value={locale}
          onChange={(v) => setLocale(v as Locale)}
          testID="gallery-locale"
        />

        <Section title={t('gallery.actions')}>
          <View style={styles.wrap}>
            <Button label={t('gallery.addToCart')} icon="shopping-cart" onPress={noop} />
            <Button label={t('gallery.buyNow')} variant="secondary" onPress={noop} />
            <Button label={t('gallery.details')} variant="tertiary" onPress={noop} />
            <Button label={t('gallery.suspend')} variant="destructive" icon="trash" onPress={noop} />
          </View>
          <View style={styles.wrap}>
            <Button label={t('gallery.addToCart')} size="sm" onPress={noop} />
            <Button label={t('gallery.disabled')} variant="secondary" size="sm" disabled onPress={noop} />
            <Button label={t('gallery.busy')} busy onPress={noop} />
          </View>
          <Button label={t('gallery.addToCart')} size="lg" block onPress={noop} />
        </Section>

        <Section title={t('gallery.tabs')}>
          <UnderlineTabs
            label={t('gallery.tabs')}
            value={tab}
            onChange={setTab}
            options={[
              { value: 'pack', label: t('gallery.toPack'), count: 3 },
              { value: 'packed', label: t('gallery.packed'), count: 2 },
              { value: 'delivered', label: t('gallery.delivered'), count: 32 },
            ]}
          />
        </Section>

        <Section title={t('gallery.segmented')}>
          <Text style={textStyles.label}>{t('gallery.tip')}</Text>
          <Segmented
            label={t('gallery.tip')}
            value={tip}
            onChange={setTip}
            fill
            options={[
              { value: '0', label: t('gallery.noTip') },
              { value: '2', label: formatPrice(200, locale) },
              { value: '3', label: formatPrice(300, locale) },
              { value: '5', label: formatPrice(500, locale) },
              { value: 'other', label: t('gallery.other') },
            ]}
          />
          <Text style={textStyles.label}>{t('gallery.outOfStock')}</Text>
          <Segmented
            label={t('gallery.outOfStock')}
            value={stock}
            onChange={setStock}
            stack
            options={[
              { value: 'replace', label: t('gallery.replace') },
              { value: 'refund', label: t('gallery.refund') },
              { value: 'text', label: t('gallery.textMe') },
            ]}
          />
        </Section>

        <Section title={t('gallery.chips')}>
          <View style={styles.wrap}>
            {applied ? <FilterChip label={t('gallery.tonight')} onRemove={() => setApplied(false)} /> : <FilterChip label={t('gallery.tonight')} onPress={() => setApplied(true)} />}
            <FilterChip label={t('gallery.vegan')} count={12} selected={chips.vegan} onPress={() => setChips((c) => ({ ...c, vegan: !c.vegan }))} />
            <FilterChip label={t('gallery.near')} icon="map-pin" selected={chips.near} onPress={() => setChips((c) => ({ ...c, near: !c.near }))} />
          </View>
        </Section>

        <Section title={t('gallery.tiles')}>
          <Text style={textStyles.label}>{t('gallery.time')}</Text>
          <ChoiceTiles
            label={t('gallery.time')}
            value={slot}
            onChange={setSlot}
            options={[
              { value: '0900', label: formatTime('2026-10-23T09:00:00Z', locale, ZONE), disabled: true },
              { value: '0930', label: formatTime('2026-10-23T09:30:00Z', locale, ZONE) },
              { value: '1100', label: formatTime('2026-10-23T11:00:00Z', locale, ZONE) },
              { value: '1330', label: formatTime('2026-10-23T13:30:00Z', locale, ZONE) },
            ]}
          />
          <Text style={textStyles.label}>{t('gallery.window')}</Text>
          <View style={styles.stack}>
            {(
              [
                ['tonight', t('gallery.tonightWindow'), t('gallery.tonightNote'), 299],
                ['tomorrow', t('gallery.tomorrowWindow'), t('gallery.tomorrowNote'), 199],
                ['express', t('gallery.express'), t('gallery.expressNote'), 999],
              ] as const
            ).map(([v, title, note, cents]) => (
              <OptionCard key={v} title={title} description={note} right={formatPrice(cents, locale)} selected={windowChoice === v} onPress={() => setWindowChoice(v)} testID={`gallery-window-${v}`} />
            ))}
          </View>
        </Section>

        <Section title={t('gallery.toggles')}>
          <Checkbox label={t('gallery.substitutes')} checked={subs} onChange={setSubs} />
          <Switch label={t('gallery.notify')} value={notify} onChange={setNotify} />
        </Section>

        <Section title={t('gallery.commerce')}>
          <View style={styles.cards}>
            <ProductCard
              title={t('gallery.sourdough')}
              seller={t('gallery.bakery')}
              priceCents={850}
              unit={{ cents: unitCents(850, 900, '100g'), per: '100g' }}
              rating={{ value: 4.8, count: 312 }}
              eta={t('gallery.eta', { when: t('gallery.tonightShort') })}
              deal={t('gallery.deal', { n: 12 })}
              photo={<Photo slot="demo/merchant/bakery" crop="square" style={StyleSheet.absoluteFill} />}
              addLabel={t('gallery.addToCart')}
              inCart={qty}
              onAdd={() => setQty((q) => q + 1)}
              onLess={() => setQty((q) => Math.max(0, q - 1))}
              testID="gallery-product"
            />
            <ProductCard
              title={t('gallery.honey')}
              seller={t('gallery.apiary')}
              priceCents={1350}
              unit={{ cents: unitCents(1350, 500, '100g'), per: '100g' }}
              rating={{ value: 4.9, count: 41 }}
              low={t('gallery.lowStock', { n: 4 })}
              icon="drop"
              addLabel={t('gallery.addToCart')}
              onAdd={noop}
              testID="gallery-fallback"
            />
          </View>
          <View style={styles.cards}>
            <ServiceCard
              title={t('gallery.mechanic')}
              meta={t('gallery.mechanicMeta')}
              fromCents={7900}
              rating={{ value: 4.9, count: 212 }}
              photo={<Photo slot="demo/merchant/handyman" crop="square" style={StyleSheet.absoluteFill} />}
              onPress={noop}
            />
            <View style={styles.cardSpacer} />
          </View>
          <View style={styles.wrapCenter}>
            <Price cents={850} unit={{ cents: 94, per: '100g' }} size="lg" />
            <Rating value={4.8} count={312} />
            <DealBadge label={t('gallery.deal', { n: 12 })} />
          </View>
          <QuantityStepper value={qty} onLess={() => setQty((q) => Math.max(0, q - 1))} onMore={() => setQty((q) => q + 1)} size="lg" />
        </Section>

        <Section title={t('gallery.status')}>
          <View style={styles.wrap}>
            <StatusTag label={t('gallery.stDelivered')} tone="success" />
            <StatusTag label={t('gallery.stPacked')} tone="info" />
            <StatusTag label={t('gallery.stToPack')} tone="warning" />
            <StatusTag label={t('gallery.stWrong')} tone="danger" />
            <StatusTag label={t('gallery.stPaused')} tone="neutral" />
          </View>
        </Section>

        <Section title={t('gallery.formats')}>
          <Line label={t('gallery.priceLine')} value={`${formatPrice(850, locale)} · ${formatPrice(123456, locale)}`} />
          <Line label={t('gallery.unitLine')} value={`${formatUnitPrice(94, '100g', locale)} · ${formatUnitPrice(375, 'each', locale)}`} />
          <Line label={t('gallery.timeLine')} value={formatTimeRange(WINDOW_START, WINDOW_END, locale, ZONE)} />
          <Line label={t('gallery.dateLine')} value={`${formatDateRange('2026-10-19', '2026-10-22', locale)} · ${formatDateRange('2026-10-30', '2026-11-02', locale)}`} />
        </Section>

        <Section title={t('gallery.inputs')}>
          <SearchBar
            placeholder={t('gallery.searchHint')}
            value={query}
            onChangeText={setQuery}
            onSubmit={noop}
            scopes={[
              { value: 'all', label: t('gallery.all') },
              { value: 'services', label: t('gallery.services') },
              { value: 'shop', label: t('gallery.shop') },
              { value: 'food', label: t('gallery.food') },
            ]}
            scope={scope}
            onScope={setScope}
            testID="gallery-search"
          />
        </Section>

        <Section title={t('gallery.feedback')}>
          <View style={styles.wrap}>
            <Button label={t('gallery.openSheet')} variant="secondary" onPress={() => setSheet(true)} testID="gallery-open-sheet" />
            <Button label={t('gallery.showToast')} variant="secondary" onPress={() => setToast(true)} />
          </View>
          {toast ? <Toast text={t('gallery.toast')} tone="success" action={{ label: t('gallery.undo'), onPress: () => setToast(false) }} onDismiss={() => setToast(false)} style={styles.inlineToast} testID="gallery-toast" /> : null}
          <EmptyState icon="shopping-cart" title={t('gallery.emptyTitle')} message={t('gallery.emptyBody')} action={t('gallery.emptyAction')} onAction={noop} />
          <Text style={textStyles.label}>{t('gallery.loadingCards')}</Text>
          <CardSkeleton />
          <Skeleton height={space[4]} width="60%" />
        </Section>

        <Section title={t('gallery.navigation')}>
          <View style={styles.frame}>
            <AppHeader title={t('gallery.header')} onBack={noop} actions={<IconButton icon="export" label={t('gallery.share')} onPress={noop} />} />
            <View style={styles.frameBody} />
            <BottomTabBar
              current={navTab}
              onSelect={setNavTab}
              items={[
                { key: 'home', label: t('gallery.home'), icon: 'house', testID: 'gallery-tab-home' },
                { key: 'browse', label: t('gallery.browse'), icon: 'squares-four', testID: 'gallery-tab-browse' },
                { key: 'cart', label: t('gallery.cart'), icon: 'shopping-cart', badge: 4, testID: 'gallery-tab-cart' },
                { key: 'account', label: t('gallery.account'), icon: 'user-circle', testID: 'gallery-tab-account' },
              ]}
            />
          </View>
        </Section>
        <View style={{ height: bottomInset }} />
      </ScrollView>
      <BottomSheet visible={sheet} onClose={() => setSheet(false)} title={t('gallery.sheetTitle')} bottomInset={bottomInset} testID="gallery-sheet">
        {(
          [
            ['featured', t('gallery.featured')],
            ['price', t('gallery.priceLow')],
            ['rating', t('gallery.rating')],
          ] as const
        ).map(([v, label]) => (
          <OptionCard key={v} title={label} selected={sort === v} onPress={() => setSort(v)} />
        ))}
      </BottomSheet>
    </View>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <View style={styles.section}>
      <Text accessibilityRole="header" style={textStyles.section}>
        {title}
      </Text>
      {children}
    </View>
  );
}

function Line({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.line}>
      <Text style={[textStyles.meta, styles.lineLabel]}>{label}</Text>
      <Text style={[textStyles.body, styles.flex]}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: semantic.surface },
  flex: { flex: 1 },
  muted: { color: semantic['text-muted'] },
  scroll: { padding: space[4], gap: space[4] },
  section: { gap: space[3], paddingTop: space[4], borderTopWidth: LINE.hair, borderTopColor: semantic.border },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2], alignItems: 'center' },
  wrapCenter: { flexDirection: 'row', flexWrap: 'wrap', gap: space[3], alignItems: 'center' },
  stack: { gap: space[2] },
  cards: { flexDirection: 'row', gap: space[3], alignItems: 'flex-start' },
  cardSpacer: { flex: 1 },
  line: { flexDirection: 'row', gap: space[3], alignItems: 'baseline' },
  lineLabel: { width: space[16] + space[6] },
  inlineToast: { position: 'relative', left: 0, right: 0 },
  frame: { borderWidth: LINE.hair, borderColor: semantic['border-strong'], borderRadius: radius.panel, overflow: 'hidden' },
  frameBody: { height: space[16], backgroundColor: semantic.well },
});
