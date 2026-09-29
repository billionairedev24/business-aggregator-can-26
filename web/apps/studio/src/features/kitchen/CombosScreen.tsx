import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, DataTable, Dialog, EmptyState, ErrorState, Field, PageSkeleton, Skeleton, TextInput, useLocale, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useMerchantId, useRole } from '../shell/api';
import { combosQuery, groupsQuery, promosQuery, useAddOption, useDeleteCombo, useSetPromo, type Combo, type ModifierGroup } from './api';
import { ComboDialog } from './ComboDialog';
import { GroupDialog } from './GroupDialog';
import { useKitchenT } from './messages';
import { dollars, optionText, parseDollars, ruleText } from './model';
import './Kitchen.css';

interface ComboRow { id: string; name: string; save: string; rule: string; price: number; state: string; status: Combo['status'] }
const TONE: Record<Combo['status'], DataTableTone> = { live: 'tag-accent', scheduled: 'tag-accent-2', paused: 'tag-neutral', draft: 'tag-neutral' };

/** Kitchen · Modifiers & combos (design 02 lines 876–897, `combos`, `dt.combos`). */
export function CombosScreen() {
  const merchantId = useMerchantId();
  const role = useRole();
  const t = useKitchenT();
  const { locale } = useLocale();
  const groups = useQuery(groupsQuery(merchantId));
  const combos = useQuery(combosQuery(merchantId));
  const promos = useQuery(promosQuery(merchantId));
  const setPromo = useSetPromo(merchantId);
  const removeCombo = useDeleteCombo(merchantId);
  const canEdit = role !== 'bookkeeper';
  const [groupDialog, setGroupDialog] = useState<{ group?: ModifierGroup } | null>(null);
  const [optionFor, setOptionFor] = useState<ModifierGroup | null>(null);
  const [comboDialog, setComboDialog] = useState<{ combo?: Combo } | null>(null);

  const rows = useMemo<ComboRow[]>(() => (combos.data ?? []).map(c => ({
    id: c.id, name: c.name, save: t('saveAmount', { amount: new Intl.NumberFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { style: 'currency', currency: 'CAD', maximumFractionDigits: c.savingCents % 100 ? 2 : 0 }).format(c.savingCents / 100) }),
    rule: c.rule, price: c.priceCents, state: t(`cs_${c.status}`), status: c.status,
  })), [combos.data, t, locale]);
  const columns: DataTableColumn<ComboRow>[] = [
    { key: 'name', label: t('col_combo'), sub: 'save', subLabel: t('col_saving'), primary: true },
    { key: 'rule', label: t('col_rule') },
    { key: 'price', label: t('col_price'), type: 'money' },
    { key: 'state', label: t('col_status'), type: 'tag', options: [t('cs_live'), t('cs_scheduled'), t('cs_paused'), t('cs_draft')] },
  ];

  if (groups.isPending && combos.isPending) return <PageSkeleton kpis={0} rows={6} />;
  return (
    <div className="nl-kitchen">
      <span className="nl-k-kicker">{t('combosKicker')}</span>
      <h1 className="nl-k-title nl-k-title-gap">{t('combosTitle')}</h1>
      <p className="nl-k-lede">{t('combosLede')}</p>
      <div className="nl-k-two">
        <section aria-labelledby="k-groups">
          <div className="nl-k-subhead"><h2 id="k-groups">{t('groupsTitle')}</h2>{canEdit ? <button type="button" className="btn btn-secondary nl-k-small-btn" onClick={() => setGroupDialog({})}>{t('newGroup')}</button> : null}</div>
          {groups.isPending ? <Skeleton height={120} /> : groups.isError ? <ErrorState message={t('loadError')} onRetry={() => void groups.refetch()} />
            : groups.data.length === 0 ? <EmptyState action={canEdit ? <button type="button" className="btn btn-primary" onClick={() => setGroupDialog({})}>{t('newGroup')}</button> : undefined}>{t('groupsEmpty')}</EmptyState>
            : groups.data.map(g => (
              <div key={g.id} className="nl-k-group">
                <div className="nl-k-group-head">
                  {canEdit ? <button type="button" className="nl-k-linkish" onClick={() => setGroupDialog({ group: g })}><strong>{g.name}</strong></button> : <strong>{g.name}</strong>}
                  <span className="nl-k-small">{t('usedBy', { n: g.usedBy })}</span>
                </div>
                <div className="nl-k-small">{ruleText(g, t)}</div>
                <div className="nl-chips nl-k-group-opts">
                  {g.options.map(o => <span key={o.id} className="tag tag-neutral">{optionText(o, t, locale)}</span>)}
                  {canEdit ? <button type="button" className="tag tag-outline nl-k-tag-btn" onClick={() => setOptionFor(g)}>{t('addOption')}</button> : null}
                </div>
              </div>
            ))}
          <div className="nl-k-note"><strong>{t('rulesBox')}</strong> {t('rulesText')}</div>
        </section>
        <section aria-labelledby="k-combos">
          <div className="nl-k-subhead"><h2 id="k-combos">{t('combosTitle2')}</h2>{canEdit ? <button type="button" className="btn btn-secondary nl-k-small-btn" onClick={() => setComboDialog({})}>{t('newCombo')}</button> : null}</div>
          <DataTable<ComboRow>
            entity={t('combo')} plural={t('combosPlural')} roleName={t(`role_${role}` as 'role_owner')}
            columns={columns} rows={rows} rowTones={r => ({ state: TONE[r.status] })}
            can={{ create: canEdit, update: canEdit, delete: role === 'owner', export: true }}
            loading={combos.isPending} error={combos.isError ? t('loadError') : null} onRetry={() => void combos.refetch()}
            createLabel={t('newCombo')} emptyText={t('combosEmpty')}
            onCreateClick={() => setComboDialog({})}
            onEditClick={r => setComboDialog({ combo: combos.data?.find(c => c.id === r.id) })}
            onOpen={canEdit ? r => setComboDialog({ combo: combos.data?.find(c => c.id === r.id) }) : undefined}
            onDelete={async hit => { await Promise.all(hit.map(r => removeCombo.mutateAsync(r.id))); }}
          />
          <div className="nl-k-note"><strong>{t('builderBox')}</strong> {t('builderText')}</div>
          <h2 className="nl-k-h2-gap">{t('promosTitle')}</h2>
          {promos.isError ? <ErrorState message={t('loadError')} onRetry={() => void promos.refetch()} /> : (
            <div className="nl-k-rows">
              {(promos.data ?? []).map(p => (
                <div key={p.promo} className="nl-k-row">
                  <span>{t(`promo_${p.promo}`)}</span>
                  <span className="nl-k-row-end">
                    {p.enabled ? <span className="tag tag-accent">{t(`promoOn_${p.promo}`)}</span> : null}
                    {role === 'owner'
                      ? <button type="button" className="btn btn-ghost nl-k-small-btn" aria-pressed={p.enabled} disabled={setPromo.isPending} onClick={() => setPromo.mutate({ promo: p.promo, enabled: !p.enabled })}>{p.enabled ? t('turnOff') : t('turnOn')}</button>
                      : null}
                  </span>
                </div>
              ))}
              {role !== 'owner' ? <span className="nl-k-small">{t('ownerOnly')}</span> : null}
              {setPromo.isError ? <Alert tone="error" role="alert">{t('saveError')}</Alert> : null}
            </div>
          )}
        </section>
      </div>
      {groupDialog ? <GroupDialog merchantId={merchantId} group={groupDialog.group} groups={groups.data ?? []} canDelete={role === 'owner'} onClose={() => setGroupDialog(null)} /> : null}
      {optionFor ? <AddOptionDialog merchantId={merchantId} group={optionFor} onClose={() => setOptionFor(null)} /> : null}
      {comboDialog ? <ComboDialog merchantId={merchantId} combo={comboDialog.combo} canDelete={role === 'owner'} onClose={() => setComboDialog(null)} /> : null}
    </div>
  );
}

function AddOptionDialog({ merchantId, group, onClose }: { merchantId: string; group: ModifierGroup; onClose: () => void }) {
  const t = useKitchenT();
  const add = useAddOption(merchantId);
  const [name, setName] = useState('');
  const [price, setPrice] = useState(dollars(0));
  const [tried, setTried] = useState(false);
  const cents = parseDollars(price);
  const errors = {
    name: !name.trim() ? t('v_optionName') : name.trim().length > 40 ? t('v_at40') : undefined,
    price: cents === undefined || cents > 10_000 ? t('v_delta') : undefined,
  };
  const server = add.error instanceof ValidationError ? add.error.byField() : {};
  const submit = () => { setTried(true); if (!errors.name && !errors.price) add.mutate({ id: group.id, name: name.trim(), priceDeltaCents: cents! }, { onSuccess: onClose }); };
  return (
    <Dialog open onClose={onClose} title={t('addOptionTitle', { name: group.name })} actions={<><button type="button" className="btn btn-ghost" onClick={onClose}>{t('cancel')}</button><button type="button" className="btn btn-primary" disabled={add.isPending} onClick={submit}>{t('save')}</button></>}>
      <Field label={t('o_name')} error={(tried ? errors.name : undefined) ?? server.name}><TextInput value={name} placeholder={t('o_namePh')} onChange={e => setName(e.target.value)} /></Field>
      <Field label={t('o_price')} error={(tried ? errors.price : undefined) ?? server.priceDeltaCents ?? server.options}><TextInput inputMode="decimal" value={price} onChange={e => setPrice(e.target.value)} /></Field>
      {add.isError && !(add.error instanceof ValidationError) ? <div role="alert" className="nl-error">{t('saveError')}</div> : null}
    </Dialog>
  );
}
