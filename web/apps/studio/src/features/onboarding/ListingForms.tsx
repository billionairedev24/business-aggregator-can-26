import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Checkbox, Chip, Field, Segmented, Select, TextArea, TextInput } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import type { Onboarding } from './api';
import { menusQuery, modifierGroupsQuery, useCreateMenuItem, useCreateProduct, useCreateService, type MenuItem } from './listingsApi';
import { useOnboardingT } from './messages';
import { parseDollars } from './model';

/** Server 422s of the catalogue contract, mapped onto this form's fields. */
function serverErrors(error: unknown, map: Record<string, string>): Record<string, string> {
  if (!(error instanceof ValidationError)) return {};
  const out: Record<string, string> = {};
  for (const e of error.errors) { const k = map[e.field] ?? e.field; out[k] ??= e.message; }
  return out;
}

const DURATIONS = [30, 45, 60, 90, 120, 240] as const;
const BUFFERS = [0, 15, 20, 30] as const;

/** New service (design 02 lines 259–269) → `POST …/services`. */
export function ServiceForm({ merchantId }: { merchantId: string }) {
  const t = useOnboardingT();
  const create = useCreateService(merchantId);
  const [name, setName] = useState('');
  const [mode, setMode] = useState<'fixed' | 'quote' | 'hourly'>('fixed');
  const [price, setPrice] = useState('');
  const [duration, setDuration] = useState<number>(30);
  const [buffer, setBuffer] = useState<number>(0);
  const [included, setIncluded] = useState('');
  const [instant, setInstant] = useState(true);
  const [tried, setTried] = useState(false);
  const cents = parseDollars(price);
  const errors: Record<string, string> = {
    ...(!name.trim() ? { name: t('fv_name') } : {}),
    ...(mode !== 'quote' && cents === undefined ? { price: t('fv_price') } : {}),
  };
  const server = serverErrors(create.error, { priceCents: 'price', durationMin: 'duration', bufferMin: 'buffer' });
  const err = (k: string) => (tried ? errors[k] : undefined) ?? server[k];
  const submit = () => {
    setTried(true);
    if (Object.keys(errors).length) return;
    create.mutate({ name: name.trim(), pricingMode: mode, priceCents: mode === 'quote' ? undefined : cents, durationMin: duration, bufferMin: buffer, included: included.trim(), instantBook: instant }, {
      onSuccess: () => { setName(''); setPrice(''); setIncluded(''); setTried(false); },
    });
  };
  return (
    <div className="nl-ob-stack">
      <Field label={t('svcName')} error={err('name')}><TextInput placeholder={t('svcNamePh')} value={name} onChange={e => setName(e.target.value)} /></Field>
      <div className="nl-ob-pair">
        <div className="nl-field"><span className="nl-label">{t('pricing')}</span><Segmented name="pricing" aria-label={t('pricing')} value={mode} onChange={setMode} options={[{ value: 'fixed', label: t('pr_fixed') }, { value: 'quote', label: t('pr_quote') }, { value: 'hourly', label: t('pr_hourly') }]} /></div>
        <Field label={t('price')} error={err('price')}><TextInput inputMode="decimal" placeholder={t('pricePh')} disabled={mode === 'quote'} value={price} onChange={e => setPrice(e.target.value)} /></Field>
      </div>
      <div className="nl-ob-pair">
        <Field label={t('duration')}><Select value={String(duration)} onChange={e => setDuration(Number(e.target.value))} options={DURATIONS.map(d => ({ value: String(d), label: d === 120 ? t('hours2') : d === 240 ? t('halfDay') : t('min', { n: d }) }))} /></Field>
        <Field label={t('buffer')}><Select value={String(buffer)} onChange={e => setBuffer(Number(e.target.value))} options={BUFFERS.map(b => ({ value: String(b), label: b === 0 ? '0' : t('min', { n: b }) }))} /></Field>
      </div>
      <Field label={t('included')} error={err('included')}><TextArea style={{ minHeight: 70 }} placeholder={t('includedPh')} value={included} onChange={e => setIncluded(e.target.value)} /></Field>
      <Checkbox checked={instant} onChange={setInstant} label={t('instantBook')} />
      {create.isError && !(create.error instanceof ValidationError) ? <div role="alert" className="nl-error">{t('saveError')}</div> : null}
      <button type="button" className="btn btn-primary" style={{ alignSelf: 'flex-start' }} disabled={create.isPending} onClick={submit}>{t('addService')}</button>
    </div>
  );
}

/** GS1 check digit for GTIN-12/13. */
export function validGtin(code: string): boolean {
  if (!/^\d{12,13}$/.test(code)) return false;
  const digits = code.split('').map(Number);
  const check = digits.pop()!;
  const sum = digits.reverse().reduce((acc, d, i) => acc + d * (i % 2 === 0 ? 3 : 1), 0);
  return (10 - (sum % 10)) % 10 === check;
}

/** New product (design 02 lines 271–281) → `POST …/products` (saved as a draft). */
export function ProductForm({ onboarding }: { onboarding: Onboarding }) {
  const t = useOnboardingT();
  const create = useCreateProduct(onboarding.merchantId);
  const categories = (onboarding.business?.categories ?? []).filter(c => c.id.startsWith('shop.'));
  const [gtin, setGtin] = useState('');
  const [gtinChecked, setGtinChecked] = useState<boolean | null>(null);
  const [title, setTitle] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [price, setPrice] = useState('');
  const [stock, setStock] = useState('');
  const [variant, setVariant] = useState<'none' | 'size' | 'colour' | 'size_colour'>('none');
  const [tried, setTried] = useState(false);
  const cents = parseDollars(price);
  const errors: Record<string, string> = {
    ...(gtin.trim() && !validGtin(gtin.trim()) ? { gtin: t('gtinBad') } : {}),
    ...(!title.trim() ? { title: t('fv_name') } : {}),
    ...(!categoryId ? { categoryId: t('fv_category') } : {}),
    ...(cents === undefined ? { price: t('fv_price') } : {}),
    ...(!/^\d+$/.test(stock.trim()) ? { stock: t('fv_stock') } : {}),
  };
  const server = serverErrors(create.error, { priceCents: 'price' });
  const err = (k: string) => (tried ? errors[k] : undefined) ?? server[k];
  const submit = () => {
    setTried(true);
    if (Object.keys(errors).length) return;
    create.mutate({ gtin: gtin.trim() || undefined, title: title.trim(), categoryId, priceCents: cents!, stock: Number(stock), variantTheme: variant }, {
      onSuccess: () => { setGtin(''); setTitle(''); setPrice(''); setStock(''); setTried(false); setGtinChecked(null); },
    });
  };
  return (
    <div className="nl-ob-stack">
      <Field label={t('gtin')} error={err('gtin') ?? (gtinChecked === false ? t('gtinBad') : undefined)} hint={gtinChecked ? t('gtinOk') : undefined}>
        <GtinInput value={gtin} placeholder={t('gtinPh')} onChange={v => { setGtin(v); setGtinChecked(null); }} onLookUp={() => setGtinChecked(validGtin(gtin.trim()))} lookUpLabel={t('lookUp')} />
      </Field>
      <Field label={t('productTitle')} error={err('title')}><TextInput placeholder={t('productTitlePh')} value={title} onChange={e => setTitle(e.target.value)} /></Field>
      <div className="nl-ob-pair">
        <Field label={t('deptCategory')} error={err('categoryId')}><Select placeholder={t('select')} value={categoryId} onChange={e => setCategoryId(e.target.value)} options={categories.map(c => ({ value: c.id, label: c.name }))} /></Field>
        <Field label={t('productPrice')} error={err('price')}><TextInput inputMode="decimal" placeholder={t('productPricePh')} value={price} onChange={e => setPrice(e.target.value)} /></Field>
      </div>
      <div className="nl-ob-pair">
        <Field label={t('stock')} error={err('stock')}><TextInput inputMode="numeric" placeholder={t('stockPh')} value={stock} onChange={e => setStock(e.target.value)} /></Field>
        <Field label={t('variants')}><Select value={variant} onChange={e => setVariant(e.target.value as typeof variant)} options={(['none', 'size', 'colour', 'size_colour'] as const).map(v => ({ value: v, label: t(`v_${v}`) }))} /></Field>
      </div>
      <div className="nl-field"><span className="nl-label">{t('photoMain')}</span><div className="nl-ob-photo" title={t('photoHint')}>{t('photoLater')}</div><div className="nl-hint">{t('photoHint')}</div></div>
      {create.isError && !(create.error instanceof ValidationError) ? <div role="alert" className="nl-error">{t('saveError')}</div> : null}
      <button type="button" className="btn btn-primary" style={{ alignSelf: 'flex-start' }} disabled={create.isPending} onClick={submit}>{t('saveDraft')}</button>
    </div>
  );
}

function GtinInput({ value, placeholder, onChange, onLookUp, lookUpLabel, ...rest }: { value: string; placeholder: string; onChange: (v: string) => void; onLookUp: () => void; lookUpLabel: string; id?: string; 'aria-invalid'?: boolean; 'aria-describedby'?: string }) {
  return (
    <div style={{ display: 'flex', gap: 8 }}>
      <TextInput {...rest} inputMode="numeric" placeholder={placeholder} value={value} onChange={e => onChange(e.target.value)} />
      <button type="button" className="btn btn-secondary" onClick={onLookUp}>{lookUpLabel}</button>
    </div>
  );
}

const ALLERGENS = ['eggs', 'milk', 'peanuts', 'tree_nuts', 'sesame', 'soy', 'wheat', 'fish', 'shellfish', 'mustard', 'sulphites'] as const;

/** New menu item (design 02 lines 283–294) → `POST …/menu-items` (hidden until approved). */
export function MenuItemForm({ merchantId, onCreated }: { merchantId: string; onCreated: (item: MenuItem) => void }) {
  const t = useOnboardingT();
  const create = useCreateMenuItem(merchantId);
  const menus = useQuery(menusQuery(merchantId)).data ?? [];
  const groups = useQuery(modifierGroupsQuery(merchantId)).data ?? [];
  const [menuId, setMenuId] = useState('');
  const [sectionId, setSectionId] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [price, setPrice] = useState('');
  const [prep, setPrep] = useState(0);
  const [allergens, setAllergens] = useState<string[] | 'none'>([]);
  const [groupIds, setGroupIds] = useState<string[]>([]);
  const [tried, setTried] = useState(false);
  const menu = menus.find(m => m.id === (menuId || menus[0]?.id));
  const section = sectionId || menu?.sections[0]?.id || '';
  const cents = parseDollars(price);
  const errors: Record<string, string> = {
    ...(!menu || !section ? { menu: t('fv_menu') } : {}),
    ...(!name.trim() ? { name: t('fv_name') } : {}),
    ...(cents === undefined ? { price: t('fv_price') } : {}),
    ...(allergens !== 'none' && allergens.length === 0 ? { allergens: t('fv_allergens') } : {}),
  };
  const server = serverErrors(create.error, { priceCents: 'price', menuId: 'menu', sectionId: 'menu' });
  const err = (k: string) => (tried ? errors[k] : undefined) ?? server[k];
  const toggle = (a: string) => setAllergens(cur => (cur === 'none' ? [a] : cur.includes(a) ? cur.filter(x => x !== a) : [...cur, a]));
  const submit = () => {
    setTried(true);
    if (Object.keys(errors).length || !menu) return;
    create.mutate({ menuId: menu.id, sectionId: section, name: name.trim(), description: description.trim(), priceCents: cents!, prepAddMin: prep, allergens: allergens === 'none' ? [] : allergens, modifierGroupIds: groupIds }, {
      onSuccess: item => { onCreated(item); setName(''); setDescription(''); setPrice(''); setAllergens([]); setTried(false); },
    });
  };
  return (
    <div className="nl-ob-stack">
      <div className="nl-ob-pair">
        <Field label={t('menu')} error={err('menu')} hint={menus.length ? undefined : t('noMenus')}><Select value={menu?.id ?? ''} disabled={!menus.length} onChange={e => { setMenuId(e.target.value); setSectionId(''); }} options={menus.map(m => ({ value: m.id, label: m.name }))} /></Field>
        <Field label={t('section')}><Select value={section} disabled={!menu} onChange={e => setSectionId(e.target.value)} options={(menu?.sections ?? []).map(s => ({ value: s.id, label: s.name }))} /></Field>
      </div>
      <Field label={t('itemName')} error={err('name')}><TextInput placeholder={t('itemNamePh')} value={name} onChange={e => setName(e.target.value)} /></Field>
      <Field label={t('itemDesc')}><TextArea style={{ minHeight: 60 }} placeholder={t('itemDescPh')} value={description} onChange={e => setDescription(e.target.value)} /></Field>
      <div className="nl-ob-pair">
        <Field label={t('basePrice')} error={err('price')}><TextInput inputMode="decimal" placeholder={t('productPricePh')} value={price} onChange={e => setPrice(e.target.value)} /></Field>
        <Field label={t('prep')}><Select value={String(prep)} onChange={e => setPrep(Number(e.target.value))} options={[0, 5, 10].map(n => ({ value: String(n), label: t(`prep_${n}` as 'prep_0') }))} /></Field>
      </div>
      <div className="nl-field" role="group" aria-label={t('allergens')}>
        <span className="nl-label">{t('allergens')}</span>
        <div className="nl-chips">
          {ALLERGENS.map(a => <Chip key={a} selected={allergens !== 'none' && allergens.includes(a)} onClick={() => toggle(a)}>{t(`al_${a}`)}</Chip>)}
          <Chip selected={allergens === 'none'} onClick={() => setAllergens(allergens === 'none' ? [] : 'none')}>{t('noneAllergen')}</Chip>
        </div>
        {err('allergens') ? <div role="alert" className="nl-error">{err('allergens')}</div> : null}
      </div>
      <div className="nl-field" role="group" aria-label={t('modifierGroups')}>
        <span className="nl-label">{t('modifierGroups')}</span>
        {groups.length ? <div className="nl-chips">{groups.map(g => <Chip key={g.id} selected={groupIds.includes(g.id)} onClick={() => setGroupIds(ids => (ids.includes(g.id) ? ids.filter(x => x !== g.id) : [...ids, g.id]))}>+ {g.name}</Chip>)}</div> : <div className="nl-hint">{t('noGroups')}</div>}
      </div>
      <div className="nl-field"><span className="nl-label">{t('photoRequired')}</span><div className="nl-ob-photo" title={t('photoHint')}>{t('photoLater')}</div><div className="nl-hint">{t('photoHint')}</div></div>
      {create.isError && !(create.error instanceof ValidationError) ? <div role="alert" className="nl-error">{t('saveError')}</div> : null}
      <button type="button" className="btn btn-primary" style={{ alignSelf: 'flex-start' }} disabled={create.isPending} onClick={submit}>{t('addItem')}</button>
    </div>
  );
}
