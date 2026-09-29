import { useState } from 'react';
import { Alert, Checkbox, Chip, Dialog, Field, Select, TextInput } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { attentionCount } from '../../lib/forms';
import { useDeleteGroup, useSaveGroup, type GroupBody, type ModifierGroup, type PickRule } from './api';
import { useKitchenT } from './messages';
import { dollars, parseDollars } from './model';

interface OptionDraft { key: string; id?: string; name: string; price: string; isDefault: boolean; soldOut: boolean }
let seq = 0;
const draftOf = (o?: ModifierGroup['options'][number]): OptionDraft => ({ key: o?.id ?? `n${++seq}`, id: o?.id, name: o?.name ?? '', price: dollars(o?.priceDeltaCents ?? 0), isDefault: o?.isDefault ?? false, soldOut: o?.soldOut ?? false });

/** New / edit modifier group: rule, required, nesting, options with price deltas, default, sold out. */
export function GroupDialog({ merchantId, group, groups, canDelete, onClose }: { merchantId: string; group?: ModifierGroup; groups: ModifierGroup[]; canDelete: boolean; onClose: () => void }) {
  const t = useKitchenT();
  const save = useSaveGroup(merchantId);
  const remove = useDeleteGroup(merchantId);
  const [name, setName] = useState(group?.name ?? '');
  const [rule, setRule] = useState<PickRule>(group?.pickRule ?? 'exactly');
  const [count, setCount] = useState(String(group?.pickCount ?? 1));
  const [required, setRequired] = useState(group?.required ?? false);
  const [showFor, setShowFor] = useState<string[]>(group?.showForOptionIds ?? []);
  const [options, setOptions] = useState<OptionDraft[]>(group ? group.options.map(draftOf) : [draftOf()]);
  const [tried, setTried] = useState(false);

  const n = Number(count);
  const errors: Record<string, string | undefined> = {
    name: !name.trim() ? t('v_groupName') : name.trim().length > 40 ? t('v_at40') : undefined,
    pickCount: !Number.isInteger(n) || n < 1 || n > 20 ? t('v_count') : rule !== 'up_to' && n > options.length && options.length > 0 ? t('v_enough') : undefined,
    options: options.length === 0 ? t('v_options') : undefined,
  };
  options.forEach((o, i) => {
    if (!o.name.trim()) errors[`options[${i}].name`] = t('v_optionName');
    else if (o.name.trim().length > 40) errors[`options[${i}].name`] = t('v_at40');
    const c = parseDollars(o.price);
    if (c === undefined || c > 10_000) errors[`options[${i}].priceDeltaCents`] = t('v_delta');
  });
  const server = save.error instanceof ValidationError ? save.error.byField() : {};
  const err = (k: string) => (tried ? errors[k] : undefined) ?? server[k];
  const shown = Object.fromEntries(Object.keys({ ...errors, ...server }).map(k => [k, err(k)]));
  const others = groups.filter(g => g.id !== group?.id);
  const update = (key: string, patch: Partial<OptionDraft>) => setOptions(list => list.map(o => (o.key === key ? { ...o, ...patch } : o)));

  const submit = () => {
    setTried(true);
    if (Object.values(errors).some(Boolean)) return;
    const body: GroupBody = {
      name: name.trim(), pickRule: rule, pickCount: n, required, showForOptionIds: showFor,
      options: options.map(o => ({ id: o.id, name: o.name.trim(), priceDeltaCents: parseDollars(o.price)!, isDefault: o.isDefault, soldOut: o.soldOut })),
    };
    save.mutate({ id: group?.id, body }, { onSuccess: onClose });
  };
  const attention = attentionCount(shown);
  return (
    <Dialog open onClose={onClose} width={640} title={group ? t('groupDialogEdit', { name: group.name }) : t('groupDialogNew')}
      actions={<>
        {group && canDelete ? <button type="button" className="btn btn-ghost nl-k-danger" disabled={remove.isPending} onClick={() => { if (window.confirm(t('confirmDeleteGroup', { name: group.name }))) remove.mutate(group.id, { onSuccess: onClose }); }}>{t('deleteGroup')}</button> : null}
        <button type="button" className="btn btn-ghost" onClick={onClose}>{t('cancel')}</button>
        <button type="button" className="btn btn-primary" disabled={save.isPending} onClick={submit}>{save.isPending ? t('saving') : t('save')}</button>
      </>}>
      <div className="nl-k-editor-form">
        {tried && attention > 0 ? <Alert tone="error" role="alert">{t('attention', { n: attention })}</Alert> : null}
        <Field label={t('g_name')} error={err('name')}><TextInput value={name} placeholder={t('g_namePh')} onChange={e => setName(e.target.value)} /></Field>
        <div className="nl-k-pair">
          <Field label={t('g_rule')}><Select value={rule} onChange={e => setRule(e.target.value as PickRule)} options={(['exactly', 'at_least', 'up_to'] as const).map(r => ({ value: r, label: t(`pick_${r}`) }))} /></Field>
          <Field label={t('g_count')} error={err('pickCount')}><TextInput inputMode="numeric" value={count} onChange={e => setCount(e.target.value)} /></Field>
        </div>
        <Checkbox checked={required} onChange={setRequired} label={t('g_required')} />
        {others.some(g => g.options.length) ? (
          <div className="nl-field" role="group" aria-label={t('g_showFor')}>
            <span className="nl-label">{t('g_showFor')}</span>
            <div className="nl-chips">{others.flatMap(g => g.options.map(o => <Chip key={o.id} selected={showFor.includes(o.id)} onClick={() => setShowFor(x => (x.includes(o.id) ? x.filter(v => v !== o.id) : [...x, o.id]))}>{`${g.name}: ${o.name}`}</Chip>))}</div>
            {err('showForOptionIds') ? <div role="alert" className="nl-error">{err('showForOptionIds')}</div> : null}
          </div>
        ) : null}
        <fieldset className="nl-k-fieldset">
          <legend className="nl-label">{t('g_options')}</legend>
          {options.map((o, i) => (
            <div key={o.key} className="nl-k-option-row">
              <Field label={t('o_name')} error={err(`options[${i}].name`)}><TextInput value={o.name} placeholder={t('o_namePh')} onChange={e => update(o.key, { name: e.target.value })} /></Field>
              <Field label={t('o_price')} error={err(`options[${i}].priceDeltaCents`)}><TextInput inputMode="decimal" value={o.price} onChange={e => update(o.key, { price: e.target.value })} /></Field>
              <Checkbox checked={o.isDefault} onChange={v => update(o.key, { isDefault: v })} label={t('o_default')} />
              <Checkbox checked={o.soldOut} onChange={v => update(o.key, { soldOut: v })} label={t('o_soldOut')} />
              <button type="button" className="btn btn-ghost nl-k-x" aria-label={t('o_remove', { name: o.name || String(i + 1) })} onClick={() => setOptions(list => list.filter(x => x.key !== o.key))}>×</button>
            </div>
          ))}
          {err('options') ? <div role="alert" className="nl-error">{err('options')}</div> : null}
          <button type="button" className="btn btn-ghost" onClick={() => setOptions(list => [...list, draftOf()])}>{t('o_add')}</button>
        </fieldset>
        {save.isError && !(save.error instanceof ValidationError) ? <div role="alert" className="nl-error">{t('saveError')}</div> : null}
      </div>
    </Dialog>
  );
}
