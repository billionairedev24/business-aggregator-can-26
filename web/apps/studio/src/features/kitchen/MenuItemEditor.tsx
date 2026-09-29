import { useRef, useState } from 'react';
import { Link } from '@tanstack/react-router';
import { Alert, Checkbox, Chip, Field, Select, TextArea, TextInput } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { attentionCount } from '../../lib/forms';
import { ALLERGENS, DIETARY, WINDOWS, useDeleteItem, useSaveItem, useUploadPhoto, type ItemBody, type MenuDetail, type MenuItem, type ModifierGroup } from './api';
import { ItemPhoto } from './ItemPhoto';
import { useKitchenT } from './messages';
import { dollars, parseDollars } from './model';

interface Props {
  merchantId: string;
  menu: MenuDetail;
  groups: ModifierGroup[];
  item?: MenuItem;
  sectionId?: string;
  canDelete: boolean;
  onClose: () => void;
}

type FieldKey = 'name' | 'description' | 'price' | 'sectionId' | 'allergens' | 'dailyLimit' | 'photo';
const SERVER_FIELDS: Record<string, FieldKey> = { name: 'name', description: 'description', priceCents: 'price', sectionId: 'sectionId', menuId: 'sectionId', allergens: 'allergens', dailyLimit: 'dailyLimit', file: 'photo' };

/** The item editor in the builder's side panel (design 02 lines 853–866). */
export function MenuItemEditor({ merchantId, menu, groups, item, sectionId, canDelete, onClose }: Props) {
  const t = useKitchenT();
  const save = useSaveItem(merchantId);
  const upload = useUploadPhoto(merchantId);
  const remove = useDeleteItem(merchantId);
  const fileRef = useRef<HTMLInputElement>(null);
  const [name, setName] = useState(item?.name ?? '');
  const [description, setDescription] = useState(item?.description ?? '');
  const [price, setPrice] = useState(item ? dollars(item.priceCents) : '');
  const [section, setSection] = useState(item?.sectionId ?? sectionId ?? menu.sections[0]?.id ?? '');
  const [groupIds, setGroupIds] = useState<string[]>(item?.modifierGroups.map(g => g.id) ?? []);
  const [dietary, setDietary] = useState<string[]>(item?.dietary ?? []);
  const [allergens, setAllergens] = useState<string[] | 'none' | null>(item ? (item.allergens === null ? null : item.allergens.length ? item.allergens : 'none') : null);
  const [prep, setPrep] = useState(item?.prepAddMin ?? 0);
  const [availability, setAvailability] = useState<ItemBody['availability']>(item?.availability ?? 'always');
  const [limit, setLimit] = useState(item?.dailyLimit ? String(item.dailyLimit) : '');
  const [comboEligible, setComboEligible] = useState(item?.comboEligible ?? true);
  const [touched, setTouched] = useState<Partial<Record<FieldKey, boolean>>>({});
  const [tried, setTried] = useState(false);
  const [photoVersion, setPhotoVersion] = useState(item?.updatedAt ?? null);
  const [hasPhoto, setHasPhoto] = useState(item?.hasPhoto ?? false);

  const cents = parseDollars(price);
  const limitNum = limit.trim() ? Number(limit) : null;
  const errors: Partial<Record<FieldKey, string>> = {
    ...(!name.trim() ? { name: t('v_name') } : name.trim().length > 80 ? { name: t('v_nameLong') } : {}),
    ...(description.length > 500 ? { description: t('v_desc') } : {}),
    ...(cents === undefined || cents <= 0 ? { price: t('v_price') } : {}),
    ...(!section ? { sectionId: t('v_section') } : {}),
    ...(allergens === null || (Array.isArray(allergens) && allergens.length === 0) ? { allergens: t('v_allergens') } : {}),
    ...(limitNum !== null && (!Number.isInteger(limitNum) || limitNum < 1 || limitNum > 999) ? { dailyLimit: t('v_limit') } : {}),
  };
  const server: Partial<Record<FieldKey, string>> = {};
  for (const e of save.error instanceof ValidationError ? save.error.errors : []) { const k = SERVER_FIELDS[e.field]; if (k) server[k] ??= e.message; }
  for (const e of upload.error instanceof ValidationError ? upload.error.errors : []) server.photo ??= e.message;
  const err = (k: FieldKey) => ((tried || touched[k]) ? errors[k] : undefined) ?? server[k];
  const touch = (k: FieldKey) => () => setTouched(x => ({ ...x, [k]: true }));
  const visible = (Object.keys(errors) as FieldKey[]).reduce<Record<string, string | undefined>>((acc, k) => ({ ...acc, [k]: err(k) }), { ...server });

  const submit = (publish: boolean) => {
    setTried(true);
    if (Object.keys(errors).length) return;
    const body: ItemBody = {
      menuId: menu.id, sectionId: section, name: name.trim(), description: description.trim(), priceCents: cents!, prepAddMin: prep,
      allergens: allergens === 'none' || allergens === null ? [] : allergens, dietary, modifierGroupIds: groupIds, availability, dailyLimit: limitNum, comboEligible, publish,
    };
    save.mutate({ id: item?.id, body }, { onSuccess: onClose });
  };
  const toggleIn = (list: string[], v: string) => (list.includes(v) ? list.filter(x => x !== v) : [...list, v]);
  const onPhoto = (file: File | undefined) => {
    if (!file || !item) return;
    upload.mutate({ id: item.id, file }, { onSuccess: updated => { setHasPhoto(true); setPhotoVersion(updated.updatedAt ?? String(Date.now())); } });
  };
  const n = attentionCount(visible);

  return (
    <form className="nl-k-editor-form" noValidate onSubmit={e => { e.preventDefault(); submit(true); }}>
      {tried && n > 0 ? <Alert tone="error" role="alert">{t('attention', { n })}</Alert> : null}
      <Field label={t('f_name')} error={err('name')}><TextInput value={name} placeholder={t('f_namePh')} maxLength={120} onChange={e => setName(e.target.value)} onBlur={touch('name')} /></Field>
      <Field label={t('f_desc')} error={err('description')}><TextArea value={description} placeholder={t('f_descPh')} rows={3} onChange={e => setDescription(e.target.value)} onBlur={touch('description')} /></Field>
      <div className="nl-k-pair">
        <Field label={t('f_price')} error={err('price')}><TextInput inputMode="decimal" value={price} placeholder={t('f_pricePh')} onChange={e => setPrice(e.target.value)} onBlur={touch('price')} /></Field>
        <Field label={t('f_section')} error={err('sectionId')}><Select value={section} onChange={e => setSection(e.target.value)} options={menu.sections.map(s => ({ value: s.id, label: s.name }))} /></Field>
      </div>
      <div className="nl-field" role="group" aria-label={t('f_photo')}>
        <span className="nl-label">{t('f_photo')}</span>
        <div className="nl-k-photos">
          <ItemPhoto merchantId={merchantId} itemId={item?.id} hasPhoto={hasPhoto} version={photoVersion} size={72} />
          {item ? (
            <>
              <button type="button" className="nl-k-photo-add" disabled={upload.isPending} onClick={() => fileRef.current?.click()}>{hasPhoto ? t('f_photoReplace') : t('f_photoAdd')}</button>
              <input ref={fileRef} type="file" hidden accept="image/jpeg,image/png,image/webp" aria-label={t('f_photo')} onChange={e => onPhoto(e.target.files?.[0])} />
            </>
          ) : <span className="nl-hint">{t('f_photoAfterSave')}</span>}
        </div>
        {err('photo') ? <div role="alert" className="nl-error">{err('photo')}</div> : upload.isError && !(upload.error instanceof ValidationError) ? <div role="alert" className="nl-error">{t('photoError')}</div> : null}
      </div>
      <div className="nl-field" role="group" aria-label={t('f_groups')}>
        <span className="nl-label">{t('f_groups')}</span>
        <div className="nl-chips">
          {groups.map(g => <Chip key={g.id} selected={groupIds.includes(g.id)} onClick={() => setGroupIds(ids => toggleIn(ids, g.id))}>{groupIds.includes(g.id) ? g.name : `+ ${g.name}`}</Chip>)}
          <Link className="nl-chip nl-k-chip-link" to="/b/$merchantId/kitchen/combos" params={{ merchantId }}>{t('f_newGroup')}</Link>
        </div>
        {groups.length === 0 ? <div className="nl-hint">{t('f_noGroups')}</div> : null}
      </div>
      <div className="nl-field" role="group" aria-label={t('f_dietAllergens')}>
        <span className="nl-label">{t('f_dietAllergens')}</span>
        <span className="nl-hint">{t('f_dietary')}</span>
        <div className="nl-chips">{DIETARY.map(d => <Chip key={d} selected={dietary.includes(d)} onClick={() => setDietary(x => toggleIn(x, d))}>{t(`dt_${d}`)}</Chip>)}</div>
        <span className="nl-hint">{t('f_allergens')}</span>
        <div className="nl-chips">
          {ALLERGENS.map(a => {
            const on = Array.isArray(allergens) && allergens.includes(a);
            return <Chip key={a} className={on ? 'nl-k-allergen-on' : undefined} selected={on} onClick={() => { setAllergens(x => toggleIn(Array.isArray(x) ? x : [], a)); setTouched(x => ({ ...x, allergens: true })); }}>{t(`al_${a}`)}</Chip>;
          })}
          <Chip selected={allergens === 'none'} onClick={() => { setAllergens(x => (x === 'none' ? null : 'none')); setTouched(x => ({ ...x, allergens: true })); }}>{t('f_noneAllergen')}</Chip>
        </div>
        {err('allergens') ? <div role="alert" className="nl-error">{err('allergens')}</div> : null}
      </div>
      <div className="nl-k-pair">
        <Field label={t('f_prep')}><Select value={String(prep)} onChange={e => setPrep(Number(e.target.value))} options={[0, 5, 10].map(p => ({ value: String(p), label: t(`prep_${p}` as 'prep_0') }))} /></Field>
        <Field label={t('f_avail')}><Select value={availability} onChange={e => setAvailability(e.target.value as ItemBody['availability'])} options={WINDOWS.map(w => ({ value: w, label: t(`win_${w}`) }))} /></Field>
      </div>
      <Field label={t('f_limit')} error={err('dailyLimit')}><TextInput inputMode="numeric" value={limit} placeholder={t('f_limitPh')} onChange={e => setLimit(e.target.value)} onBlur={touch('dailyLimit')} /></Field>
      <Checkbox checked={comboEligible} onChange={setComboEligible} label={t('f_combo')} />
      {save.isError && !(save.error instanceof ValidationError) ? <div role="alert" className="nl-error">{t('saveError')}</div> : null}
      <div className="nl-k-editor-actions">
        <button type="submit" className="btn btn-primary" disabled={save.isPending}>{save.isPending ? t('saving') : t('savePublish')}</button>
        <button type="button" className="btn btn-secondary" disabled={save.isPending} onClick={() => submit(false)}>{t('saveDraft')}</button>
        {item && canDelete ? (
          <button type="button" className="btn btn-ghost nl-k-danger" disabled={remove.isPending} onClick={() => { if (window.confirm(t('confirmDelete', { name: item.name }))) remove.mutate(item.id, { onSuccess: onClose }); }}>{t('deleteItem')}</button>
        ) : null}
      </div>
    </form>
  );
}
