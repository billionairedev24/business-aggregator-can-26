import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, DataTable, Dialog, ErrorState, Field, PageSkeleton, Select, TextInput, useFormatters, useLocale, type DataTableColumn } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useRegions } from '../shell/api';
import { useGrant } from '../shell/grant';
import { useRegionName } from '../shell/PlaceFilters';
import { taxonomyQuery, useMergeSuggestion, useSaveRegulator, useSetLimit, type Category, type Limit, type Regulator, type Screen, type Suggestion } from './api';
import { CategoryDialog } from './CategoryDialog';
import { errorText, regulation } from './format';
import { useTaxonomyT, type TaxonomyKey } from './messages';
import './taxonomy.css';

interface Row { id: string; name: string; root: string; reg: string; live: string; sellers: number; price: string; src: Category }

/**
 * Catalogue taxonomy (S-94, design 03 `taxonomy`; admin only, changes need `vet`): the categories with their regulator in
 * each province, where they are live and their sellers and median price; the regulators; the category limit per type
 * of business (the database's trigger enforces it); and the categories businesses suggested, to approve or merge.
 */
export function TaxonomyScreen() {
  const t = useTaxonomyT();
  const query = useQuery(taxonomyQuery);
  if (query.isPending) return <PageSkeleton kpis={0} rows={8} />;
  if (query.isError) return <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />;
  return <TaxonomyView data={query.data} />;
}

function TaxonomyView({ data }: { data: Screen }) {
  const t = useTaxonomyT();
  const { locale } = useLocale();
  const fmt = useFormatters();
  const { can, roleName } = useGrant();
  const regionName = useRegionName();
  const [dialog, setDialog] = useState<{ mode: 'create' } | { mode: 'edit'; category: Category } | null>(null);
  const byId = new Map(data.categories.map(c => [c.id, c]));
  const name = (c: Category | undefined) => (c ? (locale === 'fr' ? c.nameFr ?? c.nameEn : c.nameEn) : '');
  const price = (c: Category) => {
    if (c.priceMode === 'quote') return t('quote');
    if (c.medianPriceCents == null) return t('none');
    const money = fmt.money(c.medianPriceCents, { whole: true });
    return c.priceMode === 'hourly' ? t('hourly', { price: money }) : money;
  };
  const rows: Row[] = data.categories.filter(c => !c.group).map(c => ({
    id: c.id, name: name(c), root: t('rootPath', { root: t(`root_${c.root}` as TaxonomyKey), group: name(byId.get(c.parentId ?? '')) }),
    reg: regulation(c, data.regulators, t), live: c.liveIn.length ? c.liveIn.map(regionName).join(', ') : t('none'), sellers: c.sellers, price: price(c), src: c,
  }));
  const columns: DataTableColumn<Row>[] = [
    { key: 'name', label: t('c_name'), primary: true }, { key: 'root', label: t('c_root') }, { key: 'reg', label: t('c_reg') },
    { key: 'live', label: t('c_live') }, { key: 'sellers', label: t('c_sellers'), type: 'num' }, { key: 'price', label: t('c_price') },
  ];
  const canEdit = can('vet');
  return (
    <div>
      <span className="nl-tx-kicker">{t('kicker')}</span>
      <h1 className="nl-tx-title">{t('title', { services: data.serviceCategories, departments: data.shopDepartments })}</h1>
      <DataTable<Row> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} pageSize={10} roleName={roleName}
        can={{ create: false, update: false, delete: false }} openLabel={t('edit')} onOpen={canEdit ? r => setDialog({ mode: 'edit', category: r.src }) : undefined} reportName="taxonomy" />
      <div className="nl-tx-actions">
        <Button disabled={!canEdit} onClick={() => setDialog({ mode: 'create' })}>{t('addCategory')}</Button>
        {!canEdit ? <span className="nl-tx-sub">{t('cannot')}</span> : null}
      </div>
      <div className="nl-tx-cols">
        <div>
          <h2 className="nl-tx-h2">{t('suggestionsTitle')}</h2>
          <p className="nl-tx-sub">{t('suggestionsSub')}</p>
          <Suggestions data={data} canEdit={canEdit} />
          <h2 className="nl-tx-h2 nl-tx-gap">{t('limitsTitle')}</h2>
          <p className="nl-tx-sub">{t('limitsSub')}</p>
          {data.limits.map(l => <LimitRow key={l.merchantType} limit={l} canEdit={canEdit} />)}
        </div>
        <div>
          <h2 className="nl-tx-h2">{t('regulatorsTitle')}</h2>
          <p className="nl-tx-sub">{t('regulatorsSub')}</p>
          <Regulators regulators={data.regulators} canEdit={canEdit} />
        </div>
      </div>
      {dialog ? <CategoryDialog data={data} category={dialog.mode === 'edit' ? dialog.category : undefined} onClose={() => setDialog(null)} /> : null}
    </div>
  );
}

function Suggestions({ data, canEdit }: { data: Screen; canEdit: boolean }) {
  const t = useTaxonomyT();
  const [approving, setApproving] = useState<Suggestion | null>(null);
  const [merging, setMerging] = useState<Suggestion | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  if (!data.suggestions.length) return <p className="nl-tx-sub">{notice ?? t('noSuggestions')}</p>;
  return (
    <>
      {notice ? <p role="status" className="nl-tx-sub">{notice}</p> : null}
      <ul className="nl-tx-list">
        {data.suggestions.map(s => (
          <li key={s.id} className="nl-tx-item">
            <div>
              <strong>{s.name}</strong>
              <div className="nl-tx-sub">{t('held', { n: s.businesses.length, names: s.businesses.slice(0, 3).map(b => b.name).join(', ') })}</div>
            </div>
            {canEdit ? (
              <div className="nl-tx-actions">
                <Button variant="secondary" onClick={() => setApproving(s)}>{t('approve')}</Button>
                <Button variant="ghost" onClick={() => setMerging(s)}>{t('merge')}</Button>
              </div>
            ) : null}
          </li>
        ))}
      </ul>
      {approving ? <CategoryDialog data={data} suggestion={approving} onClose={() => setApproving(null)} onResolved={setNotice} /> : null}
      {merging ? <MergeDialog data={data} suggestion={merging} onClose={() => setMerging(null)} onResolved={setNotice} /> : null}
    </>
  );
}

function MergeDialog({ data, suggestion, onClose, onResolved }: { data: Screen; suggestion: Suggestion; onClose: () => void; onResolved: (text: string) => void }) {
  const t = useTaxonomyT();
  const { locale } = useLocale();
  const merge = useMergeSuggestion();
  const [categoryId, setCategoryId] = useState('');
  const byId = new Map(data.categories.map(c => [c.id, c]));
  const label = (c: Category) => {
    const own = locale === 'fr' ? c.nameFr ?? c.nameEn : c.nameEn;
    const parent = c.parentId ? byId.get(c.parentId) : undefined;
    return parent ? `${locale === 'fr' ? parent.nameFr ?? parent.nameEn : parent.nameEn} › ${own}` : own;
  };
  const options = data.categories.filter(c => !c.group || c.root === 'shop').map(c => ({ value: c.id, label: label(c) })).sort((a, b) => a.label.localeCompare(b.label));
  const errors = merge.error instanceof ValidationError ? merge.error.byField() : {};
  return (
    <Dialog open onClose={onClose} title={t('mergeTitle', { name: suggestion.name })}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={merge.isPending} onClick={() => merge.mutate({ id: suggestion.id, categoryId }, {
          onSuccess: r => { onResolved(t('resolved', { n: r.moved + r.alreadyHeld, name: label(r.category) })); onClose(); },
        })}>{t('mergeCta')}</Button></>}>
      <Field label={t('f_category')} error={errors.categoryId}>
        <Select value={categoryId} placeholder="" options={options} onChange={e => setCategoryId(e.target.value)} />
      </Field>
      {merge.error && !(merge.error instanceof ValidationError) ? <p role="alert" className="nl-tx-error">{errorText(merge.error)}</p> : null}
    </Dialog>
  );
}

function LimitRow({ limit, canEdit }: { limit: Limit; canEdit: boolean }) {
  const t = useTaxonomyT();
  const set = useSetLimit();
  const [max, setMax] = useState(String(limit.max));
  const type = t(`type_${limit.merchantType}` as TaxonomyKey);
  const error = set.error instanceof ValidationError ? set.error.byField().max : errorText(set.error);
  return (
    <form className="nl-tx-limit" onSubmit={e => { e.preventDefault(); set.mutate({ type: limit.merchantType, max: Number(max) }); }}>
      <Field label={t('limitLabel', { type })} error={error}>
        <TextInput type="number" inputMode="numeric" min={1} max={50} value={max} disabled={!canEdit} onChange={e => setMax(e.target.value)} />
      </Field>
      {canEdit ? <Button type="submit" variant="secondary" disabled={set.isPending || Number(max) === limit.max}>{t('save')}</Button> : null}
      {limit.businessesAbove ? <p className="nl-tx-sub nl-tx-wide">{t('above', { n: limit.businessesAbove })}</p> : null}
    </form>
  );
}

interface RegRow { id: string; name: string; province: string; website: string; categories: number; src: Regulator }

function Regulators({ regulators, canEdit }: { regulators: readonly Regulator[]; canEdit: boolean }) {
  const t = useTaxonomyT();
  const { roleName } = useGrant();
  const regionName = useRegionName();
  const [editing, setEditing] = useState<Regulator | 'new' | null>(null);
  const rows: RegRow[] = regulators.map(r => ({ id: r.code, name: r.name, province: regionName(r.province), website: r.website ?? t('none'), categories: r.categories, src: r }));
  const columns: DataTableColumn<RegRow>[] = [
    { key: 'name', label: t('r_name'), primary: true }, { key: 'province', label: t('r_province') },
    { key: 'website', label: t('r_website') }, { key: 'categories', label: t('r_categories'), type: 'num' },
  ];
  return (
    <>
      <DataTable<RegRow> entity={t('regEntity')} plural={t('regPlural')} columns={columns} rows={rows} pageSize={5} roleName={roleName} emptyText={t('noRegulators')}
        can={{ create: false, update: false, delete: false }} openLabel={t('edit')} onOpen={canEdit ? r => setEditing(r.src) : undefined} />
      <div className="nl-tx-actions">
        <Button variant="secondary" disabled={!canEdit} onClick={() => setEditing('new')}>{t('addRegulator')}</Button>
      </div>
      {editing ? <RegulatorDialog regulator={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} /> : null}
    </>
  );
}

function RegulatorDialog({ regulator, onClose }: { regulator?: Regulator; onClose: () => void }) {
  const t = useTaxonomyT();
  const { locale } = useLocale();
  const provinces = (useRegions(locale).data?.provinces ?? []);
  const save = useSaveRegulator();
  const [code, setCode] = useState(regulator?.code ?? '');
  const [name, setName] = useState(regulator?.name ?? '');
  const [province, setProvince] = useState(regulator?.province ?? '');
  const [website, setWebsite] = useState(regulator?.website ?? '');
  const errors = save.error instanceof ValidationError ? save.error.byField() : {};
  const submit = () => save.mutate({ create: !regulator, code: code.trim(), name, province, website: website.trim() || undefined }, { onSuccess: onClose });
  return (
    <Dialog open onClose={onClose} title={regulator ? t('regEditTitle', { name: regulator.name }) : t('regNewTitle')}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button><Button disabled={save.isPending} onClick={submit}>{regulator ? t('save') : t('create')}</Button></>}>
      {!regulator ? <Field label={t('f_code')} error={errors.code}><TextInput value={code} maxLength={40} onChange={e => setCode(e.target.value)} /></Field> : null}
      <Field label={t('f_name')} error={errors.name}><TextInput value={name} maxLength={80} onChange={e => setName(e.target.value)} /></Field>
      <Field label={t('f_province')} error={errors.province}>
        <Select value={province} placeholder="" options={provinces.map(p => ({ value: p.code, label: p.name }))} onChange={e => setProvince(e.target.value)} />
      </Field>
      <Field label={t('f_website')} error={errors.website}><TextInput type="url" value={website} maxLength={200} onChange={e => setWebsite(e.target.value)} /></Field>
      {save.error && !(save.error instanceof ValidationError) ? <p role="alert" className="nl-tx-error">{errorText(save.error)}</p> : null}
    </Dialog>
  );
}
