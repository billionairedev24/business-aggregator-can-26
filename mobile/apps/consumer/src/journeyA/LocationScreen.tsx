import { useQuery } from '@tanstack/react-query';
import * as Location from 'expo-location';
import * as Linking from 'expo-linking';
import { router, useLocalSearchParams } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { ApiError, MIN_TARGET, colors, fonts, radius, randomToken, space } from '@northline/mobile-kit';

import { geoApi, type Address, type Province } from '../api/geo';
import { EMAIL_PATTERN } from '../auth/rules';
import { useI18n, type MessageKey } from '../i18n';
import { nameOf, useDeliveryLocation, type DeliveryLocation, type SavedLocation } from '../location/DeliveryLocation';
import { services } from '../services';
import { Body, Button, Field, Notice, Row, Tag, Title, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { ErrorState, Skeleton } from '../ui/states';

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

/**
 * A5 Province & address (design 01 `location`; the api of S-47 and the region model of S-134): the provinces with
 * their stage (live and pilot can be picked; waitlist and not-yet are shown, greyed), the street address with Google
 * suggestions through the api (Canada only, one session token per search), the unit / buzzer / drop-off note, the
 * zone, pooled runs and sales tax it resolves to, Save — or the waitlist outside a live market. "Use my location"
 * asks for the location permission (only here, only then); refused or unknown, the api's fallback market stands.
 */
export function LocationScreen() {
  const { t } = useI18n();
  const { next } = useLocalSearchParams<{ next?: string }>();
  const { location, save } = useDeliveryLocation();
  const geo = geoApi(services().api);
  const markets = useQuery({ queryKey: ['geo', 'markets'], queryFn: () => geo.markets(), staleTime: 300_000 });

  const [province, setProvince] = useState<string | null>(null);
  const savedQuery = location.status === 'saved' ? (location.street ? `${location.street}, ${location.city}` : (location.label ?? '')) : '';
  const [query, setQuery] = useState(savedQuery);
  const [session, setSession] = useState(newSession);
  const [placeId, setPlaceId] = useState<string | null>(null);
  const [listOpen, setListOpen] = useState(false);
  const [detected, setDetected] = useState<DeliveryLocation | null>(null);
  const [unit, setUnit] = useState(location.status === 'saved' ? (location.unit ?? '') : '');
  const [error, setError] = useState<string | null>(null);
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

  const items = markets.data?.items ?? [];
  const provinceOf = (code?: string | null) => items.find((p) => p.code === code);
  const shownProvince = picked?.province ?? province ?? detected?.province ?? location.province ?? items[0]?.code ?? null;
  const live = picked ? picked.resolution.market?.stage === 'live' : !!detected || location.status === 'saved';

  const onQuery = (v: string) => {
    setQuery(v);
    setListOpen(true);
    setError(null);
    setDetected(null);
    if (placeId) {
      setPlaceId(null);
      setSession(newSession()); // a new search, a new Google session
    }
  };

  const choose = (id: string, label: string) => {
    setPlaceId(id);
    setQuery(label);
    setListOpen(false);
    setError(null);
  };

  const locateMe = async () => {
    setNote(null);
    setLocating(true);
    try {
      const asked = await Location.requestForegroundPermissionsAsync();
      if (!asked.granted) {
        setNote({ text: t('location.denied'), settings: !asked.canAskAgain });
        return;
      }
      const fix = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
      const named = await nameOf(fix.coords.latitude, fix.coords.longitude);
      if (!named) {
        setNote({ text: t('location.unplaced') });
        return;
      }
      setPlaceId(null);
      setDetected(named);
      setQuery(named.label ?? '');
      setListOpen(false);
      if (named.province) setProvince(named.province);
    } catch {
      setNote({ text: t('location.unplaced') });
    } finally {
      setLocating(false);
    }
  };

  const onSave = async () => {
    const back = typeof next === 'string' && next.startsWith('/') && !next.startsWith('//') ? next : '/home';
    const extra = unit.trim() ? { unit: unit.trim() } : {};
    if (picked) {
      const r = picked.resolution;
      if (!r.market || r.market.stage !== 'live') return; // the waitlist panel is shown instead
      await save({
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
    } else if (detected?.label && detected.city) {
      await save({ ...stripStatus(detected), ...extra });
    } else if (location.status === 'saved' && location.label && location.city && query === savedQuery) {
      await save({ ...stripStatus(location), ...extra }); // the same address; only the note may have changed
    } else {
      setError(t('location.pickFromList'));
      return;
    }
    router.replace(back);
  };

  const showList = listOpen && typed.length >= 3 && !placeId;

  return (
    <Screen
      title={t('title.location')}
      testID="location"
      footer={
        <>
          {note ? (
            <View style={styles.gap}>
              <Notice message={note.text} tone="info" />
              {note.settings ? <Button label={t('location.openSettings')} tone="secondary" onPress={() => void Linking.openSettings()} /> : null}
            </View>
          ) : null}
          {picked && !live ? null : (
            <Button label={t('location.save')} large disabled={address.isFetching} onPress={() => void onSave()} testID="location-save" />
          )}
          <Button label={t('location.useMine')} tone="ghost" busy={locating} onPress={() => void locateMe()} testID="location-use-mine" />
        </>
      }
    >
      <Title>{t('location.title')}</Title>
      <Body tone="muted">{t('location.lede')}</Body>

      {location.status === 'fallback' && location.city && !picked && !detected ? <Body tone="small">{t('location.fallback', { city: location.city })}</Body> : null}

      <Provinces t={t} query={markets} value={shownProvince} onChange={setProvince} />

      <View style={styles.gap}>
        <Field
          label={t('location.street')}
          placeholder={t('location.streetPlaceholder')}
          value={query}
          onChangeText={onQuery}
          onFocus={() => setListOpen(true)}
          error={error ?? (address.isError ? t('location.addressError') : null)}
          autoComplete="street-address"
          textContentType="fullStreetAddress"
          autoCorrect={false}
          testID="field-street"
        />
        {showList ? (
          <View accessibilityLabel={t('location.suggestions')} style={styles.suggest} testID="suggestions">
            {(suggestions.data?.items ?? []).map((s) => (
              <Pressable
                key={s.placeId}
                accessibilityRole="button"
                accessibilityLabel={`${s.main}, ${s.secondary}`}
                onPress={() => choose(s.placeId, `${s.main}, ${s.secondary.split(',')[0]}`)}
                style={({ pressed }) => [styles.option, pressed && styles.optionPressed]}
              >
                <Text style={[type.body, type.strong]}>{s.main}</Text>
                <Text style={type.small}>{s.secondary}</Text>
              </Pressable>
            ))}
            {suggestions.isFetching && !suggestions.data?.items.length ? <Body tone="small">{t('location.searching')}</Body> : null}
            {suggestions.isSuccess && !suggestions.isFetching && suggestions.data?.items.length === 0 ? <Body tone="small">{t('location.noMatch', { q: typed })}</Body> : null}
            {suggestions.isError ? (
              <Body tone="small">
                {suggestions.error instanceof ApiError && suggestions.error.status === 429 ? t('location.tooMany') : t('location.lookupError')}
              </Body>
            ) : null}
            {suggestions.data ? <Body tone="small">{t('location.poweredBy', { name: suggestions.data.attribution })}</Body> : null}
          </View>
        ) : null}
        {address.isFetching ? <Body tone="small">{t('location.resolving')}</Body> : null}
      </View>

      <Field
        label={t('location.unit')}
        placeholder={t('location.unitPlaceholder')}
        value={unit}
        onChangeText={setUnit}
        maxLength={120}
        autoComplete="street-address"
        testID="field-unit"
      />

      <Tags
        t={t}
        zone={picked?.resolution.zone?.name ?? detected?.zone ?? (location.status === 'saved' ? location.zone : undefined)}
        runs={picked?.resolution.zone?.runsPerDay ?? null}
        province={provinceOf(shownProvince)}
      />

      {picked && !live ? <Waitlist t={t} address={picked} /> : null}
    </Screen>
  );
}

/** A known place without its status, as the store keeps it (the caller checked label and city). */
function stripStatus(l: DeliveryLocation): SavedLocation {
  const { status: _status, label = '', city = '', ...rest } = l;
  return { label, city, ...rest };
}

/** The design's 2-column province grid: live and pilot pickable, waitlist and not-yet greyed with their stage. */
function Provinces({ t, query, value, onChange }: { t: T; query: { data?: { items: Province[] } | null; isPending: boolean; isError: boolean; error: unknown; refetch: () => unknown }; value: string | null; onChange: (code: string) => void }) {
  if (query.isPending) {
    return (
      <View style={styles.grid} accessibilityLabel={t('common.loading')}>
        {[0, 1, 2, 3].map((i) => (
          <View key={i} style={styles.cell}>
            <Skeleton height={56} />
          </View>
        ))}
      </View>
    );
  }
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => void query.refetch()} />;
  return (
    <View accessibilityRole="radiogroup" accessibilityLabel={t('location.provinces')} style={styles.grid}>
      {query.data.items.map((p) => {
        const open = p.stage === 'live' || p.stage === 'pilot';
        const selected = value === p.code;
        return (
          <View key={p.code} style={styles.cell}>
            <Pressable
              accessibilityRole="radio"
              accessibilityLabel={`${p.name}, ${stageNote(t, p)}`}
              accessibilityState={{ checked: selected, disabled: !open }}
              disabled={!open}
              onPress={() => onChange(p.code)}
              style={[styles.province, selected && styles.provinceOn, !open && styles.provinceOff]}
              testID={`province-${p.code}`}
            >
              <Text style={type.body}>{p.name}</Text>
              <Text style={styles.provinceNote}>{stageNote(t, p)}</Text>
            </Pressable>
          </View>
        );
      })}
    </View>
  );
}

/** "Live · {first two live markets}" (as the design), "Pilot · invite only", "Waitlist", "Not yet". */
function stageNote(t: T, p: Province): string {
  switch (p.stage) {
    case 'live': {
      const cities = p.markets.filter((m) => m.stage === 'live').slice(0, 2).map((m) => m.city).join(', ');
      return cities ? t('location.stageLive', { cities }) : t('location.stageLiveOnly');
    }
    case 'pilot':
      return t('location.stagePilot');
    case 'waitlist':
      return t('location.stageWaitlist');
    default:
      return t('location.stageOff');
  }
}

/** The design's tags: "Zone · {zone}", "{n} pooled runs / day", the province's sales tax. */
function Tags({ t, zone, runs, province }: { t: T; zone?: string | null; runs?: number | null; province?: Province }) {
  const tags = useMemo(() => {
    const out: Array<{ label: string; tone: 'accent' | 'neutral' }> = [];
    if (zone) out.push({ label: t('location.tagZone', { zone }), tone: 'accent' });
    if (runs) out.push({ label: t('location.tagRuns', { count: runs }), tone: 'neutral' });
    if (province && zone) out.push({ label: t('location.tax', { rate: Number((province.taxBps / 100).toFixed(2)) }), tone: 'neutral' });
    return out;
  }, [t, zone, runs, province]);
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
function Waitlist({ t, address }: { t: T; address: Address }) {
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
    <View style={styles.waitlist} testID="waitlist">
      <Text style={[type.body, type.strong]}>{regionId ? t('location.notLiveTitle', { name }) : t('location.outside')}</Text>
      {joined ? (
        <>
          <Notice message={t('location.joined', { name })} tone="info" />
          <Button label={t('location.keepBrowsing')} tone="secondary" onPress={() => router.replace('/home')} />
        </>
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
      ) : (
        <Button label={t('location.keepBrowsing')} tone="secondary" onPress={() => router.replace('/home')} />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  gap: { gap: space[2] },
  grid: { flexDirection: 'row', flexWrap: 'wrap', marginHorizontal: -4 },
  cell: { width: '50%', padding: 4 },
  province: {
    minHeight: MIN_TARGET + 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.divider,
    gap: 2,
  },
  provinceOn: { borderColor: colors.accent, backgroundColor: colors.accent100 },
  provinceOff: { opacity: 0.45 },
  provinceNote: { fontFamily: fonts.body, fontSize: 11, color: colors.neutral700 },
  suggest: { borderWidth: 1, borderColor: colors.divider, borderRadius: radius.md, backgroundColor: colors.surface, paddingVertical: space[1] },
  option: { minHeight: MIN_TARGET, paddingHorizontal: 14, paddingVertical: 8, justifyContent: 'center' },
  optionPressed: { backgroundColor: colors.accent100 },
  waitlist: { gap: space[3], padding: space[4], borderRadius: radius.md, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.divider },
});
