import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useSearch } from '@tanstack/react-router';
import { BoHeader, Button, ChartCard, DataTable, Dialog, ErrorState, Field, formatNumber, PageSkeleton, TextArea, TextInput, UnderlineTabs, useLocale, type DataTableAction, type DataTableColumn, type StatusTone } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import type { PlaceFilter } from '../shell/PlaceFilters';
import { usePlace, usePlaceName } from '../shell/scope';
import { statusTone } from '../shell/tables';
import { SCREEN_PATH } from '../shell/screens';
import { flagsQuery, impactOf, rulesQuery, useFlagAction, useFlagDecision, useSaveRule, type Flag, type Impact, type Rule } from './api';
import { useTrustT, type TrustKey } from './messages';
import { AiScreening, WeeklyScan } from './AiScreening';
import { useAiT } from './aiMessages';
import '../shell/queues.css';
import './trust.css';

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
  // S-156: no-shows reported on a booking (the provider's: no_show; the customer's: customer_no_show) are confirmed
  if (f.rule === 'no_show' || f.rule === 'customer_no_show') return 'confirm';
  if (f.rule === 'regulated_without_permit') return 'suspend_listings';
  return 'escalate';
}

/**
 * Trust & safety (S-93, design 03 `trust`): tier rules, automatic consequences and the rating floor as configuration
 * (role `decide` edits them), the keyword lists of the off-platform detector and listing vetting, and the open flags
 * with their action or Dismiss. Admin and trust & safety open it. S-158: the AI screening queue and the weekly scan as
 * the screen's other views (`?view=ai|scan`).
 */
export type TrustView = 'rules' | 'ai' | 'scan';

/** The screen's views (S-158): rules & flags, the AI screening queue, the weekly anomaly scan. */
function TrustTabs({ view }: { view: TrustView }) {
  const t = useAiT();
  const navigate = useNavigate();
  return (
    <UnderlineTabs aria-label={t('tabs')} value={view} options={[{ value: 'rules', label: t('tabRules') }, { value: 'ai', label: t('tabAi') }, { value: 'scan', label: t('tabScan') }]}
      onChange={next => void navigate({ to: SCREEN_PATH.trust, search: (next === 'rules' ? {} : { view: next }) as never })} />
  );
}

export function TrustSafety() {
  const t = useTrustT();
  const search = useSearch({ strict: false }) as PlaceFilter & { view?: TrustView };
  const filter = usePlace();
  const view = search.view ?? 'rules';
  if (view !== 'rules') {
    return (
      <div className="bo-page">
        <BoHeader title={t('title')} sub={t('lede')} />
        <TrustTabs view={view} />
        {view === 'ai' ? <AiScreening /> : <WeeklyScan />}
      </div>
    );
  }
  return <RulesAndFlags filter={filter} />;
}

function RulesAndFlags({ filter }: { filter: PlaceFilter }) {
  const t = useTrustT();
  const rules = useQuery(rulesQuery);
  const flags = useQuery(flagsQuery(filter));
  const { can, roleName } = useGrant();
  const placeName = usePlaceName();
  const head = <><BoHeader title={t('title')} sub={`${placeName} · ${t('lede')}`} actions={<Link className="btn btn-secondary" to="/trust/appeals">{t('appeals')}</Link>} /><TrustTabs view="rules" /></>;
  if (rules.isPending || flags.isPending) return <div className="bo-page">{head}<PageSkeleton kpis={0} rows={6} /></div>;
  if (rules.isError || (flags.isError && !flags.data)) {
    return <div className="bo-page">{head}{flags.error instanceof ValidationError ? <p role="alert" className="nl-q-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => { void rules.refetch(); void flags.refetch(); }} />}</div>;
  }
  const byKey = Object.fromEntries(rules.data!.items.map(r => [r.key, r])) as Record<string, Rule>;
  const v = (k: string) => byKey[k]?.value;
  return (
    <div className="bo-page">
      {head}
      <Flags flags={flags.data!.items} canDecide={can('decide')} roleName={roleName} />
      <div className="nl-cols-wrap"><div className="nl-cols">
        <RatingFloor rule={byKey.rating_floor} filter={filter} canEdit={can('decide')} />
          <ChartCard id="ts-consequences" title={t('consequences')}>
            <ul className="nl-rows">
              <li>{t('c_floor', { rating: num(v('rating_floor'), 'rating'), days: num(v('rating_floor'), 'days'), recover: num(v('rating_floor'), 'recoverDays') })}</li>
              <li>{t('c_noShows', { count: num(v('provider_no_shows'), 'count'), days: num(v('provider_no_shows'), 'days') })}</li>
              <li>{t('c_photo', { hours: num(v('missing_photo_delay'), 'hours') })}</li>
              <li>{t('c_offPlatform')}</li>
              <li>{t('c_customer', { count: num(v('customer_no_shows'), 'count') })}</li>
            </ul>
          </ChartCard>
      </div></div>
      <ChartCard id="ts-tiers" title={t('tierRules')}>
        <TierRules byKey={byKey} canEdit={can('decide')} roleName={roleName} />
      </ChartCard>
      <Keywords byKey={byKey} canEdit={can('decide')} />
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
      <DataTable<TierRow> dense entity={t('tierEntity')} plural={t('tierPlural')} columns={columns} rows={rows} pageSize={5}
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
      actions={<><Button variant="tertiary" onClick={onClose}>{t('cancel')}</Button><Button disabled={save.isPending} onClick={submit}>{t('save')}</Button></>}>
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
    <ChartCard id="ts-keywords" title={t('keywordsTitle')}>
      <div className="nl-cols-wrap"><div className="nl-cols">
        <WordList rule={byKey.off_platform_phrases} field="phrases" label={t('offPlatformLabel')} canEdit={canEdit} />
        <WordList rule={byKey.restricted_keywords} field="words" label={t('restrictedLabel')} canEdit={canEdit} />
      </div></div>
    </ChartCard>
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

const ACTIONS = ['actioned', 'hide_review', 'warn', 'coach', 'confirm', 'suspend_listings', 'escalate'] as const;
const FLAG_VIEWS = ['open', 'done', 'all'] as const;
type FlagView = (typeof FLAG_VIEWS)[number];
/** E-15 colour roles: an open flag is due (warning), an actioned one done (success), a dismissed one neutral. */
const flagTone = (f: Flag): StatusTone => (f.state === 'open' ? 'warning' : f.state === 'dismissed' ? 'neutral' : 'success');
interface FlagRow { id: string; who: string; what: string; why: string; state: string; action: string; src: Flag }

/**
 * The open flags (S-93; E-15 S-177): a dense queue with its views as tabs (open first), status tags, the rule's own
 * action on each open row (Suspend listing rights in red) and Dismiss, one or many at once (role `decide`).
 */
function Flags({ flags, canDecide, roleName }: { flags: Flag[]; canDecide: boolean; roleName: string }) {
  const t = useTrustT();
  const { locale } = useLocale();
  const act = useFlagAction();
  const decide = useFlagDecision();
  const [view, setView] = useState<FlagView>('open');
  const refusal = [act.error, decide.error].find(Boolean) as ApiError | Error | undefined;
  const who = (f: Flag) => f.businessName ?? (f.targetType === 'customer' ? t('customer') : f.targetType === 'courier' ? t('courier') : f.targetType);
  const what = (f: Flag) => { const k = `w_${f.rule}` as TrustKey; const s = t(k); return s === k ? t('w_other') : s; };
  const inView = (f: Flag, v: FlagView) => v === 'all' || (v === 'open' ? f.state === 'open' : f.state !== 'open');
  const rows: FlagRow[] = flags.filter(f => inView(f, view)).map(f => ({
    id: f.id, who: who(f), what: what(f), why: f.explanation, action: actionFor(f), src: f,
    state: f.state === 'open' ? t('st_open') : f.state === 'dismissed' ? t('dismissed') : t('done', { action: t(`a_${f.action === 'actioned' || !f.action ? actionFor(f) : f.action}` as TrustKey) }),
  }));
  const columns: DataTableColumn<FlagRow>[] = [
    { key: 'who', label: t('colWho'), sub: 'why', subLabel: t('colWhy'), primary: true },
    { key: 'what', label: t('colWhat') },
    { key: 'state', label: t('colState'), type: 'tag', filter: false },
    { key: 'action', label: t('colAction'), hidden: true, editable: false, format: v => t(`a_${String(v)}` as TrustKey) },
  ];
  const actions: DataTableAction<FlagRow>[] = [
    ...ACTIONS.map(a => ({ id: a, label: t(`a_${a}` as TrustKey), perm: 'update' as const, inline: true, bulk: false, danger: a === 'suspend_listings', when: { key: 'action' as const, in: [a] } })),
    { id: 'dismiss', label: t('dismiss'), perm: 'update', inline: true, when: { key: 'state', in: [t('st_open')] } },
  ];
  const count = (v: FlagView) => formatNumber(flags.filter(f => inView(f, v)).length, locale);
  return (
    <section className="nl-ts-flags" aria-labelledby="ts-flags">
      <h2 className="bo-card-title" id="ts-flags">{t('openFlags', { n: flags.filter(f => f.state === 'open').length })}</h2>
      <DataTable<FlagRow> dense entity={t('flagEntity')} plural={t('flagPlural')} columns={columns} rows={rows} pageSize={10} roleName={roleName}
        views={<UnderlineTabs aria-label={t('flagViews')} value={view} onChange={setView} options={FLAG_VIEWS.map(v => ({ value: v, label: t(`fv_${v}` as TrustKey), count: count(v) }))} />}
        rowTones={r => ({ state: statusTone(flagTone(r.src)) })} emptyText={t('noFlags')} reportName="trust-flags"
        can={{ create: false, update: canDecide, delete: false }} actions={actions}
        onAction={async (a, picked) => {
          for (const r of picked) {
            if (a.id === 'dismiss') await decide.mutateAsync({ id: r.id, decision: 'dismissed' });
            else if (a.id === 'actioned') await decide.mutateAsync({ id: r.id, decision: 'actioned' });
            else await act.mutateAsync({ id: r.id, action: a.id });
          }
        }} />
      {refusal ? <p role="alert" className="nl-q-error">{refusal.message}</p> : null}
    </section>
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
    <ChartCard id="ts-floor" title={t('floorTitle')}>
      <div className="nl-ts-floor">
        <Field label={t('floorLabel')}><TextInput inputMode="decimal" value={rating} onChange={e => setRating(e.target.value)} /></Field>
        <Button variant="secondary" onClick={() => void simulate()}>{t('simulate')}</Button>
        {canEdit && rule ? <Button disabled={save.isPending} onClick={() => save.mutate({ key: 'rating_floor', value: { ...rule.value, rating: value } })}>{t('saveFloor')}</Button> : null}
      </div>
      {impact ? <p role="status" className="nl-q-note">{t('impact', { rating: formatNumber(impact.rating, locale), affected: formatNumber(impact.affected, locale), share: share(impact) })}</p> : null}
      {error || save.error ? <p role="alert" className="nl-q-error">{error ?? (save.error as Error).message}</p> : null}
    </ChartCard>
  );
}

