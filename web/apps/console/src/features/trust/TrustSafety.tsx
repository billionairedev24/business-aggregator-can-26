import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearch } from '@tanstack/react-router';
import { Button, DataTable, Dialog, ErrorState, Field, formatNumber, PageSkeleton, TextArea, TextInput, useLocale, type DataTableColumn } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { PlaceFilters, type PlaceFilter } from '../shell/PlaceFilters';
import { SCREEN_PATH } from '../shell/screens';
import { flagsQuery, impactOf, rulesQuery, useFlagAction, useFlagDecision, useSaveRule, type Flag, type Impact, type Rule } from './api';
import { useTrustT, type TrustKey } from './messages';
import '../shell/queues.css';

type Values = Record<string, unknown>;
const num = (v: Values | undefined, k: string) => Number(v?.[k] ?? 0);
const pct = (bps: number) => String(Math.round(bps) / 100);

/** The staff action a flag's rule calls for (design 03 flags: Warn, Start coaching, Confirm, Suspend listing rights, Escalate to ops; Hide review). */
export function actionFor(f: Flag): string {
  if (f.targetType === 'listing') return 'actioned';
  // mobile gaps part 2: a reported or screened review is hidden from the public page and the rating
  if (f.targetType === 'review') return 'hide_review';
  if (f.rule === 'off_platform_payment') return 'warn';
  if (f.rule === 'floor_breach') return 'coach';
  if (f.rule === 'no_show' && f.targetType === 'customer') return 'confirm';
  if (f.rule === 'regulated_without_permit') return 'suspend_listings';
  return 'escalate';
}

/**
 * Trust & safety (S-93, design 03 `trust`): tier rules, automatic consequences and the rating floor as configuration
 * (role `decide` edits them), the keyword lists of the off-platform detector and listing vetting, and the open flags
 * with their action or Dismiss. Admin and trust & safety open it.
 */
export function TrustSafety() {
  const t = useTrustT();
  const search = useSearch({ strict: false }) as PlaceFilter;
  const filter = { province: search.province, market: search.market };
  const rules = useQuery(rulesQuery);
  const flags = useQuery(flagsQuery(filter));
  const { can, roleName } = useGrant();
  const header = <div className="nl-q-top"><span className="nl-q-kicker">{t('kicker')}</span><PlaceFilters to={SCREEN_PATH.trust} filter={filter} /></div>;
  if (rules.isPending || flags.isPending) return <PageSkeleton kpis={0} rows={6} />;
  if (rules.isError || (flags.isError && !flags.data)) {
    return <div>{header}{flags.error instanceof ValidationError ? <p role="alert" className="nl-q-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => { void rules.refetch(); void flags.refetch(); }} />}</div>;
  }
  const byKey = Object.fromEntries(rules.data!.items.map(r => [r.key, r])) as Record<string, Rule>;
  const v = (k: string) => byKey[k]?.value;
  return (
    <div>
      {header}
      <h1 className="nl-q-title">{t('title')}</h1>
      <p className="nl-q-lede">{t('lede')}</p>
      <div className="nl-q-cols">
        <div>
          <h2 className="nl-q-h2">{t('tierRules')}</h2>
          <TierRules byKey={byKey} canEdit={can('decide')} roleName={roleName} />
          <h2 className="nl-q-h2 nl-q-gap">{t('consequences')}</h2>
          <div className="nl-q-list">
            <div>{t('c_floor', { rating: num(v('rating_floor'), 'rating'), days: num(v('rating_floor'), 'days'), recover: num(v('rating_floor'), 'recoverDays') })}</div>
            <div>{t('c_noShows', { count: num(v('provider_no_shows'), 'count'), days: num(v('provider_no_shows'), 'days') })}</div>
            <div>{t('c_photo', { hours: num(v('missing_photo_delay'), 'hours') })}</div>
            <div>{t('c_offPlatform')}</div>
            <div>{t('c_customer', { count: num(v('customer_no_shows'), 'count') })}</div>
          </div>
          <Keywords byKey={byKey} canEdit={can('decide')} />
        </div>
        <div>
          <Flags flags={flags.data!.items} canDecide={can('decide')} />
          <RatingFloor rule={byKey.rating_floor} filter={filter} canEdit={can('decide')} />
        </div>
      </div>
    </div>
  );
}

interface TierRow { id: string; tier: string; requires: string; unlocks: string }

function TierRules({ byKey, canEdit, roleName }: { byKey: Record<string, Rule>; canEdit: boolean; roleName: string }) {
  const t = useTrustT();
  const [editing, setEditing] = useState<string | null>(null);
  const r = byKey.tier_registered?.value, tr = byKey.tier_trusted?.value, m = byKey.tier_master?.value;
  const rows: TierRow[] = [
    { id: 'tier_registered', tier: t('t_registered'), requires: t('req_registered', { max: Math.max(0, num(tr, 'minJobs') - 1) }), unlocks: t('unl_registered', { take: pct(num(r, 'takeRateBps')) }) },
    { id: 'tier_trusted', tier: t('t_trusted'), requires: t('req_trusted', { jobs: num(tr, 'minJobs'), quality: num(tr, 'minQuality'), onTime: num(tr, 'minOnTimePct'), disputes: num(tr, 'maxDisputePct') }), unlocks: t('unl_trusted', { take: pct(num(tr, 'takeRateBps')) }) },
    { id: 'tier_master', tier: t('t_master'), requires: t('req_master', { jobs: num(m, 'minJobs'), quality: num(m, 'minQuality'), onTime: num(m, 'minOnTimePct'), disputes: num(m, 'maxDisputePct'), photos: num(m, 'minPhotosPct') }), unlocks: t('unl_master', { take: pct(num(m, 'takeRateBps')) }) },
  ];
  const columns: DataTableColumn<TierRow>[] = [
    { key: 'tier', label: t('colTier'), primary: true }, { key: 'requires', label: t('colRequires') }, { key: 'unlocks', label: t('colUnlocks') },
  ];
  const rule = editing ? byKey[editing] : undefined;
  return (
    <>
      <DataTable<TierRow> entity={t('tierEntity')} plural={t('tierPlural')} columns={columns} rows={rows} pageSize={5}
        can={{ create: false, update: canEdit, delete: false }} roleName={roleName} onEditClick={row => setEditing(row.id)} />
      {rule ? <RuleDialog rule={rule} title={t('editTier', { tier: rows.find(x => x.id === rule.key)!.tier })} onClose={() => setEditing(null)} /> : null}
    </>
  );
}

/** Edits one rule's numeric fields (the api validates the ranges and answers 422 per field). */
function RuleDialog({ rule, title, onClose }: { rule: Rule; title: string; onClose: () => void }) {
  const t = useTrustT();
  const save = useSaveRule();
  const numeric = rule.fields.filter(f => f.kind !== 'words');
  const [values, setValues] = useState<Record<string, string>>(() => Object.fromEntries(numeric.map(f => [f.name, String(rule.value[f.name] ?? '')])));
  const errors = save.error instanceof ValidationError ? save.error.byField() : {};
  const submit = () => save.mutate({ key: rule.key, value: Object.fromEntries(numeric.map(f => [f.name, Number(values[f.name])])) }, { onSuccess: onClose });
  return (
    <Dialog open onClose={onClose} title={title}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button><Button disabled={save.isPending} onClick={submit}>{t('save')}</Button></>}>
      {numeric.map(f => (
        <Field key={f.name} label={t(`f_${f.name}` as TrustKey)} error={errors[`value.${f.name}`]}>
          <TextInput inputMode="decimal" value={values[f.name] ?? ''} onChange={e => setValues(x => ({ ...x, [f.name]: e.target.value }))} />
        </Field>
      ))}
    </Dialog>
  );
}

function Keywords({ byKey, canEdit }: { byKey: Record<string, Rule>; canEdit: boolean }) {
  const t = useTrustT();
  return (
    <>
      <h2 className="nl-q-h2 nl-q-gap">{t('keywordsTitle')}</h2>
      <WordList rule={byKey.off_platform_phrases} field="phrases" label={t('offPlatformLabel')} canEdit={canEdit} />
      <WordList rule={byKey.restricted_keywords} field="words" label={t('restrictedLabel')} canEdit={canEdit} />
    </>
  );
}

function WordList({ rule, field, label, canEdit }: { rule: Rule | undefined; field: string; label: string; canEdit: boolean }) {
  const t = useTrustT();
  const save = useSaveRule();
  const [text, setText] = useState(() => ((rule?.value[field] as string[] | undefined) ?? []).join('\n'));
  const [saved, setSaved] = useState(false);
  if (!rule) return null;
  const error = save.error instanceof ValidationError ? save.error.byField()[`value.${field}`] : undefined;
  return (
    <div className="nl-q-field">
      <Field label={label} error={error}>
        <TextArea value={text} readOnly={!canEdit} onChange={e => { setText(e.target.value); setSaved(false); }} />
      </Field>
      {canEdit ? <div className="nl-q-actions">
        <Button variant="secondary" disabled={save.isPending}
          onClick={() => save.mutate({ key: rule.key, value: { [field]: text.split('\n').map(w => w.trim()).filter(Boolean) } }, { onSuccess: () => setSaved(true) })}>{t('save')}</Button>
        {saved ? <span role="status" className="nl-q-note">{t('saved')}</span> : null}
      </div> : null}
    </div>
  );
}

function Flags({ flags, canDecide }: { flags: Flag[]; canDecide: boolean }) {
  const t = useTrustT();
  const act = useFlagAction();
  const decide = useFlagDecision();
  const open = flags.filter(f => f.state === 'open');
  const refusal = [act.error, decide.error].find(Boolean) as ApiError | Error | undefined;
  const who = (f: Flag) => f.businessName ?? (f.targetType === 'customer' ? t('customer') : f.targetType === 'courier' ? t('courier') : f.targetType);
  const what = (f: Flag) => { const k = `w_${f.rule}` as TrustKey; const s = t(k); return s === k ? t('w_other') : s; };
  return (
    <>
      <h2 className="nl-q-h2">{t('openFlags', { n: open.length })}</h2>
      {flags.length === 0 ? <p className="nl-q-note">{t('noFlags')}</p> : null}
      <ul className="nl-q-list">
        {flags.map(f => {
          const action = actionFor(f);
          const label = t(`a_${action}` as TrustKey);
          return (
            <li key={f.id} className="nl-q-row">
              <span><strong>{who(f)}</strong> · {what(f)}<br /><span className="nl-q-note">{f.explanation}</span></span>
              {f.state === 'open' ? (canDecide ? (
                <span className="nl-q-actions">
                  <Button variant="secondary" onClick={() => (action === 'actioned' ? decide.mutate({ id: f.id, decision: 'actioned' }) : act.mutate({ id: f.id, action }))}>{label}</Button>
                  <Button variant="ghost" onClick={() => decide.mutate({ id: f.id, decision: 'dismissed' })}>{t('dismiss')}</Button>
                </span>
              ) : null) : <span className="tag tag-neutral">{f.state === 'dismissed' ? t('dismissed') : t('done', { action: t(`a_${f.action === 'actioned' || !f.action ? action : f.action}` as TrustKey) })}</span>}
            </li>
          );
        })}
      </ul>
      {refusal ? <p role="alert" className="nl-q-error">{refusal.message}</p> : null}
    </>
  );
}

function RatingFloor({ rule, filter, canEdit }: { rule: Rule | undefined; filter: PlaceFilter; canEdit: boolean }) {
  const t = useTrustT();
  const { locale } = useLocale();
  const save = useSaveRule();
  const [rating, setRating] = useState(String(rule?.value.rating ?? '4.2'));
  const [impact, setImpact] = useState<Impact | null>(null);
  const [error, setError] = useState<string>();
  const value = Number(rating.replace(',', '.'));
  const simulate = async () => {
    setError(undefined);
    try { setImpact(await impactOf(value, filter)); } catch (e) { setError(e instanceof ValidationError ? e.errors[0]?.message : e instanceof ApiError ? e.message : String(e)); }
  };
  const share = (i: Impact) => formatNumber(i.total ? i.affected / i.total : 0, locale, { style: 'percent', maximumFractionDigits: 1 });
  return (
    <>
      <h2 className="nl-q-h2 nl-q-gap">{t('floorTitle')}</h2>
      <div className="nl-q-actions">
        <Field label={t('floorLabel')}><TextInput inputMode="decimal" value={rating} onChange={e => setRating(e.target.value)} /></Field>
        <Button variant="secondary" onClick={() => void simulate()}>{t('simulate')}</Button>
        {canEdit && rule ? <Button disabled={save.isPending} onClick={() => save.mutate({ key: 'rating_floor', value: { ...rule.value, rating: value } })}>{t('saveFloor')}</Button> : null}
      </div>
      {impact ? <p role="status" className="nl-q-note">{t('impact', { rating: formatNumber(impact.rating, locale), affected: formatNumber(impact.affected, locale), share: share(impact) })}</p> : null}
      {error || save.error ? <p role="alert" className="nl-q-error">{error ?? (save.error as Error).message}</p> : null}
    </>
  );
}

