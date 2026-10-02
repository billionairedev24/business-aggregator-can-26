import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { ApiError, space } from '@northline/mobile-kit';

import { geoApi } from '../api/geo';
import type { Address, AddressInput } from '../api/account';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n';
import { services } from '../services';
import { serverMessage } from '../shop/common';
import { Chip } from '../shop/parts';
import { Body, Button, Field, Notice, Tag, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, LoadingList, QueryView, SignInPrompt, errorMessage } from '../ui/states';
import { KEYS, account, useAccountMutation } from './common';
import { Heading, accountStyles } from './parts';

const POSTAL = /^[A-Za-z]\d[A-Za-z][ -]?\d[A-Za-z]\d$/;
type Errors = Partial<Record<keyof AddressInput, string>>;

/**
 * Addresses & household (design 01 You › "Addresses & household", the consumer web's S-59 tab; signed in): the address
 * book (`GET /me/addresses`, default first) — make one the default, remove one, add one (the api's rules, checked
 * here first with its words; provinces from the region model, never from code) — and the household that shares
 * Northline Plus (`GET /me/household`). The address on this phone (Location, S-98) stays this phone's.
 */
export function Addresses() {
  const { t } = useI18n();
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const list = useQuery({ queryKey: KEYS.addresses, queryFn: () => account().addresses(), enabled: signedIn, staleTime: 60_000 });
  const household = useQuery({ queryKey: KEYS.household, queryFn: () => account().household(), enabled: signedIn, staleTime: 300_000 });
  const makeDefault = useAccountMutation((id: string) => account().defaultAddress(id), { set: KEYS.addresses });
  const remove = useAccountMutation((id: string) => account().removeAddress(id), { set: KEYS.addresses });
  const [adding, setAdding] = useState(false);

  if (!signedIn) {
    return (
      <Screen title={t('account.addresses.title')} testID="addresses">
        <SignInPrompt message={t('account.addresses.signIn')} />
      </Screen>
    );
  }
  const failed = makeDefault.error ?? remove.error;
  return (
    <Screen title={t('account.addresses.title')} testID="addresses">
      {failed ? <Notice message={errorMessage(failed, t)} /> : null}
      <QueryView
        query={list}
        skeleton={<LoadingList rows={2} height={64} />}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState message={t('account.addresses.empty')} />}
      >
        {(items) => (
          <View accessibilityRole="list">
            {items.map((a) => (
              <AddressRow key={a.id} address={a} busy={makeDefault.isPending || remove.isPending} onDefault={() => makeDefault.mutate(a.id)} onRemove={() => remove.mutate(a.id)} />
            ))}
          </View>
        )}
      </QueryView>
      {adding ? <AddAddress onDone={() => setAdding(false)} /> : <Button label={t('account.addresses.add')} tone="secondary" onPress={() => setAdding(true)} testID="address-add" />}

      <Heading>{t('account.addresses.household')}</Heading>
      <QueryView query={household} skeleton={<LoadingList rows={1} height={40} />}>
        {(h) => (
          <View>
            {h.members.map((m) => (
              <View key={m.userId} style={styles.member}>
                <Text style={type.body}>{m.you ? t('account.addresses.you', { name: m.name }) : m.name}</Text>
                <Text style={type.small}>{t(m.role === 'owner' ? 'account.addresses.owner' : 'account.addresses.member')}</Text>
              </View>
            ))}
            <Body tone="small">{t(h.plan === 'none' ? 'account.addresses.noPlus' : 'account.addresses.plusShared')}</Body>
          </View>
        )}
      </QueryView>
    </Screen>
  );
}

function AddressRow({ address: a, busy, onDefault, onRemove }: { address: Address; busy: boolean; onDefault: () => void; onRemove: () => void }) {
  const { t } = useI18n();
  const line = [a.unit ? `${a.unit} – ${a.street}` : a.street, `${a.city}, ${a.province} ${a.postal}`].join(', ');
  return (
    <View style={styles.address} testID={`address-${a.id}`}>
      <View style={styles.head}>
        <Text style={[type.body, type.strong, styles.flex]}>{a.label || a.street}</Text>
        {a.isDefault ? <Tag label={t('account.addresses.default')} tone="accent" /> : null}
      </View>
      <Text style={type.small}>{line}</Text>
      {a.note ? <Text style={type.small}>{a.note}</Text> : null}
      <View style={styles.actions}>
        {!a.isDefault ? <Button label={t('account.addresses.makeDefault')} tone="ghost" disabled={busy} onPress={onDefault} testID={`address-default-${a.id}`} /> : null}
        <Button label={t('account.addresses.remove')} tone="danger" hint={line} disabled={busy} onPress={onRemove} testID={`address-remove-${a.id}`} />
      </View>
    </View>
  );
}

function AddAddress({ onDone }: { onDone: () => void }) {
  const { t, locale } = useI18n();
  const regions = useQuery({ queryKey: ['geo', 'regions', locale], queryFn: () => geoApi(services().api).regions(locale === 'fr-CA' ? 'fr' : 'en'), staleTime: 3_600_000 });
  const [a, setA] = useState<AddressInput>({ label: '', street: '', unit: '', city: '', province: '', postal: '', note: '' });
  const [errors, setErrors] = useState<Errors>({});
  const add = useAccountMutation((input: AddressInput) => account().addAddress(input), { refresh: [KEYS.addresses] });
  const set = (k: keyof AddressInput) => (v: string) => setA((x) => ({ ...x, [k]: v }));

  const submit = () => {
    const e: Errors = {};
    if (!a.street.trim() || a.street.trim().length > 120) e.street = t('shop.srv.street');
    if (!a.city.trim() || a.city.trim().length > 60) e.city = t('shop.srv.city');
    if (!a.province) e.province = t('shop.srv.province');
    if (!POSTAL.test(a.postal.trim())) e.postal = t('shop.srv.postal');
    if ((a.unit ?? '').trim().length > 20) e.unit = t('shop.srv.unit');
    if ((a.note ?? '').trim().length > 200) e.note = t('shop.srv.note');
    if ((a.label ?? '').trim().length > 40) e.label = t('account.addresses.v.label');
    setErrors(e);
    if (Object.keys(e).length) return;
    const clean = (v?: string) => v?.trim() || undefined;
    add.mutate(
      { label: clean(a.label), street: a.street.trim(), unit: clean(a.unit), city: a.city.trim(), province: a.province, postal: a.postal.trim().toUpperCase(), note: clean(a.note) },
      {
        onSuccess: onDone,
        onError: (err) => {
          if (err instanceof ApiError && err.status === 422) {
            const out: Errors = {};
            for (const fe of err.errors) out[fe.field as keyof AddressInput] = serverMessage(fe.message, t);
            setErrors(out);
          }
        },
      },
    );
  };
  const provinces = regions.data?.provinces ?? [];
  return (
    <View style={accountStyles.form} testID="address-form">
      <Heading>{t('account.addresses.new')}</Heading>
      <Field label={t('account.addresses.label')} value={a.label} onChangeText={set('label')} placeholder={t('account.addresses.labelPlaceholder')} error={errors.label} testID="address-label" />
      <Field label={t('account.addresses.street')} value={a.street} onChangeText={set('street')} autoComplete="street-address" error={errors.street} testID="address-street" />
      <Field label={t('account.addresses.unit')} value={a.unit} onChangeText={set('unit')} error={errors.unit} testID="address-unit" />
      <Field label={t('account.addresses.city')} value={a.city} onChangeText={set('city')} error={errors.city} testID="address-city" />
      <Body tone="small">{t('account.addresses.province')}</Body>
      {regions.isPending ? (
        <LoadingList rows={1} height={40} />
      ) : (
        <View style={accountStyles.chips} accessibilityRole="radiogroup" accessibilityLabel={t('account.addresses.province')}>
          {provinces.map((p) => (
            <Chip key={p.code} role="radio" label={p.name} on={a.province === p.code} onPress={() => set('province')(p.code)} testID={`province-${p.code}`} />
          ))}
        </View>
      )}
      {errors.province ? <Notice message={errors.province} /> : null}
      <Field label={t('account.addresses.postal')} value={a.postal} onChangeText={set('postal')} autoCapitalize="characters" autoComplete="postal-code" error={errors.postal} testID="address-postal" />
      <Field label={t('account.addresses.note')} value={a.note} onChangeText={set('note')} error={errors.note} testID="address-note" />
      {add.error && !(add.error instanceof ApiError && add.error.status === 422) ? <Notice message={errorMessage(add.error, t)} /> : null}
      <Button label={t('account.addresses.save')} busy={add.isPending} onPress={submit} testID="address-save" />
      <Button label={t('account.addresses.cancel')} tone="ghost" onPress={onDone} />
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, minWidth: 0 },
  address: { paddingVertical: space[2], gap: 2 },
  head: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
  member: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', minHeight: 40, gap: space[2] },
});
