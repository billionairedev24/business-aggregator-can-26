import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { Button, Dialog, EmptyState, ErrorState, Field, FormGrid, Select, Tag, TextInput } from '@northline/ui';
import { serverFieldErrors } from '@northline/client';
import { FormSkeleton } from './ProfileTab';
import { addressesQuery, householdQuery, useAddAddress, useChangeAddress, useDefaultAddress, useRemoveAddress, type Address } from './settingsApi';
import { useSettingsT, type SettingsT } from './settingsMessages';

export const PROVINCES = ['AB', 'BC', 'MB', 'NB', 'NL', 'NS', 'NT', 'NU', 'ON', 'PE', 'QC', 'SK', 'YT'] as const;
const FIELDS = ['label', 'street', 'unit', 'city', 'province', 'postal', 'note'] as const;
type Values = Record<(typeof FIELDS)[number], string>;
const EMPTY: Values = { label: '', street: '', unit: '', city: '', province: '', postal: '', note: '' };

/** Checkout's address rules (S-51), worded in the reader's language. */
const schema = (t: SettingsT, editing: boolean) => z.object({
  label: z.string().trim().max(40, t('v_label')),
  street: editing ? z.string() : z.string().trim().min(1, t('v_street')).max(120, t('v_street')),
  unit: z.string().trim().max(20, t('v_unit')),
  city: editing ? z.string() : z.string().trim().min(1, t('v_city')).max(60, t('v_city')),
  province: editing ? z.string() : z.enum(PROVINCES, { message: t('v_province') }),
  postal: editing ? z.string() : z.string().trim().regex(/^[A-Za-z]\d[A-Za-z][ -]?\d[A-Za-z]\d$/, t('v_postal')),
  note: z.string().trim().max(200, t('v_note')),
});


const line = (a: Address) => {
  const place = [a.street, a.unit].filter(Boolean).join(', ');
  return [a.label, place, a.note].filter(Boolean).join(' · ');
};

/** Addresses & household (design 06 `at.addresses`): the address book and who shares the household. */
export function AddressesTab() {
  const t = useSettingsT();
  const list = useQuery(addressesQuery);
  const household = useQuery(householdQuery);
  const makeDefault = useDefaultAddress();
  const [editing, setEditing] = useState<Address | 'new'>();
  const [manage, setManage] = useState(false);
  const others = (household.data?.members ?? []).filter(m => !m.you);
  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t('addrTitle')}</h1>
      {list.isPending ? <FormSkeleton label={t('loading')} rows={3} />
        : list.isError ? <ErrorState message={t('loadError')} onRetry={() => void list.refetch()} />
          : (
            <ul className="nl-acct-rows" aria-label={t('addrTitle')}>
              {list.data.length === 0 ? <li><EmptyState>{t('addrEmpty')}</EmptyState></li> : null}
              {list.data.map(a => (
                <li key={a.id} className="nl-acct-row">
                  <span>{line(a)}</span>
                  <span className="nl-pm-actions">
                    {a.isDefault ? <Tag tone="accent">{t('addrDefault')}</Tag>
                      : <Button type="button" variant="ghost" disabled={makeDefault.isPending} onClick={() => makeDefault.mutate(a.id)}>{t('makeDefaultAddress')}</Button>}
                    <Button type="button" variant="ghost" aria-label={`${t('edit')} · ${a.street}`} onClick={() => setEditing(a)}>{t('edit')}</Button>
                  </span>
                </li>
              ))}
              {household.isSuccess ? (
                <li className="nl-acct-row">
                  <span>{others.length ? t('members', { names: others.map(m => `${m.name.split(' ')[0]} (${household.data.plan !== 'none' ? `${t('sharesPlus')}, ` : ''}${t('ownLogin')})`).join(', ') }) : t('membersNone')}</span>
                  <Button type="button" variant="ghost" onClick={() => setManage(true)}>{t('manage')}</Button>
                </li>
              ) : null}
            </ul>
          )}
      <Button type="button" variant="secondary" className="nl-pm-add" onClick={() => setEditing('new')}>{t('addAddress')}</Button>
      {editing ? <AddressDialog address={editing === 'new' ? undefined : editing} onClose={() => setEditing(undefined)} /> : null}
      <Dialog open={manage} onClose={() => setManage(false)} title={t('householdTitle')} actions={<Button type="button" variant="ghost" onClick={() => setManage(false)}>{t('close')}</Button>}>
        <ul className="nl-acct-rows">
          {(household.data?.members ?? []).map(m => <li key={m.userId} className="nl-acct-row"><span>{m.name}{m.you ? ` (${t('you')})` : ''}</span></li>)}
        </ul>
        <p className="nl-small nl-muted">{t('householdBody')}</p>
      </Dialog>
    </>
  );
}

function AddressDialog({ address, onClose }: { address?: Address; onClose: () => void }) {
  const t = useSettingsT();
  const add = useAddAddress();
  const change = useChangeAddress();
  const remove = useRemoveAddress();
  const editing = !!address;
  const [values, setValues] = useState<Values>(() => address
    ? { label: address.label ?? '', street: address.street, unit: address.unit ?? '', city: address.city, province: address.province, postal: address.postal, note: address.note ?? '' }
    : EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const set = (k: keyof Values) => (v: string) => setValues(x => ({ ...x, [k]: v }));
  const busy = add.isPending || change.isPending || remove.isPending;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const parsed = schema(t, editing).safeParse(values);
    if (!parsed.success) {
      const out: Record<string, string> = {};
      for (const issue of parsed.error.issues) out[String(issue.path[0])] ??= issue.message;
      setErrors(out);
      return;
    }
    setErrors({});
    const onError = (err: unknown) => { const f = serverFieldErrors(err, FIELDS); setErrors(Object.keys(f).length ? f : { _form: t('saveError') }); };
    if (address) change.mutate({ id: address.id, label: values.label, unit: values.unit, note: values.note }, { onSuccess: onClose, onError });
    else add.mutate({ label: values.label || undefined, street: values.street, unit: values.unit || undefined, city: values.city, province: values.province, postal: values.postal, note: values.note || undefined }, { onSuccess: onClose, onError });
  };
  return (
    <Dialog open onClose={onClose} title={editing ? t('editAddress') : t('newAddress')}
      actions={<>
        {address ? <Button type="button" variant="ghost" className="nl-danger" disabled={busy} onClick={() => remove.mutate(address.id, { onSuccess: onClose })}>{t('removeAddress')}</Button> : null}
        <Button type="button" variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button type="submit" form="nl-address-form" disabled={busy} aria-busy={busy}>{t('saveAddress')}</Button>
      </>}>
      <form id="nl-address-form" onSubmit={submit} noValidate>
        <FormGrid min={200}>
          <Field label={t('label')} hint={t('labelHint')} error={errors.label}><TextInput value={values.label} onChange={e => set('label')(e.target.value)} /></Field>
          <Field label={t('street')} error={errors.street}><TextInput value={values.street} readOnly={editing} onChange={e => set('street')(e.target.value)} autoComplete="address-line1" /></Field>
          <Field label={t('unit')} error={errors.unit}><TextInput value={values.unit} onChange={e => set('unit')(e.target.value)} autoComplete="address-line2" /></Field>
          <Field label={t('city')} error={errors.city}><TextInput value={values.city} readOnly={editing} onChange={e => set('city')(e.target.value)} autoComplete="address-level2" /></Field>
          <Field label={t('province')} error={errors.province}>
            <Select value={values.province} disabled={editing} onChange={e => set('province')(e.target.value)} placeholder="—" options={PROVINCES.map(p => ({ value: p, label: p }))} />
          </Field>
          <Field label={t('postal')} error={errors.postal}><TextInput value={values.postal} readOnly={editing} onChange={e => set('postal')(e.target.value)} autoComplete="postal-code" /></Field>
          <Field label={t('note')} hint={t('noteHint')} error={errors.note} span><TextInput value={values.note} onChange={e => set('note')(e.target.value)} /></Field>
        </FormGrid>
        {errors._form ? <p className="nl-error" role="alert">{errors._form}</p> : null}
      </form>
    </Dialog>
  );
}
