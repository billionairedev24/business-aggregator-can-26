import { useState } from 'react';
import { Button, Checkbox, Dialog, Field, Select, TextInput, useLocale } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useRegions } from '../shell/api';
import { useApproveSuggestion, useCreateCategory, useRegulate, useUpdateCategory, type Category, type CategoryInput, type Screen, type Suggestion, useClassify } from './api';
import { errorText } from './format';
import { useTaxonomyT, type TaxonomyKey } from './messages';

const BOOKING = ['visit', 'home', 'event', 'appointment', 'consult'] as const;
const ROOTS = ['service', 'shop', 'food'] as const;

/**
 * Add a category, edit one (with its regulator in each province of the region model), or approve a business's
 * suggestion as a new category. The api validates; its messages show under the fields.
 */
export function CategoryDialog({ data, category, suggestion, onClose, onResolved }: {
  data: Screen; category?: Category; suggestion?: Suggestion; onClose: () => void; onResolved?: (text: string) => void;
}) {
  const t = useTaxonomyT();
  const { locale } = useLocale();
  const create = useCreateCategory();
  const update = useUpdateCategory();
  const approve = useApproveSuggestion();
  const [root, setRoot] = useState<string>(category?.root ?? 'service');
  const [parentId, setParentId] = useState(category?.parentId ?? '');
  const [nameEn, setNameEn] = useState(category?.nameEn ?? suggestion?.name ?? '');
  const [nameFr, setNameFr] = useState(category?.nameFr ?? '');
  const [bookingType, setBookingType] = useState(category?.bookingType ?? '');
  const [registry, setRegistry] = useState(category?.regulatedRegistry ?? '');
  const [vs, setVs] = useState(category?.requiresVsCheck ?? false);
  const run = category ? update : suggestion ? approve : create;
  const errors = run.error instanceof ValidationError ? run.error.byField() : {};
  const groupName = (c: Category) => (locale === 'fr' ? c.nameFr ?? c.nameEn : c.nameEn);
  const groups = data.categories.filter(c => c.group && c.root === root).sort((a, b) => groupName(a).localeCompare(groupName(b)));
  const input: CategoryInput = {
    root, parentId: parentId || undefined, nameEn, nameFr: nameFr.trim() || undefined, bookingType: root === 'service' ? bookingType || undefined : undefined,
    regulatedRegistry: registry.trim() || undefined, requiresVsCheck: vs,
  };
  const submit = () => {
    if (category) update.mutate({ id: category.id, ...input }, { onSuccess: onClose });
    else if (suggestion) approve.mutate({ id: suggestion.id, ...input }, { onSuccess: r => { onResolved?.(t('resolved', { n: r.moved + r.alreadyHeld, name: r.category.nameEn })); onClose(); } });
    else create.mutate(input, { onSuccess: onClose });
  };
  const title = category ? t('editTitle', { name: category.nameEn }) : suggestion ? t('approveTitle', { name: suggestion.name }) : t('newTitle');
  return (
    <Dialog open onClose={onClose} title={title} width={640}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button><Button disabled={run.isPending} onClick={submit}>{category ? t('save') : t('create')}</Button></>}>
      {!category ? (
        <>
          <Field label={t('f_root')} error={errors.root}>
            <Select value={root} options={ROOTS.map(r => ({ value: r, label: t(`root_${r}` as TaxonomyKey) }))} onChange={e => { setRoot(e.target.value); setParentId(''); }} />
          </Field>
          <Field label={t('f_group')} error={errors.parentId}>
            <Select value={parentId} placeholder={suggestion ? undefined : t('f_groupNone')} options={groups.map(g => ({ value: g.id, label: groupName(g) }))}
              onChange={e => setParentId(e.target.value)} />
          </Field>
        </>
      ) : null}
      <Field label={t('f_nameEn')} error={errors.nameEn}><TextInput value={nameEn} maxLength={80} onChange={e => setNameEn(e.target.value)} /></Field>
      <Field label={t('f_nameFr')} error={errors.nameFr}><TextInput value={nameFr} maxLength={80} onChange={e => setNameFr(e.target.value)} /></Field>
      {root === 'service' ? (
        <Field label={t('f_booking')} error={errors.bookingType}>
          <Select value={bookingType} placeholder={t('f_bookingNone')} options={BOOKING.map(b => ({ value: b, label: t(`booking_${b}` as TaxonomyKey) }))}
            onChange={e => setBookingType(e.target.value)} />
        </Field>
      ) : null}
      <Field label={t('f_registry')} error={errors.regulatedRegistry}><TextInput value={registry} maxLength={80} onChange={e => setRegistry(e.target.value)} /></Field>
      <Checkbox checked={vs} onChange={setVs} label={t('f_vs')} />
      {category && category.root !== 'service' ? <AgeClassField data={data} category={category} /> : null}
      {category ? <ProvinceRules data={data} category={category} /> : null}
      {run.error && !(run.error instanceof ValidationError) ? <p role="alert" className="nl-tx-error">{errorText(run.error)}</p> : null}
    </Dialog>
  );
}

/**
 * 2026-10-04: the age-restriction class (alcohol, tobacco and vape, cannabis accessories). Listings in the category then
 * need the business's licence and an age-verified customer; the minimum age per province is region data.
 */
function AgeClassField({ data, category }: { data: Screen; category: Category }) {
  const t = useTaxonomyT();
  const classify = useClassify();
  const current = data.categories.find(c => c.id === category.id) ?? category;
  const error = classify.error instanceof ValidationError ? Object.values(classify.error.byField())[0] : errorText(classify.error);
  return (
    <Field label={t('f_ageClass')} hint={t('f_ageClassHint')} error={error ?? undefined}>
      <Select value={current.ageClass ?? ''} disabled={classify.isPending}
        options={[{ value: '', label: t('age_none') }, ...(['alcohol', 'tobacco', 'cannabis'] as const).map(c => ({ value: c, label: t(`age_${c}`) }))]}
        onChange={e => classify.mutate({ id: category.id, ageClass: e.target.value || null })} />
    </Field>
  );
}

/** One select per province of the region model (not Off): default registry, not regulated, or one of its regulators. */
function ProvinceRules({ data, category }: { data: Screen; category: Category }) {
  const t = useTaxonomyT();
  const { locale } = useLocale();
  const regulate = useRegulate();
  const provinces = (useRegions(locale).data?.provinces ?? []).filter(p => p.status !== 'off');
  const current = data.categories.find(c => c.id === category.id) ?? category;
  const value = (province: string) => {
    const rule = current.regulators.find(r => r.province === province);
    return !rule ? '' : rule.regulator ?? 'none';
  };
  const def = current.regulatedRegistry ? t('ruleDefault', { registry: current.regulatedRegistry }) : t('ruleDefaultNone');
  const error = regulate.error instanceof ValidationError ? Object.values(regulate.error.byField())[0] : errorText(regulate.error);
  return (
    <fieldset className="nl-tx-rules">
      <legend>{t('byProvince')}</legend>
      {provinces.map(p => (
        <Field key={p.code} label={p.name}>
          <Select value={value(p.code)} disabled={regulate.isPending}
            options={[{ value: '', label: def }, { value: 'none', label: t('ruleNone') },
              ...data.regulators.filter(r => r.province === p.code).map(r => ({ value: r.code, label: r.name }))]}
            onChange={e => regulate.mutate({ id: category.id, province: p.code, regulator: e.target.value || null })} />
        </Field>
      ))}
      {error ? <p role="alert" className="nl-tx-error">{error}</p> : null}
    </fieldset>
  );
}
