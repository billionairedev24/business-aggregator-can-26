import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { View } from 'react-native';

import { geoApi } from '../api/geo';
import { ACCESSIBILITY, DIETARY, type Prefs } from '../api/account';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n';
import { services } from '../services';
import { Chip } from '../shop/parts';
import { Body, Button, Field, Notice } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { LoadingList, QueryView, SignInPrompt, errorMessage } from '../ui/states';
import { KEYS, account, useAccountMutation } from './common';
import { Heading, accountStyles } from './parts';

/**
 * Region, dietary & accessibility (the consumer web's S-59 "Language & region" and "Dietary & accessibility" tabs,
 * reached from You; signed in): the province the person shops in (the region model's served provinces, or "follow my
 * location", which a chosen province can go back to: `province: ""`), units, the clock, dietary needs and allergies
 * (shops and kitchens see them on orders), accessibility needs and notes for providers (`GET`/`PATCH /me/preferences`,
 * only what changed). The language is You › "Language / Langue".
 */
export function Preferences() {
  const { t } = useI18n();
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const prefs = useQuery({ queryKey: KEYS.prefs, queryFn: () => account().prefs(), enabled: signedIn, staleTime: 300_000 });
  if (!signedIn) {
    return (
      <Screen title={t('account.prefs.title')} testID="preferences">
        <SignInPrompt message={t('account.prefs.signIn')} />
      </Screen>
    );
  }
  return (
    <Screen title={t('account.prefs.title')} testID="preferences">
      <QueryView query={prefs} skeleton={<LoadingList rows={6} height={44} />}>
        {(p) => <Form initial={p} />}
      </QueryView>
    </Screen>
  );
}

const toggleIn = (list: string[], v: string) => (list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);

function Form({ initial }: { initial: Prefs }) {
  const { t, locale } = useI18n();
  const regions = useQuery({ queryKey: ['geo', 'regions', locale], queryFn: () => geoApi(services().api).regions(locale === 'fr-CA' ? 'fr' : 'en'), staleTime: 3_600_000 });
  const [p, setP] = useState(initial);
  /** What the api has: the last answer, so a second save right after the first compares with it. */
  const [base, setBase] = useState(initial);
  const [saved, setSaved] = useState(false);
  const [errors, setErrors] = useState<{ allergies?: string; accessNotes?: string }>({});
  const save = useAccountMutation((c: Partial<Prefs>) => account().savePrefs(c), { set: KEYS.prefs });
  const update = (next: Partial<Prefs>) => {
    setP((x) => ({ ...x, ...next }));
    setSaved(false);
  };
  const submit = () => {
    const e: typeof errors = {};
    if ((p.allergies ?? '').length > 200) e.allergies = t('account.prefs.v.allergies');
    if ((p.accessNotes ?? '').length > 500) e.accessNotes = t('account.prefs.v.notes');
    setErrors(e);
    if (Object.keys(e).length) return;
    const change: Partial<Prefs> = {};
    if ((p.province ?? null) !== (base.province ?? null)) change.province = p.province ?? ''; // "" = follow my location
    if (p.units !== base.units) change.units = p.units;
    if (p.timeFormat !== base.timeFormat) change.timeFormat = p.timeFormat;
    if (p.dietary.join() !== base.dietary.join()) change.dietary = p.dietary;
    if ((p.allergies ?? '') !== (base.allergies ?? '')) change.allergies = p.allergies ?? '';
    if (p.accessibility.join() !== base.accessibility.join()) change.accessibility = p.accessibility;
    if ((p.accessNotes ?? '') !== (base.accessNotes ?? '')) change.accessNotes = p.accessNotes ?? '';
    save.mutate(change, {
      onSuccess: (next) => {
        setBase(next);
        setSaved(true);
      },
    });
  };

  return (
    <View style={accountStyles.form}>
      <Heading>{t('account.prefs.region')}</Heading>
      <Body tone="small">{t('account.prefs.provinceHint')}</Body>
      {regions.isPending ? (
        <LoadingList rows={1} height={40} />
      ) : (
        <View style={accountStyles.chips} accessibilityRole="radiogroup" accessibilityLabel={t('account.prefs.province')}>
          <Chip role="radio" label={t('account.prefs.followLocation')} on={!p.province} onPress={() => update({ province: null })} testID="province-auto" />
          {(regions.data?.provinces ?? [])
            .filter((r) => r.status === 'live' || r.status === 'pilot' || r.code === base.province)
            .map((r) => (
              <Chip key={r.code} role="radio" label={r.name} on={p.province === r.code} onPress={() => update({ province: r.code })} testID={`province-${r.code}`} />
            ))}
        </View>
      )}
      {!p.province ? <Body tone="small">{t('account.prefs.followLocationHint')}</Body> : null}
      <Body tone="small">{t('account.prefs.units')}</Body>
      <View style={accountStyles.chips} accessibilityRole="radiogroup" accessibilityLabel={t('account.prefs.units')}>
        {(['metric', 'imperial'] as const).map((u) => (
          <Chip key={u} role="radio" label={t(`account.prefs.unit.${u}`)} on={p.units === u} onPress={() => update({ units: u })} testID={`units-${u}`} />
        ))}
      </View>
      <Body tone="small">{t('account.prefs.clock')}</Body>
      <View style={accountStyles.chips} accessibilityRole="radiogroup" accessibilityLabel={t('account.prefs.clock')}>
        {(['12h', '24h'] as const).map((c) => (
          <Chip key={c} role="radio" label={t(`account.prefs.clock.${c}`)} on={p.timeFormat === c} onPress={() => update({ timeFormat: c })} testID={`clock-${c}`} />
        ))}
      </View>

      <Heading>{t('account.prefs.dietary')}</Heading>
      <View style={accountStyles.chips}>
        {DIETARY.map((d) => (
          <Chip key={d} label={t(`account.prefs.diet.${d}`)} on={p.dietary.includes(d)} onPress={() => update({ dietary: toggleIn(p.dietary, d) })} testID={`diet-${d}`} />
        ))}
      </View>
      <Field label={t('account.prefs.allergies')} value={p.allergies ?? ''} onChangeText={(v) => update({ allergies: v })} maxLength={210} error={errors.allergies} testID="allergies" />

      <Heading>{t('account.prefs.accessibility')}</Heading>
      <View style={accountStyles.chips}>
        {ACCESSIBILITY.map((a) => (
          <Chip key={a} label={t(`account.prefs.access.${a}`)} on={p.accessibility.includes(a)} onPress={() => update({ accessibility: toggleIn(p.accessibility, a) })} testID={`access-${a}`} />
        ))}
      </View>
      <Field label={t('account.prefs.accessNotes')} value={p.accessNotes ?? ''} onChangeText={(v) => update({ accessNotes: v })} multiline maxLength={510} error={errors.accessNotes} testID="access-notes" />

      {save.error ? <Notice message={errorMessage(save.error, t)} /> : null}
      {saved ? <Notice tone="info" message={t('account.prefs.saved')} testID="prefs-saved" /> : null}
      <Button label={t('account.prefs.save')} busy={save.isPending} onPress={submit} testID="prefs-save" />
    </View>
  );
}
