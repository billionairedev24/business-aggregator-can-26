import { useQuery } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { router, useLocalSearchParams } from 'expo-router';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { ApiError, Icon, MIN_TARGET, colors, fonts, radius, randomToken, semantic, shadow, space, type IconName } from '@northline/mobile-kit';

import type { Address as AccountAddress } from '../api/account';
import { geoApi, type Address, type Market, type Province } from '../api/geo';
import { useAuth } from '../auth/AuthProvider';
import { EMAIL_PATTERN } from '../auth/rules';
import { useI18n, type MessageKey } from '../i18n';
import { services } from '../services';
import { Body, Button, Field, Notice, Row, Tag, type } from '../ui/primitives';
import { RoundButton } from '../ui/screen';
import { Skeleton } from '../ui/states';
import { locateHere, placeOfAddress, useDeliveryLocation, type DeliveryLocation, type SavedLocation } from './DeliveryLocation';

type T = (key: MessageKey, params?: Record<string, string | number>) => string;

/** Waits `ms` after the last change (typeahead: one request per pause, not per keystroke). */
function useDebounced<V>(value: V, ms: number): V {
  const [v, setV] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setV(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return v;
}

const newSession = () => randomToken(16);

/** A known place without its status, as the store keeps it (the caller checked label and city). */
function stripStatus(l: DeliveryLocation): SavedLocation {
  const { status: _status, label = '', city = '', ...rest } = l;
  return { label, city, ...rest };
}

/**
 * The location sheet (light direction; the consumer web's location dialog, DECISIONS "Light foundation"): a bottom
 * sheet over the screen it was opened from, with what is shown now and four ways to change it —
 *   · "Use my current location": the only place the app asks for the phone's position, on the press
 *     (`expo-location`, then `GET /geo/reverse`); refused, unavailable or outside our markets each say so;
 *   · "Address or postal code": suggestions through the api (`/geo/autocomplete`, Canada only, one session token per
 *     search), resolved by `/geo/places/{id}` to a market and zone — "Deliver here" with the unit / buzzer note, or
 *     the waitlist outside a live market;
 *   · the signed-in person's addresses (`GET /me/addresses`);
 *   · the live markets (`GET /geo/markets`).
 * Choosing saves the place (every screen reads it in place) and closes the sheet — or goes on to `next` (the sign-in
 * and sign-up flows' address step).
 */
export function LocationSheet() {
  const { t } = useI18n();
  const insets = useSafeAreaInsets();
  const { next, step } = useLocalSearchParams<{ next?: string; step?: string }>();
  // after a sign-up or sign-in with no address yet: Journey A's question (design 01 A5) heads the sheet
  const askAddress = step === 'address';
  const { location, save } = useDeliveryLocation();
  const { status } = useAuth();
  const geo = geoApi(services().api);
  const markets = useQuery({ queryKey: ['geo', 'markets'], queryFn: () => geo.markets(), staleTime: 300_000 });
  const addresses = useQuery({
    queryKey: ['account', 'addresses'],
    queryFn: async () => (await services().api.get<{ items: AccountAddress[] }>('/me/addresses'))?.items ?? [],
    enabled: status === 'signedIn',
    staleTime: 60_000,
  });

  const [query, setQuery] = useState('');
  const [session, setSession] = useState(newSession);
  const [placeId, setPlaceId] = useState<string | null>(null);
  const [listOpen, setListOpen] = useState(false);
  const [here, setHere] = useState<DeliveryLocation | null>(null);
  const [unit, setUnit] = useState(location.status === 'saved' ? (location.unit ?? '') : '');
  const [note, setNote] = useState<{ text: string; settings?: boolean } | null>(null);
  const [locating, setLocating] = useState(false);

  const debounced = useDebounced(query, 250);
  const typed = debounced.trim();
  const suggestions = useQuery({
    queryKey: ['geo', 'autocomplete', typed.toLowerCase()],
    queryFn: () => geo.suggest(typed, session, location),
    enabled: listOpen && typed.length >= 3 && !placeId,
    staleTime: 60_000,
    retry: false,
  });
  const address = useQuery({
    queryKey: ['geo', 'place', placeId],
    queryFn: () => geo.place(placeId!, session),
    enabled: !!placeId,
    staleTime: Infinity,
    retry: false,
  });
  const picked: Address | null = address.data ?? null;
  const provinces = useMemo(() => markets.data?.items ?? [], [markets.data]);
  const live = useMemo(() => provinces.flatMap((p) => p.markets.filter((m) => m.stage === 'live').map((m) => ({ m, p }))), [provinces]);

  const close = () => {
    // the sign-up / sign-in address step goes on to Home (what is under the sheet there is the sign-in flow)
    const to = typeof next === 'string' && next.startsWith('/') && !next.startsWith('//') ? next : askAddress ? '/home' : null;
    if (to) router.replace(to as never);
    else if (router.canGoBack()) router.back();
    else router.replace('/home');
  };
  const choose = async (l: SavedLocation) => {
    await save(l);
    close();
  };

  const onQuery = (v: string) => {
    setQuery(v);
    setListOpen(true);
    setHere(null);
    setNote(null);
    if (placeId) {
      setPlaceId(null);
      setSession(newSession()); // a new search, a new Google session
    }
  };
  const pick = (id: string, label: string) => {
    setPlaceId(id);
    setQuery(label);
    setListOpen(false);
  };

  const locate = async () => {
    setNote(null);
    setLocating(true);
    const r = await locateHere();
    setLocating(false);
    if (r.ok) {
      setPlaceId(null);
      setQuery('');
      setListOpen(false);
      setHere(r.place);
      return;
    }
    setNote(
      r.reason === 'outside'
        ? { text: t('lt.loc.hereOutside') }
        : r.reason === 'unavailable'
          ? { text: t('lt.loc.hereUnavailable') }
          : { text: t('lt.loc.hereDenied'), settings: r.reason === 'settings' },
    );
  };

  const savePicked = async () => {
    const extra = unit.trim() ? { unit: unit.trim() } : {};
    if (picked) {
      const r = picked.resolution;
      if (!r.market || r.market.stage !== 'live') return; // the waitlist is shown instead
      await choose({
        label: picked.label,
        city: r.market.city,
        lat: picked.lat,
        lng: picked.lng,
        placeId: picked.placeId,
        street: picked.street,
        ...extra,
        ...(picked.province ? { province: picked.province } : {}),
        ...(picked.postalCode ? { postalCode: picked.postalCode } : {}),
        marketId: r.market.id,
        ...(r.zone ? { zoneId: r.zone.id, zone: r.zone.name } : {}),
      });
    } else if (here?.label && here.city) {
      await choose({ ...stripStatus(here), ...extra });
    }
  };

  const current = location.label ?? location.city;
  const currentLine =
    location.status === 'saved' && current
      ? t('lt.loc.currentSaved', { place: current })
      : location.status === 'address' && current
        ? t('lt.loc.currentAddress', { place: current })
        : current
          ? t('lt.loc.currentDefault', { place: current })
          : t('lt.loc.currentNone');
  const showList = listOpen && typed.length >= 3 && !placeId;
  const pickedLive = picked ? picked.resolution.market?.stage === 'live' : false;
  const chosen = picked ?? null;
  const taxOf = (code?: string | null) => provinces.find((p) => p.code === code);

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
            <View style={styles.current}>
              <Icon name="map-pin" size={20} filled color={semantic['action-primary']} />
              <Text style={[type.body, styles.flex]}>{currentLine}</Text>
            </View>

            <Pressable
              accessibilityRole="button"
              accessibilityLabel={t('lt.loc.useHere')}
              accessibilityState={{ busy: locating }}
              onPress={() => void locate()}
              style={({ pressed }) => [styles.here, pressed && styles.pressed]}
              testID="location-use-mine"
            >
              <Icon name="crosshair" size={20} color={semantic['action-primary']} />
              <Text style={styles.hereText}>{locating ? t('lt.loc.locating') : t('lt.loc.useHere')}</Text>
            </Pressable>
            {note ? (
              <View style={styles.gap}>
                <Notice message={note.text} tone="info" />
                {note.settings ? <Button label={t('location.openSettings')} tone="secondary" onPress={() => void Linking.openSettings()} /> : null}
              </View>
            ) : null}
            {here ? <Chosen t={t} place={here.label ?? ''} detail={here.zone ? t('location.tagZone', { zone: here.zone }) : here.city} unit={unit} setUnit={setUnit} onSave={() => void savePicked()} /> : null}

            <View style={styles.gap}>
              <Field
                label={t('lt.loc.search')}
                placeholder={t('lt.loc.searchPlaceholder')}
                value={query}
                onChangeText={onQuery}
                onFocus={() => setListOpen(true)}
                error={address.isError ? t('location.addressError') : null}
                autoComplete="street-address"
                textContentType="fullStreetAddress"
                autoCorrect={false}
                testID="field-street"
              />
              {showList ? (
                <View accessibilityLabel={t('location.suggestions')} style={styles.list} testID="suggestions">
                  {(suggestions.data?.items ?? []).map((s) => (
                    <Choice key={s.placeId} icon="map-pin" title={s.main} sub={s.secondary} onPress={() => pick(s.placeId, `${s.main}, ${s.secondary.split(',')[0]}`)} label={`${s.main}, ${s.secondary}`} />
                  ))}
                  {suggestions.isFetching && !suggestions.data?.items.length ? <Body tone="small" style={styles.listNote}>{t('location.searching')}</Body> : null}
                  {suggestions.isSuccess && !suggestions.isFetching && suggestions.data?.items.length === 0 ? <Body tone="small" style={styles.listNote}>{t('location.noMatch', { q: typed })}</Body> : null}
                  {suggestions.isError ? (
                    <Body tone="small" style={styles.listNote}>
                      {suggestions.error instanceof ApiError && suggestions.error.status === 429 ? t('location.tooMany') : t('location.lookupError')}
                    </Body>
                  ) : null}
                  {suggestions.data ? <Body tone="small" style={styles.listNote}>{t('location.poweredBy', { name: suggestions.data.attribution })}</Body> : null}
                </View>
              ) : null}
              {address.isFetching ? <Body tone="small">{t('location.resolving')}</Body> : null}
            </View>

            {chosen && pickedLive ? (
              <Chosen
                t={t}
                place={chosen.label}
                tags={<Tags t={t} zone={chosen.resolution.zone?.name} runs={chosen.resolution.zone?.runsPerDay ?? null} province={taxOf(chosen.province)} />}
                unit={unit}
                setUnit={setUnit}
                onSave={() => void savePicked()}
              />
            ) : null}
            {chosen && !pickedLive ? <Waitlist t={t} address={chosen} onAnother={() => onQuery('')} /> : null}

            {status === 'signedIn' && (addresses.data?.length ?? 0) > 0 ? (
              <View style={styles.gap} testID="location-addresses">
                <Text style={type.kicker}>{t('lt.loc.savedAddresses')}</Text>
                <View style={styles.list}>
                  {addresses.data!.map((a) => (
                    <Choice
                      key={a.id}
                      icon="house-line"
                      title={a.label ? `${a.label} · ${a.street}` : a.street}
                      sub={[a.city, a.isDefault ? t('lt.loc.default') : null].filter(Boolean).join(' · ')}
                      label={[a.street, a.city, a.isDefault ? t('lt.loc.default') : null].filter(Boolean).join(', ')}
                      onPress={() => void choose(placeOfAddress(a))}
                      testID={`address-${a.id}`}
                    />
                  ))}
                </View>
              </View>
            ) : null}

            <View style={styles.gap}>
              <Text style={type.kicker}>{t('lt.loc.markets')}</Text>
              {markets.isPending ? (
                <Skeleton height={48} />
              ) : markets.isError ? (
                <Notice message={t('location.marketsError')} />
              ) : (
                <View style={styles.markets} testID="location-markets">
                  {live.map(({ m, p }) => (
                    <MarketChip key={m.id} m={m} p={p} t={t} onPress={() => void choose({ label: m.city, city: m.city, province: m.province, marketId: m.id, ...(m.lat != null && m.lng != null ? { lat: m.lat, lng: m.lng } : {}) })} />
                  ))}
                </View>
              )}
            </View>
          </ScrollView>
        </View>
      </KeyboardAvoidingView>
    </View>
  );
}

/** A row of the sheet's lists: an icon, the place on one or two lines. */
function Choice({ icon, title, sub, label, onPress, testID }: { icon: IconName; title: string; sub?: string; label: string; onPress: () => void; testID?: string }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} onPress={onPress} style={({ pressed }) => [styles.choice, pressed && styles.pressed]} testID={testID}>
      <Icon name={icon} size={20} color={colors.textMuted} />
      <View style={styles.flex}>
        <Text style={[type.body, type.strong]} numberOfLines={1}>
          {title}
        </Text>
        {sub ? (
          <Text style={type.small} numberOfLines={1}>
            {sub}
          </Text>
        ) : null}
      </View>
    </Pressable>
  );
}

function MarketChip({ m, p, t, onPress }: { m: Market; p: Province; t: T; onPress: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${m.city}, ${p.name}`}
      accessibilityHint={t('lt.loc.marketHint', { city: m.city })}
      onPress={onPress}
      style={({ pressed }) => [styles.market, pressed && styles.pressed]}
      testID={`market-${m.id}`}
    >
      <Icon name="storefront" size={16} color={semantic['action-primary']} />
      <Text style={styles.marketCity}>{m.city}</Text>
      <Text style={styles.marketProvince}>{p.name}</Text>
    </Pressable>
  );
}

/** The place found (an address picked, or where the phone is): its tags, the unit / buzzer note and "Deliver here". */
function Chosen({ t, place, detail, tags, unit, setUnit, onSave }: { t: T; place: string; detail?: string; tags?: ReactNode; unit: string; setUnit: (v: string) => void; onSave: () => void }) {
  return (
    <View style={styles.chosen} testID="location-chosen">
      <Text style={type.kicker}>{t('lt.loc.chosen')}</Text>
      <Text style={[type.item]}>{place}</Text>
      {detail ? <Body tone="small">{detail}</Body> : null}
      {tags}
      <Field
        label={t('location.unit')}
        placeholder={t('location.unitPlaceholder')}
        value={unit}
        onChangeText={setUnit}
        maxLength={120}
        autoComplete="street-address"
        testID="field-unit"
      />
      <Button label={t('lt.loc.useThis')} large onPress={onSave} testID="location-save" />
    </View>
  );
}

/** The tags: "Zone · {zone}", "{n} pooled runs / day", the province's sales tax. */
function Tags({ t, zone, runs, province }: { t: T; zone?: string | null; runs?: number | null; province?: Province }) {
  const tags: Array<{ label: string; tone: 'accent' | 'neutral' }> = [];
  if (zone) tags.push({ label: t('location.tagZone', { zone }), tone: 'accent' });
  if (runs) tags.push({ label: t('location.tagRuns', { count: runs }), tone: 'neutral' });
  if (province && zone) tags.push({ label: t('location.tax', { rate: Number((province.taxBps / 100).toFixed(2)) }), tone: 'neutral' });
  if (!tags.length) return null;
  return (
    <Row wrap>
      {tags.map((g) => (
        <Tag key={g.label} label={g.label} tone={g.tone} />
      ))}
    </Row>
  );
}

/** Outside a live market: the waitlist of the place's region, with an email (a guest's own, or the account's). */
function Waitlist({ t, address, onAnother }: { t: T; address: Address; onAnother: () => void }) {
  const r = address.resolution;
  const name = r.waitlist?.name ?? r.market?.city ?? address.city ?? '';
  const [email, setEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [joined, setJoined] = useState(false);
  const [busy, setBusy] = useState(false);
  const regionId = r.waitlist?.regionId ?? r.market?.id ?? null;

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
    <View style={styles.chosen} testID="waitlist">
      <Text style={[type.body, type.strong]}>{regionId ? t('location.notLiveTitle', { name }) : t('location.outside')}</Text>
      {joined ? (
        <Notice message={t('location.joined', { name })} tone="info" />
      ) : regionId ? (
        <>
          <Body tone="muted">{r.market?.stage === 'pilot' ? t('location.notLivePilot', { name }) : t('location.notLiveBody')}</Body>
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
          <Button label={t('location.join')} busy={busy} onPress={() => void join()} testID="waitlist-join" />
        </>
      ) : null}
      <Button label={t('lt.loc.chooseAnother')} tone="secondary" onPress={onAnother} />
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, minWidth: 0 },
  root: { flex: 1, justifyContent: 'flex-end' },
  scrim: { position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, backgroundColor: colors.overlay },
  sheetWrap: { maxHeight: '94%' },
  sheet: { borderTopLeftRadius: 20, borderTopRightRadius: 20, backgroundColor: colors.surface, ...shadow.lg, maxHeight: '100%' },
  grabber: { alignSelf: 'center', width: 40, height: 5, borderRadius: 3, marginTop: space[2], backgroundColor: colors.borderStrong },
  head: { flexDirection: 'row', alignItems: 'center', gap: space[3], paddingHorizontal: space[4], paddingTop: space[2], paddingBottom: space[2] },
  title: { flex: 1, fontFamily: fonts.display, fontSize: 24, lineHeight: 30, color: colors.text },
  body: { gap: space[4], paddingHorizontal: space[4], paddingBottom: space[4] },
  current: { flexDirection: 'row', gap: space[2], alignItems: 'flex-start', padding: space[3], borderRadius: radius.lg, backgroundColor: semantic['surface-sunken'] },
  here: {
    minHeight: 52,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: space[2],
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.borderControl,
    backgroundColor: colors.surface,
  },
  hereText: { fontFamily: fonts.bold, fontSize: 15, color: colors.text },
  pressed: { backgroundColor: semantic['surface-hover'] },
  gap: { gap: space[2] },
  list: { borderWidth: 1, borderColor: semantic['border-card'], borderRadius: radius.lg, backgroundColor: colors.surface, overflow: 'hidden' },
  listNote: { paddingHorizontal: space[4], paddingVertical: space[2] },
  choice: { minHeight: MIN_TARGET + 12, flexDirection: 'row', alignItems: 'center', gap: space[3], paddingHorizontal: space[4], paddingVertical: space[2], borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border },
  markets: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
  market: {
    minHeight: MIN_TARGET,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: space[4],
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.borderControl,
    backgroundColor: colors.surface,
  },
  marketCity: { fontFamily: fonts.bold, fontSize: 14, color: colors.text },
  marketProvince: { fontFamily: fonts.regular, fontSize: 14, color: colors.textMuted },
  chosen: { gap: space[3], padding: space[4], borderRadius: radius.lg, borderWidth: 1, borderColor: semantic['border-card'], backgroundColor: colors.surface },
});
