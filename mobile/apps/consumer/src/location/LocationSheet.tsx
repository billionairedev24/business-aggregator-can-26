import { useQuery } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { router, useLocalSearchParams } from 'expo-router';
import { useMemo, useState } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { ApiError, colors, Icon, LINE, MIN_TARGET, radius, semantic, shadow, space, textStyles } from '@northline/mobile-kit';

import type { Address as AccountAddress } from '../api/account';
import { geoApi, type PostalPlace, type Resolution } from '../api/geo';
import { useAuth } from '../auth/AuthProvider';
import { EMAIL_PATTERN } from '../auth/rules';
import { useI18n, type MessageKey } from '../i18n';
import { services } from '../services';
import { Body, Button, Field, Notice, type } from '../ui/primitives';
import { RoundButton } from '../ui/screen';
import { Skeleton } from '../ui/states';
import { countryName, countryOptions } from './countries';
import { locateHere, placeLine, placeOfAddress, useDeliveryLocation, type SavedLocation } from './DeliveryLocation';

type T = (key: MessageKey, params?: Record<string, string | number>) => string;

/** validation-rules.md § Location (S-192): the api's `PlaceParams.postalCode`, the same messages. */
const POSTAL = /^[A-Z0-9](?:[A-Z0-9 -]*[A-Z0-9])?$/;
export function postalError(t: T, raw: string): { code: string } | { error: string } {
  const code = raw.trim().replace(/\s+/g, ' ').toUpperCase();
  if (!code) return { error: t('lt.loc.postalRequired') };
  if (code.length > 12) return { error: t('lt.loc.postalTooLong') };
  if (!POSTAL.test(code)) return { error: t('lt.loc.postalFormat') };
  return { code };
}

/** What the sheet saves for a postal code (S-192): inside a live or pilot market its city; elsewhere the code and its point. */
export function fromPostal(p: PostalPlace): SavedLocation {
  const m = p.resolution?.market;
  const served = !!m && (m.stage === 'live' || m.stage === 'pilot');
  const locality = p.city ?? undefined;
  return {
    label: locality ? `${locality} ${p.postalCode}` : p.postalCode,
    postalCode: p.postalCode,
    ...(locality ? { locality } : {}),
    ...(p.country ? { country: p.country } : {}),
    ...(p.lat != null && p.lng != null ? { lat: p.lat, lng: p.lng } : {}),
    ...(served && m ? { city: m.city, province: m.province, marketId: m.id } : {}),
    ...(served && p.resolution?.zone ? { zoneId: p.resolution.zone.id, zone: p.resolution.zone.name } : {}),
  };
}

/**
 * The location sheet (S-192, the consumer web's "Choose your location" after Amazon's): a bottom sheet over the screen
 * it was opened from — the line that delivery depends on the place, what is shown now, the signed-in person's addresses
 * as cards (the chosen one marked, "Default address", "Manage addresses") or "Sign in to see your addresses", "or enter
 * a postal or ZIP code" with Apply (any country, `GET /geo/postal`; a code nobody knows is kept as typed), "or deliver to
 * another country" (a country alone, no city), "Use my current location" (the only place the app asks for the phone's
 * position, on the press) and Done. Each choice is saved at once; Done closes (or goes on to `next`, the sign-in flows'
 * address step). A place outside every market is kept: the screens rank the nearest markets first (S-190).
 */
export function LocationSheet() {
  const { t, locale } = useI18n();
  const insets = useSafeAreaInsets();
  const { next, step } = useLocalSearchParams<{ next?: string; step?: string }>();
  const askAddress = step === 'address';
  const { location, save } = useDeliveryLocation();
  const { status } = useAuth();
  const addresses = useQuery({
    queryKey: ['account', 'addresses'],
    queryFn: async () => (await services().api.get<{ items: AccountAddress[] }>('/me/addresses'))?.items ?? [],
    enabled: status === 'signedIn',
    staleTime: 60_000,
  });

  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string | null>(null);
  const [applying, setApplying] = useState(false);
  const [applied, setApplied] = useState<PostalPlace | null>(null);
  const [countriesOpen, setCountriesOpen] = useState(false);
  const [note, setNote] = useState<{ text: string; settings?: boolean } | null>(null);
  const [locating, setLocating] = useState(false);
  const countries = useMemo(() => countryOptions(locale), [locale]);

  const close = () => {
    const to = typeof next === 'string' && next.startsWith('/') && !next.startsWith('//') ? next : askAddress ? '/home' : null;
    if (to) router.replace(to as never);
    else if (router.canGoBack()) router.back();
    else router.replace('/home');
  };
  const choose = (l: SavedLocation) => {
    setNote(null);
    void save(l);
  };

  const apply = async () => {
    const checked = postalError(t, code);
    if ('error' in checked) return setCodeError(checked.error);
    setCodeError(null);
    setApplying(true);
    try {
      const p = (await geoApi(services().api).postal(checked.code)) ?? { postalCode: checked.code, found: false };
      setApplied(p);
      setCode(p.postalCode);
      choose(fromPostal(p));
    } catch (e) {
      if (e instanceof ApiError && e.status === 422) setCodeError(e.message);
      else {
        // the lookup is down: the code is still a place (S-190, never blocks)
        const p = { postalCode: checked.code, found: false };
        setApplied(p);
        choose(fromPostal(p));
      }
    } finally {
      setApplying(false);
    }
  };

  const locate = async () => {
    setNote(null);
    setApplied(null);
    setLocating(true);
    const r = await locateHere();
    setLocating(false);
    if (r.ok) {
      const { status: _status, ...place } = r.place;
      await save(place);
      close();
      return;
    }
    if (r.reason === 'outside') {
      if (r.place) void save(r.place);
      setNote({ text: r.place ? t('lt.loc.hereOutsideKept') : t('lt.loc.hereOutside') });
    } else if (r.reason === 'unavailable') setNote({ text: t('lt.loc.hereUnavailable') });
    else setNote({ text: t('lt.loc.hereDenied'), settings: r.reason === 'settings' });
  };

  const place = placeLine(location, (c) => countryName(c, locale));
  const currentLine = place ? t('lt.loc.delivering', { place }) : t('lt.loc.currentNone');
  const chosen = (a: AccountAddress) => location.status !== 'locating' && location.street === a.street && location.city === a.city;
  const pickedCountry = location.status !== 'locating' && !location.city && !location.postalCode ? location.country : undefined;
  const appliedServed = !!applied?.resolution?.market && ['live', 'pilot'].includes(applied.resolution.market.stage);

  return (
    <View style={styles.root} testID="location">
      {/* tapping outside closes it too; screen readers use the Close button (one name, not two) */}
      <Pressable accessible={false} importantForAccessibility="no" onPress={close} style={styles.scrim} testID="location-scrim" />
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={styles.sheetWrap} pointerEvents="box-none">
        <View style={[styles.sheet, { paddingBottom: Math.max(insets.bottom, space[4]) }]} accessibilityViewIsModal>
          <View style={styles.grabber} />
          <View style={styles.head}>
            <Text accessibilityRole="header" style={styles.title}>
              {askAddress ? t('location.title') : t('lt.loc.title')}
            </Text>
            <RoundButton icon="x" label={t('lt.loc.close')} onPress={close} testID="location-close" />
          </View>
          <ScrollView contentContainerStyle={styles.body} keyboardShouldPersistTaps="handled">
            <Body tone="muted">{t('lt.loc.intro')}</Body>
            <Text style={[type.body, type.strong]} accessibilityLiveRegion="polite" testID="location-current">
              {currentLine}
            </Text>

            {status === 'signedIn' ? (
              <View style={styles.gap} testID="location-addresses">
                <Text style={type.kicker}>{t('lt.loc.savedAddresses')}</Text>
                {addresses.isPending ? <Skeleton height={64} /> : null}
                {(addresses.data ?? []).map((a) => (
                  <Pressable
                    key={a.id}
                    accessibilityRole="button"
                    accessibilityState={{ selected: chosen(a) }}
                    accessibilityLabel={[a.label, a.street, a.city, a.postal, a.isDefault ? t('lt.loc.defaultAddress') : null].filter(Boolean).join(', ')}
                    onPress={() => {
                      setApplied(null);
                      choose(placeOfAddress(a));
                    }}
                    style={({ pressed }) => [styles.card, chosen(a) && styles.cardChosen, pressed && styles.pressed]}
                    testID={`address-${a.id}`}
                  >
                    <View style={styles.flex}>
                      <Text style={[type.body, type.strong]} numberOfLines={2}>
                        {a.label ? `${a.label} · ${a.street}` : a.street}
                      </Text>
                      <Text style={type.small}>{[a.city, a.province, a.postal].filter(Boolean).join(' ')}</Text>
                      {a.isDefault ? <Text style={[type.small, type.strong]}>{t('lt.loc.defaultAddress')}</Text> : null}
                    </View>
                    {chosen(a) ? <Icon name="check" size={20} color={semantic.selected} /> : null}
                  </Pressable>
                ))}
                <Pressable accessibilityRole="link" onPress={() => router.push('/account/addresses' as never)} style={styles.link} testID="location-manage">
                  <Text style={styles.linkText}>{t('lt.loc.manageAddresses')}</Text>
                </Pressable>
              </View>
            ) : (
              <Button label={t('lt.loc.signInForAddresses')} tone="secondary" onPress={() => router.push('/sign-in')} testID="location-sign-in" />
            )}

            <Divider label={t('lt.loc.orPostal')} />
            <View style={styles.postal}>
              <View style={styles.flex}>
                <Field
                  label={t('lt.loc.postal')}
                  placeholder={t('lt.loc.postalPlaceholder')}
                  value={code}
                  onChangeText={(v) => {
                    setCode(v);
                    setCodeError(null);
                  }}
                  error={codeError}
                  autoCapitalize="characters"
                  autoCorrect={false}
                  autoComplete="postal-code"
                  textContentType="postalCode"
                  maxLength={20}
                  onSubmitEditing={() => void apply()}
                  testID="field-postal"
                />
              </View>
              <Button label={t('lt.loc.apply')} tone="secondary" busy={applying} onPress={() => void apply()} style={styles.apply} testID="location-apply" />
            </View>
            {applied && !appliedServed ? (
              <View style={styles.gap}>
                <Body tone="small">{t('lt.loc.appliedElsewhere', { place: applied.city ? `${applied.city} ${applied.postalCode}` : applied.postalCode })}</Body>
                {applied.resolution?.waitlist ? <Waitlist t={t} resolution={applied.resolution} /> : null}
              </View>
            ) : null}

            <Divider label={t('lt.loc.orCountry')} />
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={`${t('lt.loc.country')}, ${pickedCountry ? countryName(pickedCountry, locale) : t('lt.loc.chooseCountry')}`}
              accessibilityState={{ expanded: countriesOpen }}
              onPress={() => setCountriesOpen((o) => !o)}
              style={({ pressed }) => [styles.select, pressed && styles.pressed]}
              testID="location-country"
            >
              <Text style={[type.body, styles.flex]}>{pickedCountry ? countryName(pickedCountry, locale) : t('lt.loc.chooseCountry')}</Text>
              <Icon name={countriesOpen ? 'caret-right' : 'caret-down'} size={18} color={colors.textMuted} />
            </Pressable>
            {countriesOpen ? (
              <View style={styles.list} accessibilityLabel={t('lt.loc.allCountries')}>
                {countries.map((c) => (
                  <Pressable
                    key={c.code}
                    accessibilityRole="button"
                    accessibilityState={{ selected: pickedCountry === c.code }}
                    onPress={() => {
                      setApplied(null);
                      setCountriesOpen(false);
                      choose({ country: c.code });
                    }}
                    style={({ pressed }) => [styles.option, pressed && styles.pressed]}
                    testID={`country-${c.code}`}
                  >
                    <Text style={type.body}>{c.name}</Text>
                  </Pressable>
                ))}
              </View>
            ) : null}

            <Pressable
              accessibilityRole="button"
              accessibilityLabel={t('lt.loc.useHere')}
              accessibilityState={{ busy: locating }}
              onPress={() => void locate()}
              style={({ pressed }) => [styles.here, pressed && styles.pressed]}
              testID="location-use-mine"
            >
              <Icon name="crosshair" size={20} color={semantic['action-primary']} />
              <Text style={styles.linkText}>{locating ? t('lt.loc.locating') : t('lt.loc.useHere')}</Text>
            </Pressable>
            {note ? (
              <View style={styles.gap}>
                <Notice message={note.text} tone="info" />
                {note.settings ? <Button label={t('location.openSettings')} tone="secondary" onPress={() => void Linking.openSettings()} /> : null}
              </View>
            ) : null}

            <Button label={t('lt.loc.done')} large onPress={close} testID="location-done" />
          </ScrollView>
        </View>
      </KeyboardAvoidingView>
    </View>
  );
}

/** "or …": a hairline with the words in the middle. */
function Divider({ label }: { label: string }) {
  return (
    <View style={styles.divider} accessibilityRole="text">
      <View style={styles.rule} />
      <Text style={type.small}>{label}</Text>
      <View style={styles.rule} />
    </View>
  );
}

/** A market not open yet: its waitlist, with an email (a guest's own, or the account's). */
function Waitlist({ t, resolution }: { t: T; resolution: Resolution }) {
  const name = resolution.waitlist?.name ?? resolution.market?.city ?? '';
  const [email, setEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [joined, setJoined] = useState(false);
  const [busy, setBusy] = useState(false);
  const regionId = resolution.waitlist?.regionId ?? null;

  const join = async () => {
    if (!regionId) return;
    const value = email.trim();
    if (!value) return setError(t('authErr.emailRequired'));
    if (!EMAIL_PATTERN.test(value)) return setError(t('authErr.emailFormat'));
    setBusy(true);
    try {
      await geoApi(services().api).joinWaitlist(regionId, value);
      setJoined(true);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('authErr.network'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <View style={styles.card} testID="waitlist">
      <View style={[styles.flex, styles.gap]}>
        <Text style={[type.body, type.strong]}>{t('location.notLiveTitle', { name })}</Text>
        {joined ? (
          <Notice message={t('location.joined', { name })} tone="info" />
        ) : (
          <>
            <Body tone="muted">{resolution.market?.stage === 'pilot' ? t('location.notLivePilot', { name }) : t('location.notLiveBody')}</Body>
            <Field
              label={t('location.email')}
              placeholder={t('field.emailPh')}
              value={email}
              onChangeText={(v) => {
                setEmail(v);
                setError(null);
              }}
              error={error}
              keyboardType="email-address"
              autoCapitalize="none"
              autoComplete="email"
              testID="field-waitlist-email"
            />
            <Button label={t('location.join')} tone="secondary" busy={busy} onPress={() => void join()} testID="waitlist-join" />
          </>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, minWidth: 0 },
  root: { flex: 1, justifyContent: 'flex-end' },
  scrim: { position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, backgroundColor: colors.overlay },
  sheetWrap: { maxHeight: '94%' },
  sheet: { borderTopLeftRadius: radius.panel, borderTopRightRadius: radius.panel, backgroundColor: semantic.surface, ...shadow.lg, maxHeight: '100%' },
  grabber: { alignSelf: 'center', width: 40, height: 5, borderRadius: radius.control, marginTop: space[2], backgroundColor: semantic['border-strong'] },
  head: { flexDirection: 'row', alignItems: 'center', gap: space[3], paddingHorizontal: space[4], paddingTop: space[2], paddingBottom: space[2] },
  title: { ...StyleSheet.flatten(textStyles.pageTitle), flex: 1 },
  body: { gap: space[3], paddingHorizontal: space[4], paddingBottom: space[4] },
  gap: { gap: space[2] },
  card: {
    minHeight: MIN_TARGET,
    flexDirection: 'row',
    alignItems: 'center',
    gap: space[3],
    padding: space[3],
    borderRadius: radius.panel,
    borderWidth: LINE.hair,
    borderColor: semantic['border-control'],
    backgroundColor: semantic.surface,
  },
  cardChosen: { borderWidth: 2, borderColor: semantic.selected, backgroundColor: semantic['selected-tint'] },
  pressed: { backgroundColor: semantic['surface-hover'] },
  link: { minHeight: MIN_TARGET, justifyContent: 'center', alignSelf: 'flex-start' },
  linkText: { ...StyleSheet.flatten(textStyles.bodyStrong), color: semantic['action-primary'] },
  divider: { flexDirection: 'row', alignItems: 'center', gap: space[3], marginTop: space[1] },
  rule: { flex: 1, height: LINE.hair, backgroundColor: semantic.border },
  postal: { flexDirection: 'row', alignItems: 'flex-start', gap: space[2] },
  apply: { marginTop: space[6] },
  select: {
    minHeight: MIN_TARGET,
    flexDirection: 'row',
    alignItems: 'center',
    gap: space[2],
    paddingHorizontal: space[3],
    borderRadius: radius.control,
    borderWidth: LINE.hair,
    borderColor: semantic['border-control'],
    backgroundColor: semantic.surface,
  },
  list: { borderWidth: LINE.hair, borderColor: semantic['border-card'], borderRadius: radius.panel, backgroundColor: semantic.surface, overflow: 'hidden' },
  option: { minHeight: MIN_TARGET, justifyContent: 'center', paddingHorizontal: space[4], borderBottomWidth: LINE.hair, borderBottomColor: semantic.border },
  here: { minHeight: MIN_TARGET, flexDirection: 'row', alignItems: 'center', gap: space[2], alignSelf: 'flex-start' },
});
