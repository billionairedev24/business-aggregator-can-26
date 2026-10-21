import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { Gavel, MagnifyingGlass } from '@phosphor-icons/react';
import {
  Button, Checkbox, DataTable, Dialog, Drawer, ErrorState, FactList, Field, formatDate, formatNumber, KpiStrip, OptionCard, PageSkeleton, RadioGroup, SheetSection, Tag, TextArea, useLocale,
  type DataTableAction, type DataTableColumn, type DataTableTone,
} from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useAge } from '../shell/age';
import { meQuery } from '../shell/api';
import { useGrant } from '../shell/grant';
import { useRegionName, type PlaceFilter } from '../shell/PlaceFilters';
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

export interface VerifySearch extends PlaceFilter { application?: string; view?: 'registry' }

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
function useHands(d?: Detail) {
  const { can } = useGrant();
  const me = useQuery(meQuery).data?.userId;
  const fourEyes = d?.fourEyes ?? true;
  const reviewedByMe = fourEyes && !!me && (d?.reviews ?? []).some(r => !r.decisionId && r.reviewedBy === me);
  return { canReview: can('verify'), canApprove: can('approve'), fourEyes, reviewedByMe };
}

const statusText = (a: Application, t: VerifyT) =>
  a.status === 'pending' ? t('pending') : a.decision === 'approved' ? t('approved') : a.decision === 'info_requested' ? t('infoRequested') : a.status;
const riskText = (a: Application, t: VerifyT) => t(a.risk === 'low' ? 'riskLow' : a.risk === 'medium' ? 'riskMedium' : 'riskHigh');

/**
 * Applications › Businesses (S-79; redesign § 2.3 → BO `apps` + the BO:811 sheet): submitted applications with their
 * automated checks, risk, waiting time and stage; approve or request info inline (role `verify`), and the right sheet
 * with every submitted field, the checks, the owners' identity and the registry reviews behind them (S-22, S-23) and
 * the decision history. Admin and trust & safety open it; every other role is refused by the api (denied banner).
 */
export function VerificationQueue() {
  const search = useSearch({ strict: false }) as VerifySearch;
  // S-158: the registry check queue is the screen's second tab
  if (search.view === 'registry') return <RegistryQueue filter={{ province: search.province, market: search.market }} />;
  return <BusinessQueue />;
}

function BusinessQueue() {
  const t = useVerifyT();
  const { locale } = useLocale();
  const search = useSearch({ strict: false }) as VerifySearch;
  const filter = { province: search.province, market: search.market };
  const navigate = useNavigate();
  const query = useQuery(queueQuery(filter));
  const { can, roleName } = useGrant();
  const regionName = useRegionName();
  const age = useAge();
  const [asking, setAsking] = useState<{ app: Application; keys?: string[]; note?: string } | null>(null);
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);
  const select = (id: string | undefined) => void navigate({ to: SCREEN_PATH.verify, search: { ...filter, application: id } as never });

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
  const rows: Row[] = data.items.map(a => ({
    id: a.merchantId, name: a.businessName, sub: a.legalName, cat: a.categories[0] ?? '—', region: regionName(a.province),
    checks: a.checks.map(c => `${checkLabel(c, t)} ${MARK[c.state]}`).join(' · '), risk: riskText(a, t),
    wait: a.status === 'pending' ? age(a.submittedAt) : '—', stage: a.status === 'pending' ? t(`stage_${stageOf(a)}`) : statusText(a, t), status: statusText(a, t), app: a,
  }));
  const tones = (r: Row): Partial<Record<keyof Row, DataTableTone>> => ({
    risk: r.app.risk === 'low' ? 'tag-accent' : 'tag-accent-2',
    stage: r.app.status !== 'pending' ? (r.app.decision === 'approved' ? 'tag-accent' : 'tag-neutral') : stageOf(r.app) === 'review' ? 'tag-neutral' : 'tag-highlight',
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
    select(hit[0]!.app.merchantId);
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
      {notice ? <p role={notice.tone === 'error' ? 'alert' : 'status'} className={notice.tone === 'error' ? 'nl-q-error' : 'nl-q-note'}>{notice.text}</p> : null}
      <DataTable<Row> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} rowTones={tones}
        can={{ create: false, update: can('verify') || can('approve'), delete: false }} roleName={roleName} actions={actions} onAction={onAction}
        emptyText={t('empty')} openLabel={t('open')} onOpen={r => select(r.id)} />
      {search.application ? <ApplicationSheet id={search.application} roleName={roleName} onClose={() => select(undefined)}
        onAsk={setAsking} onDone={text => setNotice({ tone: 'ok', text })} /> : null}
      {asking ? <RequestInfoDialog app={asking.app} keys={asking.keys} note={asking.note} onClose={() => setAsking(null)}
        onSent={() => { setNotice({ tone: 'ok', text: t('infoToast', { business: asking.app.businessName }) }); setAsking(null); }} /> : null}
    </div>
  );
}

/** Review → Decision with the current one marked (BO `apps` "Step 1 of 2 · Review"). */
function Stages({ stage }: { stage: Stage | 'done' }) {
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

/**
 * One application in the right sheet (BO:811): the stages, every submitted field, the review, checks, owners, registry
 * and history. The footer is the stage's action: "Review" (stage 1, `verify`) or Approve / Request info (stage 2,
 * `approve`) — disabled, with the reason beside it, for the person who reviewed this round (the api refuses it too).
 */
function ApplicationSheet({ id, roleName, onClose, onAsk, onDone }: {
  id: string; roleName: string; onClose: () => void;
  onAsk: (v: { app: Application; keys?: string[]; note?: string }) => void; onDone: (text: string) => void;
}) {
  const t = useVerifyT();
  const decide = useDecideApplication();
  const query = useQuery(detailQuery(id));
  const d = query.data;
  const a = d?.application;
  const { canReview, canApprove, fourEyes, reviewedByMe } = useHands(d);
  const [dialog, setDialog] = useState<'review' | 'override' | null>(null);
  const [error, setError] = useState<string>();
  const stage = a ? stageOf(a) : 'review';
  const current = a?.review ?? null;
  const approve = async (app: Application) => {
    setError(undefined);
    if (current && current.recommendation !== 'approve') { setDialog('override'); return; }
    try {
      await decide.mutateAsync({ id: app.merchantId, decision: 'approve' });
      onDone(t('approvedToast', { business: app.businessName }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  };
  const showReview = !!a && canReview && stage === 'review';
  const showDecide = !!a && canApprove && (stage === 'decision' || !fourEyes);
  const footer = a?.status === 'pending' ? (
    showReview || showDecide ? (
      <div className="nl-ap-footer">
        <div className="nl-q-actions">
          {showReview ? <Button onClick={() => setDialog('review')}>{t('review')}</Button> : null}
          {showDecide ? <>
            <Button variant={showReview ? 'secondary' : 'primary'} disabled={decide.isPending || reviewedByMe} aria-describedby={reviewedByMe ? 'nl-ap-same' : undefined}
              onClick={() => void approve(a)}>{t('approve')}</Button>
            <Button variant="secondary" disabled={reviewedByMe} aria-describedby={reviewedByMe ? 'nl-ap-same' : undefined}
              onClick={() => onAsk({ app: a, keys: current?.recommendation === 'request_info' ? current.checkKeys : undefined, note: current?.recommendation === 'request_info' ? current.note ?? undefined : undefined })}>{t('requestInfo')}</Button>
          </> : null}
        </div>
        {reviewedByMe && showDecide ? <p id="nl-ap-same" className="nl-q-note">{t('sameReviewer')}</p> : null}
        {error ? <p role="alert" className="nl-q-error">{error}</p> : null}
      </div>
    ) : <span className="nl-q-note">{stage === 'review' && canApprove ? t('waitingReview') : t('viewOnly', { role: roleName })}</span>
  ) : undefined;
  return (
    <Drawer open onClose={onClose} title={a ? a.businessName : t('entity')} footer={footer}>
      {query.isPending ? <div aria-busy="true" className="nl-q-note">…</div>
        : query.isError ? <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />
          : <ApplicationBody d={query.data} canSettle={canReview} />}
      {dialog === 'review' && a ? <ReviewDialog app={a} onClose={() => setDialog(null)}
        onSent={() => { setDialog(null); onDone(t('reviewedToast', { business: a.businessName })); }} /> : null}
      {dialog === 'override' && a ? <OverrideDialog app={a} onClose={() => setDialog(null)}
        onSent={() => { setDialog(null); onDone(t('approvedToast', { business: a.businessName })); }} /> : null}
    </Drawer>
  );
}

/** The current review (stage 2) or "not reviewed yet" (stage 1): who, what they recommend, when, the checks and note. */
function ReviewSection({ d }: { d: Detail }) {
  const t = useVerifyT();
  const { locale } = useLocale();
  const r = d.application.review;
  if (d.application.status !== 'pending') return null;
  return (
    <SheetSection title={t('reviewTitle')}>
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

function ApplicationBody({ d, canSettle: canDecide }: { d: Detail; canSettle: boolean }) {
  const t = useVerifyT();
  const { locale } = useLocale();
  const regionName = useRegionName();
  const age = useAge();
  const identity = useDecideIdentity();
  const registry = useDecideRegistry();
  const [error, setError] = useState<string>();
  const run = (p: Promise<unknown>) => { setError(undefined); p.catch(e => setError(e instanceof ApiError ? e.message : String(e))); };
  const a = d.application;
  const structureKey = `st_${a.structure}` as VerifyKey;
  const structure = !a.structure ? '—' : t(structureKey) === structureKey ? a.structure : t(structureKey);
  return (
    <>
      <Stages stage={a.status === 'pending' ? stageOf(a) : 'done'} />
      {error ? <p role="alert" className="nl-q-error">{error}</p> : null}
      <ReviewSection d={d} />
      <SheetSection title={t('secBusiness')}>
        <FactList rows={[
          [t('f_business'), a.businessName], [t('f_legal'), a.legalName],
          [t('f_type'), a.type ? t(`t_${a.type}` as VerifyKey) : '—'], [t('f_structure'), structure],
          [t('f_categories'), a.categories.length ? a.categories.join(', ') : '—'],
          [t('f_place'), [a.city, a.province ? regionName(a.province) : null].filter(Boolean).join(', ') || '—'],
          [t('f_submitted'), a.submittedAt ? `${formatDate(a.submittedAt, locale, 'date')} · ${t('submittedAgo', { age: age(a.submittedAt) })}` : '—'],
          [t('f_risk'), riskText(a, t)],
          [t('f_decision'), statusText(a, t)],
        ]} />
      </SheetSection>
      <SheetSection title={t('checksTitle')}>
        <ul className="nl-ap-list">
          {a.checks.map(c => (
            <li key={c.id} className="nl-ap-row"><span>{checkLabel(c, t)}{c.reference ? ` · ${c.reference}` : ''}</span>
              <Tag tone={c.state === 'passed' ? 'success' : c.state === 'waiting' ? 'neutral' : 'warning'}>{t(`s_${c.state}` as VerifyKey)}</Tag></li>
          ))}
        </ul>
      </SheetSection>
      <SheetSection title={t('ownersTitle')}>
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
                    <Button variant="ghost" onClick={() => run(identity.mutateAsync({ id: a.merchantId, checkId: o.checkId!, decision: 'reject' }))}>{t('reject')}</Button>
                  </span>
                : <Tag tone={o.status === 'verified' ? 'success' : o.status === 'review' ? 'warning' : 'neutral'}>{t(`o_${o.status}` as VerifyKey)}</Tag>}
            </li>
          ))}
        </ul>
      </SheetSection>
      <SheetSection title={t('registryTitle')}>
        {d.registryReviews.length === 0 ? <p className="bo-muted">{t('noReviews')}</p> : (
          <ul className="nl-ap-list">
            {d.registryReviews.map(r => (
              <li key={r.id} className="nl-ap-row">
                <span>{t('registryLine', { registry: r.registry ?? r.source, number: r.queryNumber, outcome: r.outcome.replace(/_/g, ' ') })}
                  {r.recordName ? <span className="nl-ap-note">{r.recordName}{r.recordStatus ? ` · ${r.recordStatus}` : ''}</span> : null}</span>
                {r.reviewState === 'open' && canDecide
                  ? <span className="nl-q-actions">
                      <Button variant="secondary" onClick={() => run(registry.mutateAsync({ reviewId: r.id, decision: 'approve' }))}>{t('approve')}</Button>
                      <Button variant="ghost" onClick={() => run(registry.mutateAsync({ reviewId: r.id, decision: 'reject' }))}>{t('reject')}</Button>
                    </span>
                  : <Tag tone={r.reviewState === 'approved' ? 'success' : r.reviewState === 'open' ? 'warning' : 'neutral'}>{t(`r_${r.reviewState ?? 'open'}` as VerifyKey)}</Tag>}
              </li>
            ))}
          </ul>
        )}
      </SheetSection>
      {d.decisions.length || d.reviews.length ? (
        <SheetSection title={t('historyTitle')}>
          <ul className="nl-ap-list">
            {[
              ...d.decisions.map(h => ({ at: h.decidedAt, id: h.id, text: t(h.decision === 'approved' ? 'h_approved' : 'h_info_requested', { date: formatDate(h.decidedAt, locale, 'date') }), keys: h.checkKeys, note: h.note })),
              ...d.reviews.map(r => ({ at: r.reviewedAt, id: r.id, text: t('h_reviewed', { name: r.reviewerName, recommendation: t(REC_KEY[r.recommendation]), date: formatDate(r.reviewedAt, locale, 'date') }), keys: r.checkKeys, note: r.note })),
            ].sort((x, y) => y.at.localeCompare(x.at)).map(h => (
              <li key={h.id} className="nl-ap-row"><span>{h.text}
                {h.keys.length ? ` · ${h.keys.map(k => checkLabel({ key: k, registry: null }, t)).join(', ')}` : ''}{h.note ? ` · ${h.note}` : ''}</span></li>
            ))}
          </ul>
        </SheetSection>
      ) : null}
    </>
  );
}

/** "Request info": the checks to redo (not passed ones preselected) and the note emailed to the owners. */
function RequestInfoDialog({ app, keys: reviewed, note: reviewNote, onClose, onSent }: {
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
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button><Button disabled={decide.isPending} onClick={send}>{t('infoSend')}</Button></>}>
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
function ReviewDialog({ app, onClose, onSent }: { app: Application; onClose: () => void; onSent: () => void }) {
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
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button><Button disabled={review.isPending} onClick={send}>{t('reviewSend')}</Button></>}>
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
function OverrideDialog({ app, onClose, onSent }: { app: Application; onClose: () => void; onSent: () => void }) {
  const t = useVerifyT();
  const decide = useDecideApplication();
  const [note, setNote] = useState('');
  const fieldErrors = decide.error instanceof ValidationError ? decide.error.byField() : {};
  const other = decide.error && !(decide.error instanceof ValidationError) ? (decide.error as Error).message : undefined;
  const rec = app.review?.recommendation ?? 'reject';
  return (
    <Dialog open onClose={onClose} title={t('overrideTitle', { business: app.businessName })}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={decide.isPending} onClick={() => decide.mutate({ id: app.merchantId, decision: 'approve', note }, { onSuccess: onSent })}>{t('approve')}</Button></>}>
      <p className="nl-q-note">{t('overrideLede', { name: app.review?.reviewerName ?? '', recommendation: t(REC_KEY[rec]) })}</p>
      <Field label={t('overrideNote')} hint={t('overrideNoteHint')} error={fieldErrors.note}>
        <TextArea value={note} maxLength={500} onChange={e => setNote(e.target.value)} />
      </Field>
      {other ? <p role="alert" className="nl-q-error">{other}</p> : null}
    </Dialog>
  );
}
