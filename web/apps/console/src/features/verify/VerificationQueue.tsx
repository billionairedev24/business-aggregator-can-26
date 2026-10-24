import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { Gavel, MagnifyingGlass } from '@phosphor-icons/react';
import {
  Button, Checkbox, DataTable, Dialog, ErrorState, FactList, Field, FilterChip, formatDate, formatNumber, KpiStrip, OptionCard, PageSkeleton, RadioGroup, SheetSection, StatusTag, Tag, TextArea, UnderlineTabs, useLocale,
  type DataTableAction, type DataTableColumn, type DataTableTone,
} from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useAge } from '../shell/age';
import { meQuery } from '../shell/api';
import { useGrant } from '../shell/grant';
import { useRegionName, type PlaceFilter } from '../shell/PlaceFilters';
import { usePlace } from '../shell/scope';
import { SavedViews, statusTone } from '../shell/tables';
import { SCREEN_PATH } from '../shell/screens';
import {
  detailQuery, queueQuery, useDecideApplication, useDecideIdentity, useDecideRegistry, useReviewApplication,
  type Application, type Check, type Detail, type Recommendation,
} from './api';
import { ApplicationsHead } from './ApplicationsHead';
import { RegistryQueue } from './RegistryQueue';
import { useVerifyT, type VerifyKey, type VerifyT } from './messages';
import '../shell/queues.css';
import './applications.css';

export const APP_VIEWS = ['review', 'decision', 'info', 'approved', 'all'] as const;
export type AppView = (typeof APP_VIEWS)[number];
export interface VerifySearch extends PlaceFilter { view?: 'registry'; stage?: AppView; risk?: boolean }
const viewOf = (a: Application): Exclude<AppView, 'all'> =>
  a.status === 'pending' ? stageOf(a) : a.decision === 'approved' ? 'approved' : 'info';

interface Row {
  id: string; name: string; sub: string; cat: string; region: string; checks: string; risk: string; wait: string; stage: string; status: string;
  app: Application;
}

/** A check as the design names it ("KYC", "AMVIC", "GST #"); regulators come from the data, never from code. */
export function checkLabel(c: Pick<Check, 'key' | 'registry'>, t: VerifyT): string {
  const kind = c.key.startsWith('licence:') ? 'licence' : c.key;
  const registry = c.registry ?? c.key.replace(/^licence:/, '');
  const key = `c_${kind}` as VerifyKey;
  const label = t(key, { registry });
  return label === key ? c.key : label;
}
const MARK: Record<Check['state'], string> = { passed: '✓', review: '✗', failed: '✗', waiting: '○' };

export type Stage = 'review' | 'decision';
/**
 * The two status stages of an application (redesign § 2.6 "two-person review", DECISIONS 2026-10-18): stage 1, Review —
 * a reviewer settles the manual checks and recommends; stage 2, Decision — another staff member approves or requests
 * info. The api says which (`stage`); an older api without it falls back to "an automated check waits for an agent".
 */
export const stageOf = (a: Pick<Application, 'checks' | 'stage'>): Stage =>
  a.stage ?? (a.checks.some(c => c.state === 'review') ? 'review' : 'decision');
const REC_KEY: Record<Recommendation, VerifyKey> = { approve: 'rec_approve', reject: 'rec_reject', request_info: 'rec_request_info' };

/** Who may do what on one application: the active role's grant, and whether the signed-in person reviewed this round. */
export function useHands(d?: Detail) {
  const { can } = useGrant();
  const me = useQuery(meQuery).data?.userId;
  const fourEyes = d?.fourEyes ?? true;
  const reviewedByMe = fourEyes && !!me && (d?.reviews ?? []).some(r => !r.decisionId && r.reviewedBy === me);
  return { canReview: can('verify'), canApprove: can('approve'), fourEyes, reviewedByMe };
}

export const statusText = (a: Application, t: VerifyT) =>
  a.status === 'pending' ? t('pending') : a.decision === 'approved' ? t('approved') : a.decision === 'info_requested' ? t('infoRequested') : a.status;
export const riskText = (a: Application, t: VerifyT) => t(a.risk === 'low' ? 'riskLow' : a.risk === 'medium' ? 'riskMedium' : 'riskHigh');

/**
 * Applications › Businesses (S-79; redesign § 2.3 → BO `apps` + the BO:811 sheet): submitted applications with their
 * automated checks, risk, waiting time and stage; approve or request info inline (role `verify`), and the right sheet
 * with every submitted field, the checks, the owners' identity and the registry reviews behind them (S-22, S-23) and
 * the decision history. Admin and trust & safety open it; every other role is refused by the api (denied banner).
 */
export function VerificationQueue() {
  const search = useSearch({ strict: false }) as VerifySearch;
  // S-158: the registry check queue is the screen's second tab
  const place = usePlace();
  if (search.view === 'registry') return <RegistryQueue filter={place} />;
  return <BusinessQueue />;
}

function BusinessQueue() {
  const t = useVerifyT();
  const { locale } = useLocale();
  const search = useSearch({ strict: false }) as VerifySearch;
  const filter = usePlace();
  const navigate = useNavigate();
  const query = useQuery(queueQuery(filter));
  const { can, roleName } = useGrant();
  const regionName = useRegionName();
  const age = useAge();
  const open = (id: string) => void navigate({ to: '/verification/$applicationId', params: { applicationId: id } });
  const go = (next: Partial<VerifySearch>) => void navigate({ to: SCREEN_PATH.verify, search: { ...search, ...next } as never, replace: true });
  const view = search.stage ?? 'all';

  if (query.isPending) return <div className="bo-page"><ApplicationsHead tab="businesses" filter={filter} /><PageSkeleton kpis={4} rows={6} /></div>;
  if (query.isError && !query.data) {
    return (
      <div className="bo-page">
        <ApplicationsHead tab="businesses" filter={filter} />
        {query.error instanceof ValidationError ? <p role="alert" className="nl-q-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}
      </div>
    );
  }
  const data = query.data!;
  const items = data.items.filter(a => !search.risk || a.risk === 'high');
  const count = (v: AppView) => formatNumber(items.filter(a => v === 'all' || viewOf(a) === v).length, locale);
  const rows: Row[] = items.filter(a => view === 'all' || viewOf(a) === view).map(a => ({
    id: a.merchantId, name: a.businessName, sub: a.legalName, cat: a.categories[0] ?? '—', region: regionName(a.province),
    checks: a.checks.map(c => `${checkLabel(c, t)} ${MARK[c.state]}`).join(' · '), risk: riskText(a, t),
    wait: a.status === 'pending' ? age(a.submittedAt) : '—', stage: a.status === 'pending' ? t(`stage_${stageOf(a)}`) : statusText(a, t), status: statusText(a, t), app: a,
  }));
  // E-15 colour roles: risk is a fact (neutral · warning · danger), the stage a status (who it waits for)
  const tones = (r: Row): Partial<Record<keyof Row, DataTableTone>> => ({
    risk: r.app.risk === 'low' ? 'tag-neutral' : r.app.risk === 'medium' ? 'tag-warning' : 'tag-danger',
    stage: statusTone(({ review: 'info', decision: 'warning', approved: 'success', info: 'neutral' } as const)[viewOf(r.app)]),
  });
  const columns: DataTableColumn<Row>[] = [
    { key: 'name', label: t('colApplicant'), sub: 'sub', subLabel: t('colBusiness'), primary: true },
    { key: 'cat', label: t('colCategory'), filter: 'facet' },
    { key: 'region', label: t('colRegion'), filter: 'facet' },
    { key: 'checks', label: t('colChecks'), editable: false },
    { key: 'risk', label: t('colRisk'), type: 'tag', editable: false },
    { key: 'wait', label: t('colWaiting'), editable: false },
    { key: 'stage', label: t('colStage'), type: 'tag', editable: false },
  ];
  // Stage 1 "Review" and stage 2 "Decide" open the application's sheet, where the checks, the review and the reasons are
  const fourEyes = data.fourEyes;
  const actions: DataTableAction<Row>[] = [
    ...(can('verify') ? [{ id: 'review', label: t('review'), icon: MagnifyingGlass, perm: 'update', inline: true, bulk: false, when: { key: 'stage', in: [t('stage_review')] } } as const] : []),
    ...(can('approve') ? [{ id: 'decide', label: t('decide'), icon: Gavel, perm: 'update', inline: true, bulk: false,
      when: { key: 'stage', in: fourEyes ? [t('stage_decision')] : [t('stage_review'), t('stage_decision')] } } as const] : []),
  ];
  const onAction = async (_: DataTableAction<Row>, hit: Row[]) => {
    open(hit[0]!.app.merchantId);
    return false;
  };
  const pending = data.items.filter(a => a.status === 'pending');
  const inReview = pending.filter(a => stageOf(a) === 'review').length;
  const decided = (d: Application['decision']) => formatNumber(data.items.filter(a => a.status !== 'pending' && a.decision === d).length, locale);
  const median = data.medianDecisionHours;
  const sub = median == null
    ? t('subNoMedian', { n: data.pending })
    : t('sub', { n: data.pending, median: t('medianDays', { d: formatNumber(median / 24, locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }) });
  return (
    <div className="bo-page">
      <ApplicationsHead tab="businesses" filter={filter} sub={sub} />
      <KpiStrip label={t('kpis')} items={[
        { key: 'review', label: t('k_review'), value: formatNumber(inReview, locale), delta: t('k_reviewNote') },
        { key: 'decision', label: t('k_decision'), value: formatNumber(pending.length - inReview, locale), delta: t('k_decisionNote') },
        { key: 'info', label: t('k_info'), value: decided('info_requested'), delta: t('k_infoNote') },
        { key: 'approved', label: t('k_approved'), value: decided('approved'), delta: t('k_approvedNote') },
      ]} />
      <DataTable<Row> dense entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} rowTones={tones} pageSize={25}
        tabs={<UnderlineTabs aria-label={t('appViews')} value={view} onChange={v => go({ stage: v === 'all' ? undefined : v })}
          options={APP_VIEWS.map(v => ({ value: v, label: t(`av_${v}` as VerifyKey), count: count(v) }))} />}
        filters={<>
          <FilterChip label={t('highRisk')} applied={!!search.risk} onToggle={() => go({ risk: search.risk ? undefined : true })} />
          <SavedViews screen="applications" current={{ stage: search.stage, risk: search.risk }} onApply={v => void navigate({ to: SCREEN_PATH.verify, search: v as never })} />
        </>}
        can={{ create: false, update: can('verify') || can('approve'), delete: false }} roleName={roleName} actions={actions} onAction={onAction}
        emptyText={t('empty')} openLabel={t('open')} onOpen={r => open(r.id)} />
    </div>
  );
}

/** Review → Decision with the current one marked (BO `apps` "Step 1 of 2 · Review"). */
export function Stages({ stage }: { stage: Stage | 'done' }) {
  const t = useVerifyT();
  const steps: Stage[] = ['review', 'decision'];
  return (
    <ol className="nl-ap-stages" aria-label={t('stagesLabel')}>
      {steps.map((s, i) => {
        const state = stage === 'done' || (stage === 'decision' && s === 'review') ? 'done' : s === stage ? 'current' : 'next';
        return (
          <li key={s} className="nl-ap-stage" data-state={state} aria-current={state === 'current' ? 'step' : undefined}>
            <span className="nl-ap-stage-n" aria-hidden>{i + 1}</span>
            <span className="nl-ap-stage-text">
              <strong>{t(`step_${s}`)}</strong>{state !== 'next' ? <span className="nl-sr-only"> · {t(state === 'current' ? 'stepCurrent' : 'stepDone')}</span> : null}
              <span className="nl-ap-stage-desc">{t(`step_${s}Desc`)}</span>
            </span>
          </li>
        );
      })}
    </ol>
  );
}

/** The current review (stage 2) or "not reviewed yet" (stage 1): who, what they recommend, when, the checks and note. */
export function ReviewSection({ d }: { d: Detail }) {
  const t = useVerifyT();
  const { locale } = useLocale();
  const r = d.application.review;
  if (d.application.status !== 'pending') return null;
  return (
    <SheetSection level={2} title={t('reviewTitle')}>
      {!r ? <p className="bo-muted">{t('notReviewed')}</p> : (
        <FactList rows={[
          [t('f_reviewer'), r.reviewerName],
          [t('f_recommendation'), <Tag key="rec" tone={r.recommendation === 'approve' ? 'success' : 'warning'}>{t(REC_KEY[r.recommendation])}</Tag>],
          [t('f_reviewed'), formatDate(r.reviewedAt, locale, 'dateTime')],
          r.checkKeys.length ? [t('f_redo'), r.checkKeys.map(k => checkLabel({ key: k, registry: null }, t)).join(', ')] : null,
          r.note ? [t('f_reviewNote'), r.note] : null,
        ]} />
      )}
    </SheetSection>
  );
}

/** The application's submitted fields (the page's Business tab). */
export function BusinessFacts({ d }: { d: Detail }) {
  const t = useVerifyT();
  const { locale } = useLocale();
  const regionName = useRegionName();
  const age = useAge();
  const a = d.application;
  const structureKey = `st_${a.structure}` as VerifyKey;
  const structure = !a.structure ? '—' : t(structureKey) === structureKey ? a.structure : t(structureKey);
  return (
    <FactList rows={[
      [t('f_business'), a.businessName], [t('f_legal'), a.legalName],
      [t('f_type'), a.type ? t(`t_${a.type}` as VerifyKey) : '—'], [t('f_structure'), structure],
      [t('f_categories'), a.categories.length ? a.categories.join(', ') : '—'],
      [t('f_place'), [a.city, a.province ? regionName(a.province) : null].filter(Boolean).join(', ') || '—'],
      [t('f_submitted'), a.submittedAt ? `${formatDate(a.submittedAt, locale, 'date')} · ${t('submittedAgo', { age: age(a.submittedAt) })}` : '—'],
      [t('f_risk'), riskText(a, t)],
      [t('f_decision'), statusText(a, t)],
    ]} />
  );
}

/** The evidence: the automated checks, the owners' identity and the registry lookups, each settled here (role `verify`). */
export function ChecksEvidence({ d, canSettle: canDecide }: { d: Detail; canSettle: boolean }) {
  const t = useVerifyT();
  const identity = useDecideIdentity();
  const registry = useDecideRegistry();
  const [error, setError] = useState<string>();
  const run = (p: Promise<unknown>) => { setError(undefined); p.catch(e => setError(e instanceof ApiError ? e.message : String(e))); };
  const a = d.application;
  return (
    <>
      {error ? <p role="alert" className="nl-q-error">{error}</p> : null}
      <SheetSection level={2} title={t('checksTitle')}>
        <ul className="nl-ap-list">
          {a.checks.map(c => (
            <li key={c.id} className="nl-ap-row"><span>{checkLabel(c, t)}{c.reference ? ` · ${c.reference}` : ''}</span>
              <StatusTag tone={c.state === 'passed' ? 'success' : c.state === 'waiting' ? 'neutral' : c.state === 'failed' ? 'danger' : 'warning'}>{t(`s_${c.state}` as VerifyKey)}</StatusTag></li>
          ))}
        </ul>
      </SheetSection>
      <SheetSection level={2} title={t('ownersTitle')}>
        <ul className="nl-ap-list">
          {d.owners.map(o => (
            <li key={o.checkId ?? o.principalName} className="nl-ap-row">
              <span>{t('ownerLine', { name: o.principalName, role: t(`p_${o.role}` as VerifyKey) })}
                {o.nameMatch === 'mismatch' ? <span className="nl-ap-note">{t('nameMismatch')}</span> : null}
                {o.dobMatch === 'mismatch' ? <span className="nl-ap-note">{t('dobMismatch')}</span> : null}
                {o.reviewNote ? <span className="nl-ap-note">{o.reviewNote}</span> : null}
              </span>
              {o.status === 'review' && o.checkId && canDecide
                ? <span className="nl-q-actions">
                    <Button variant="secondary" onClick={() => run(identity.mutateAsync({ id: a.merchantId, checkId: o.checkId!, decision: 'approve' }))}>{t('approve')}</Button>
                    <Button variant="tertiary" onClick={() => run(identity.mutateAsync({ id: a.merchantId, checkId: o.checkId!, decision: 'reject' }))}>{t('reject')}</Button>
                  </span>
                : <StatusTag tone={o.status === 'verified' ? 'success' : o.status === 'review' ? 'warning' : 'neutral'}>{t(`o_${o.status}` as VerifyKey)}</StatusTag>}
            </li>
          ))}
        </ul>
      </SheetSection>
      <SheetSection level={2} title={t('registryTitle')}>
        {d.registryReviews.length === 0 ? <p className="bo-muted">{t('noReviews')}</p> : (
          <ul className="nl-ap-list">
            {d.registryReviews.map(r => (
              <li key={r.id} className="nl-ap-row">
                <span>{t('registryLine', { registry: r.registry ?? r.source, number: r.queryNumber, outcome: r.outcome.replace(/_/g, ' ') })}
                  {r.recordName ? <span className="nl-ap-note">{r.recordName}{r.recordStatus ? ` · ${r.recordStatus}` : ''}</span> : null}</span>
                {r.reviewState === 'open' && canDecide
                  ? <span className="nl-q-actions">
                      <Button variant="secondary" onClick={() => run(registry.mutateAsync({ reviewId: r.id, decision: 'approve' }))}>{t('approve')}</Button>
                      <Button variant="tertiary" onClick={() => run(registry.mutateAsync({ reviewId: r.id, decision: 'reject' }))}>{t('reject')}</Button>
                    </span>
                  : <StatusTag tone={r.reviewState === 'approved' ? 'success' : r.reviewState === 'open' ? 'warning' : 'neutral'}>{t(`r_${r.reviewState ?? 'open'}` as VerifyKey)}</StatusTag>}
              </li>
            ))}
          </ul>
        )}
      </SheetSection>
    </>
  );
}

/** Decisions and reviews, newest first. */
export function historyOf(d: Detail, t: VerifyT, locale: 'en' | 'fr') {
  return [
    ...d.decisions.map(h => ({ at: h.decidedAt, id: h.id, text: t(h.decision === 'approved' ? 'h_approved' : 'h_info_requested', { date: formatDate(h.decidedAt, locale, 'date') }), keys: h.checkKeys, note: h.note })),
    ...d.reviews.map(r => ({ at: r.reviewedAt, id: r.id, text: t('h_reviewed', { name: r.reviewerName, recommendation: t(REC_KEY[r.recommendation]), date: formatDate(r.reviewedAt, locale, 'date') }), keys: r.checkKeys, note: r.note })),
  ].sort((x, y) => y.at.localeCompare(x.at)).map(h => ({
    key: h.id, at: h.at,
    text: `${h.text}${h.keys.length ? ` · ${h.keys.map(k => checkLabel({ key: k, registry: null }, t)).join(', ')}` : ''}${h.note ? ` · ${h.note}` : ''}`,
  }));
}

/** "Request info": the checks to redo (not passed ones preselected) and the note emailed to the owners. */
export function RequestInfoDialog({ app, keys: reviewed, note: reviewNote, onClose, onSent }: {
  app: Application; keys?: string[]; note?: string; onClose: () => void; onSent: () => void;
}) {
  const t = useVerifyT();
  const decide = useDecideApplication();
  // the reviewer's request (two-person review), else every check that didn't pass
  const [keys, setKeys] = useState<string[]>(() => reviewed?.length ? reviewed : app.checks.filter(c => c.state !== 'passed').map(c => c.key));
  const [note, setNote] = useState(reviewNote ?? '');
  const fieldErrors = decide.error instanceof ValidationError ? decide.error.byField() : {};
  const other = decide.error && !(decide.error instanceof ValidationError) ? (decide.error as Error).message : undefined;
  const send = () => decide.mutate({ id: app.merchantId, decision: 'request_info', checkKeys: keys, note }, { onSuccess: onSent });
  return (
    <Dialog open onClose={onClose} title={t('infoTitle', { business: app.businessName })}
      actions={<><Button variant="secondary" onClick={onClose}>{t('cancel')}</Button><Button disabled={decide.isPending} onClick={send}>{t('infoSend')}</Button></>}>
      <fieldset className="nl-q-field" aria-describedby={fieldErrors.checkKeys ? 'nl-info-keys-err' : undefined}>
        <legend>{t('infoChecks')}</legend>
        {app.checks.map(c => (
          <Checkbox key={c.key} label={checkLabel(c, t)} checked={keys.includes(c.key)}
            onChange={on => setKeys(k => (on ? [...k, c.key] : k.filter(x => x !== c.key)))} />
        ))}
        {fieldErrors.checkKeys ? <span id="nl-info-keys-err" className="nl-q-error" role="alert">{fieldErrors.checkKeys}</span> : null}
      </fieldset>
      <Field label={t('infoNote')} hint={t('infoNoteHint')} error={fieldErrors.note}>
        <TextArea value={note} maxLength={500} onChange={e => setNote(e.target.value)} />
      </Field>
      {other ? <p role="alert" className="nl-q-error">{other}</p> : null}
    </Dialog>
  );
}

/**
 * Stage 1 (two-person review): the reviewer's recommendation — approve, reject or request info (with the checks to
 * redo) — and a note for the approver (required to reject or request info; internal, never emailed).
 */
export function ReviewDialog({ app, onClose, onSent }: { app: Application; onClose: () => void; onSent: () => void }) {
  const t = useVerifyT();
  const review = useReviewApplication();
  const [rec, setRec] = useState<Recommendation>('approve');
  const [keys, setKeys] = useState<string[]>(() => app.checks.filter(c => c.state !== 'passed').map(c => c.key));
  const [note, setNote] = useState('');
  const fieldErrors = review.error instanceof ValidationError ? review.error.byField() : {};
  const other = review.error && !(review.error instanceof ValidationError) ? (review.error as Error).message : undefined;
  const send = () => review.mutate({ id: app.merchantId, recommendation: rec, checkKeys: rec === 'request_info' ? keys : undefined, note: note.trim() || undefined }, { onSuccess: onSent });
  const options: Recommendation[] = ['approve', 'reject', 'request_info'];
  return (
    <Dialog open onClose={onClose} title={t('reviewDialogTitle', { business: app.businessName })}
      actions={<><Button variant="secondary" onClick={onClose}>{t('cancel')}</Button><Button disabled={review.isPending} onClick={send}>{t('reviewSend')}</Button></>}>
      <p className="nl-q-note">{t('reviewLede')}</p>
      <fieldset className="nl-q-field" aria-describedby={fieldErrors.recommendation ? 'nl-rev-rec-err' : undefined}>
        <legend>{t('f_recommendation')}</legend>
        <RadioGroup className="nl-ap-recs" aria-label={t('f_recommendation')}>
          {options.map(o => (
            <OptionCard key={o} role="radio" aria-checked={rec === o} selected={rec === o} title={t(REC_KEY[o])} description={t(`${REC_KEY[o]}Desc` as VerifyKey)} onClick={() => setRec(o)} />
          ))}
        </RadioGroup>
        {fieldErrors.recommendation ? <span id="nl-rev-rec-err" className="nl-q-error" role="alert">{fieldErrors.recommendation}</span> : null}
      </fieldset>
      {rec === 'request_info' ? (
        <fieldset className="nl-q-field" aria-describedby={fieldErrors.checkKeys ? 'nl-rev-keys-err' : undefined}>
          <legend>{t('infoChecks')}</legend>
          {app.checks.map(c => (
            <Checkbox key={c.key} label={checkLabel(c, t)} checked={keys.includes(c.key)}
              onChange={on => setKeys(k => (on ? [...k, c.key] : k.filter(x => x !== c.key)))} />
          ))}
          {fieldErrors.checkKeys ? <span id="nl-rev-keys-err" className="nl-q-error" role="alert">{fieldErrors.checkKeys}</span> : null}
        </fieldset>
      ) : null}
      <Field label={t('reviewNoteLabel')} hint={t('reviewNoteHint')} error={fieldErrors.note}>
        <TextArea value={note} maxLength={500} onChange={e => setNote(e.target.value)} />
      </Field>
      {other ? <p role="alert" className="nl-q-error">{other}</p> : null}
    </Dialog>
  );
}

/** Stage 2, approving against the review (it recommended reject or request info): the approver says why. */
export function OverrideDialog({ app, onClose, onSent }: { app: Application; onClose: () => void; onSent: () => void }) {
  const t = useVerifyT();
  const decide = useDecideApplication();
  const [note, setNote] = useState('');
  const fieldErrors = decide.error instanceof ValidationError ? decide.error.byField() : {};
  const other = decide.error && !(decide.error instanceof ValidationError) ? (decide.error as Error).message : undefined;
  const rec = app.review?.recommendation ?? 'reject';
  return (
    <Dialog open onClose={onClose} title={t('overrideTitle', { business: app.businessName })}
      actions={<><Button variant="secondary" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={decide.isPending} onClick={() => decide.mutate({ id: app.merchantId, decision: 'approve', note }, { onSuccess: onSent })}>{t('approve')}</Button></>}>
      <p className="nl-q-note">{t('overrideLede', { name: app.review?.reviewerName ?? '', recommendation: t(REC_KEY[rec]) })}</p>
      <Field label={t('overrideNote')} hint={t('overrideNoteHint')} error={fieldErrors.note}>
        <TextArea value={note} maxLength={500} onChange={e => setNote(e.target.value)} />
      </Field>
      {other ? <p role="alert" className="nl-q-error">{other}</p> : null}
    </Dialog>
  );
}
