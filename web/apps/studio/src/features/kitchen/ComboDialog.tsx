import { useState } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { Alert, Checkbox, Chip, Dialog, Field, Segmented, Select, TextInput } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { attentionCount } from '../../lib/forms';
import { menuQuery, menusQuery, useDeleteCombo, useSaveCombo, type Combo, type ComboBody, type ComboStatus } from './api';
import { useKitchenT } from './messages';
import { HHMM, dollars, parseDollars } from './model';

interface SlotDraft { key: string; label: string; qty: string; source: string; itemIds: string[] }
const ITEMS = '__items__';
let seq = 0;

/** Combo builder: slots ("Any 2 mains"), fixed price or % off, availability window, status, swaps. */
export function ComboDialog({ merchantId, combo, canDelete, onClose }: { merchantId: string; combo?: Combo; canDelete: boolean; onClose: () => void }) {
  const t = useKitchenT();
  const save = useSaveCombo(merchantId);
  const remove = useDeleteCombo(merchantId);
  const menus = useQuery(menusQuery(merchantId)).data ?? [];
  const details = useQueries({ queries: menus.map(m => menuQuery(merchantId, m.id)) });
  const sections = menus.flatMap(m => m.sections.map(s => ({ value: s.id, label: menus.length > 1 ? `${m.name} · ${s.name}` : s.name })));
  const allItems = details.flatMap(d => d.data?.sections.flatMap(s => s.items) ?? []).filter(i => i.comboEligible);

  const [name, setName] = useState(combo?.name ?? '');
  const [slots, setSlots] = useState<SlotDraft[]>(combo?.slots.map(s => ({ key: `s${++seq}`, label: s.label, qty: String(s.qty), source: s.sectionId ?? ITEMS, itemIds: s.itemIds })) ?? [{ key: `s${++seq}`, label: '', qty: '1', source: sections[0]?.value ?? ITEMS, itemIds: [] }]);
  const [pricing, setPricing] = useState<'fixed' | 'percent_off'>(combo?.pricing ?? 'fixed');
  const [price, setPrice] = useState(combo?.pricing === 'fixed' ? dollars(combo.priceCents) : '');
  const [pct, setPct] = useState(combo?.discountPct ? String(combo.discountPct) : '10');
  const [windowed, setWindowed] = useState(!!combo?.schedule);
  const [days, setDays] = useState<number[]>(combo?.schedule?.days ?? [1, 2, 3, 4, 5]);
  const [from, setFrom] = useState(combo?.schedule?.from ?? '11:00');
  const [to, setTo] = useState(combo?.schedule?.to ?? '14:00');
  const [status, setStatus] = useState<ComboStatus>(combo?.status ?? 'draft');
  const [swaps, setSwaps] = useState(combo?.swapsAllowed ?? false);
  const [tried, setTried] = useState(false);

  const cents = parseDollars(price);
  const pctN = Number(pct);
  const errors: Record<string, string | undefined> = {
    name: !name.trim() ? t('v_comboName') : name.trim().length > 60 ? t('v_at60') : undefined,
    slots: slots.length === 0 ? t('v_slots') : undefined,
    priceCents: pricing === 'fixed' && (cents === undefined || cents <= 0) ? t('v_price') : undefined,
    discountPct: pricing === 'percent_off' && (!Number.isInteger(pctN) || pctN < 1 || pctN > 90) ? t('v_discount') : undefined,
    'schedule.days': windowed && days.length === 0 ? t('v_days') : undefined,
    'schedule.hours[0].to': windowed && (!HHMM.test(from) || !HHMM.test(to) || to <= from) ? t('v_endAfter') : undefined,
  };
  slots.forEach((s, i) => {
    if (!s.label.trim()) errors[`slots[${i}].label`] = t('v_slotLabel');
    const q = Number(s.qty);
    if (!Number.isInteger(q) || q < 1 || q > 20) errors[`slots[${i}].qty`] = t('v_slotQty');
    if (s.source === ITEMS && s.itemIds.length === 0) errors[`slots[${i}].items`] = t('v_slotItems');
  });
  const server = save.error instanceof ValidationError ? save.error.byField() : {};
  const err = (k: string) => (tried ? errors[k] : undefined) ?? server[k];
  const attention = attentionCount(Object.fromEntries(Object.keys({ ...errors, ...server }).map(k => [k, err(k)])));
  const update = (key: string, patch: Partial<SlotDraft>) => setSlots(list => list.map(s => (s.key === key ? { ...s, ...patch } : s)));

  const submit = () => {
    setTried(true);
    if (Object.values(errors).some(Boolean)) return;
    const body: ComboBody = {
      name: name.trim(), pricing, priceCents: pricing === 'fixed' ? cents! : null, discountPct: pricing === 'percent_off' ? pctN : null,
      slots: slots.map(s => ({ label: s.label.trim(), qty: Number(s.qty), sectionId: s.source === ITEMS ? null : s.source, itemIds: s.source === ITEMS ? s.itemIds : [] })),
      schedule: windowed ? { days, from, to } : null, status, swapsAllowed: swaps,
    };
    save.mutate({ id: combo?.id, body }, { onSuccess: onClose });
  };
  return (
    <Dialog open onClose={onClose} width={680} title={combo ? t('comboDialogEdit', { name: combo.name }) : t('comboDialogNew')}
      actions={<>
        {combo && canDelete ? <button type="button" className="btn btn-ghost nl-k-danger" disabled={remove.isPending} onClick={() => { if (window.confirm(t('confirmDeleteCombo', { name: combo.name }))) remove.mutate(combo.id, { onSuccess: onClose }); }}>{t('deleteCombo')}</button> : null}
        <button type="button" className="btn btn-ghost" onClick={onClose}>{t('cancel')}</button>
        <button type="button" className="btn btn-primary" disabled={save.isPending} onClick={submit}>{save.isPending ? t('saving') : t('save')}</button>
      </>}>
      <div className="nl-k-editor-form">
        {tried && attention > 0 ? <Alert tone="error" role="alert">{t('attention', { n: attention })}</Alert> : null}
        <Field label={t('c_name')} error={err('name')}><TextInput value={name} placeholder={t('c_namePh')} onChange={e => setName(e.target.value)} /></Field>
        <fieldset className="nl-k-fieldset">
          <legend className="nl-label">{t('c_slots')}</legend>
          {slots.map((s, i) => (
            <div key={s.key} className="nl-k-slot">
              <div className="nl-k-slot-row">
                <Field label={t('s_label')} error={err(`slots[${i}].label`)}><TextInput value={s.label} placeholder={t('s_labelPh')} onChange={e => update(s.key, { label: e.target.value })} /></Field>
                <Field label={t('s_qty')} error={err(`slots[${i}].qty`)}><TextInput inputMode="numeric" value={s.qty} onChange={e => update(s.key, { qty: e.target.value })} /></Field>
                <Field label={t('s_from')}><Select value={s.source} onChange={e => update(s.key, { source: e.target.value })} options={[...sections, { value: ITEMS, label: t('s_items') }]} /></Field>
                <button type="button" className="btn btn-ghost nl-k-x" aria-label={t('s_remove', { n: i + 1 })} onClick={() => setSlots(list => list.filter(x => x.key !== s.key))}>×</button>
              </div>
              {s.source === ITEMS ? (
                <div className="nl-chips" role="group" aria-label={t('s_items')}>
                  {allItems.map(it => <Chip key={it.id} selected={s.itemIds.includes(it.id)} onClick={() => update(s.key, { itemIds: s.itemIds.includes(it.id) ? s.itemIds.filter(x => x !== it.id) : [...s.itemIds, it.id] })}>{it.name}</Chip>)}
                </div>
              ) : null}
              {err(`slots[${i}].items`) ? <div role="alert" className="nl-error">{err(`slots[${i}].items`)}</div> : null}
            </div>
          ))}
          {err('slots') ? <div role="alert" className="nl-error">{err('slots')}</div> : null}
          <button type="button" className="btn btn-ghost" onClick={() => setSlots(list => [...list, { key: `s${++seq}`, label: '', qty: '1', source: sections[0]?.value ?? ITEMS, itemIds: [] }])}>{t('s_add')}</button>
        </fieldset>
        <Segmented name="pricing" aria-label={t('c_pricing')} value={pricing} onChange={setPricing} options={[{ value: 'fixed', label: t('pr_fixed') }, { value: 'percent_off', label: t('pr_percent') }]} />
        {pricing === 'fixed'
          ? <Field label={t('c_price')} error={err('priceCents')}><TextInput inputMode="decimal" value={price} placeholder={t('f_pricePh')} onChange={e => setPrice(e.target.value)} /></Field>
          : <Field label={t('c_discount')} error={err('discountPct')}><TextInput inputMode="numeric" value={pct} onChange={e => setPct(e.target.value)} /></Field>}
        <Field label={t('c_when')}><Select value={windowed ? 'window' : 'always'} onChange={e => setWindowed(e.target.value === 'window')} options={[{ value: 'always', label: t('when_always') }, { value: 'window', label: t('when_window') }]} /></Field>
        {windowed ? (
          <>
            <div className="nl-field" role="group" aria-label={t('c_days')}>
              <span className="nl-label">{t('c_days')}</span>
              <div className="nl-chips">{[1, 2, 3, 4, 5, 6, 7].map(d => <Chip key={d} selected={days.includes(d)} onClick={() => setDays(x => (x.includes(d) ? x.filter(v => v !== d) : [...x, d].sort()))}>{t(`day_${d}` as 'day_1')}</Chip>)}</div>
              {err('schedule.days') ? <div role="alert" className="nl-error">{err('schedule.days')}</div> : null}
            </div>
            <div className="nl-k-pair">
              <Field label={t('c_from')}><TextInput type="time" value={from} onChange={e => setFrom(e.target.value)} /></Field>
              <Field label={t('c_to')} error={err('schedule.hours[0].to')}><TextInput type="time" value={to} onChange={e => setTo(e.target.value)} /></Field>
            </div>
          </>
        ) : null}
        <Field label={t('c_status')}><Select value={status} onChange={e => setStatus(e.target.value as ComboStatus)} options={(['draft', 'live', 'scheduled', 'paused'] as const).map(s => ({ value: s, label: t(`cs_${s}`) }))} /></Field>
        <Checkbox checked={swaps} onChange={setSwaps} label={t('c_swaps')} />
        {save.isError && !(save.error instanceof ValidationError) ? <div role="alert" className="nl-error">{t('saveError')}</div> : null}
      </div>
    </Dialog>
  );
}
