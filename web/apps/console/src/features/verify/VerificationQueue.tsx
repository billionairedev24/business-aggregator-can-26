import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { CheckCircle, Question } from '@phosphor-icons/react';
import {
  Button, Checkbox, DataTable, Dialog, Drawer, ErrorState, FactList, Field, formatDate, formatNumber, KpiStrip, PageSkeleton, SheetSection, Tag, TextArea, useLocale,
  type DataTableAction, type DataTableColumn, type DataTableTone,
} from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useAge } from '../shell/age';
import { useGrant } from '../shell/grant';
import { useRegionName, type PlaceFilter } from '../shell/PlaceFilters';
import { SCREEN_PATH } from '../shell/screens';
import { detailQuery, queueQuery, useDecideApplication, useDecideIdentity, useDecideRegistry, type Application, type Check, type Detail } from './api';
import { ApplicationsHead } from './ApplicationsHead';
import { useVerifyT, type VerifyKey, type VerifyT } from './messages';
import '../shell/queues.css';
import './applications.css';

export interface VerifySearch extends PlaceFilter { application?: string }

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
 * The two status stages of an application (redesign § 2.6 "two-person review"; one reviewer here, DECISIONS "Redesign
 * phase 3"): Review while an automated check waits for an agent (an identity mismatch, a registry lookup), Decision
 * once nothing does. The api records one decision (approve / request info), by one person.
 */
export const stageOf = (a: Pick<Application, 'checks'>): Stage => (a.checks.some(c => c.state === 'review') ? 'review' : 'decision');

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
  const t = useVerifyT();
  const { locale } = useLocale();
  const search = useSearch({ strict: false }) as VerifySearch;
  const filter = { province: search.province, market: search.market };
  const navigate = useNavigate();
  const query = useQuery(queueQuery(filter));
  const { can, roleName } = useGrant();
  const regionName = useRegionName();
  const age = useAge();
  const decide = useDecideApplication();
  const [asking, setAsking] = useState<Application | null>(null);
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
  const open = [t('stage_review'), t('stage_decision')];
  const actions: DataTableAction<Row>[] = [
    { id: 'approve', label: t('approve'), icon: CheckCircle, perm: 'update', inline: true, bulk: false, when: { key: 'stage', in: open } },
    { id: 'info', label: t('requestInfo'), icon: Question, perm: 'update', inline: true, bulk: false, when: { key: 'stage', in: open } },
  ];
  const approve = async (app: Application) => {
    setNotice(null);
    try {
      await decide.mutateAsync({ id: app.merchantId, decision: 'approve' });
      setNotice({ tone: 'ok', text: t('approvedToast', { business: app.businessName }) });
    } catch (e) {
      setNotice({ tone: 'error', text: e instanceof ApiError ? e.message : String(e) });
    }
  };
  const onAction = async (a: DataTableAction<Row>, hit: Row[]) => {
    const app = hit[0]!.app;
    if (a.id === 'info') setAsking(app);
    else await approve(app);
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
        can={{ create: false, update: can('verify'), delete: false }} roleName={roleName} actions={actions} onAction={onAction}
        emptyText={t('empty')} openLabel={t('open')} onOpen={r => select(r.id)} />
      {search.application ? <ApplicationSheet id={search.application} canDecide={can('verify')} roleName={roleName} onClose={() => select(undefined)}
        onAsk={setAsking} onApprove={approve} /> : null}
      {asking ? <RequestInfoDialog app={asking} onClose={() => setAsking(null)} onSent={() => { setNotice({ tone: 'ok', text: t('infoToast', { business: asking.businessName }) }); setAsking(null); }} /> : null}
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

/** One application in the right sheet (BO:811): the stages, every submitted field, checks, owners, registry, history. */
function ApplicationSheet({ id, canDecide, roleName, onClose, onAsk, onApprove }: {
  id: string; canDecide: boolean; roleName: string; onClose: () => void; onAsk: (a: Application) => void; onApprove: (a: Application) => Promise<void>;
}) {
  const t = useVerifyT();
  const decide = useDecideApplication();
  const query = useQuery(detailQuery(id));
  const a = query.data?.application;
  const footer = a?.status === 'pending' ? (
    canDecide ? <div className="nl-q-actions">
      <Button disabled={decide.isPending} onClick={() => void onApprove(a)}>{t('approve')}</Button>
      <Button variant="secondary" onClick={() => onAsk(a)}>{t('requestInfo')}</Button>
    </div> : <span className="nl-q-note">{t('viewOnly', { role: roleName })}</span>
  ) : undefined;
  return (
    <Drawer open onClose={onClose} title={a ? a.businessName : t('entity')} footer={footer}>
      {query.isPending ? <div aria-busy="true" className="nl-q-note">…</div>
        : query.isError ? <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />
          : <ApplicationBody d={query.data} canDecide={canDecide} />}
    </Drawer>
  );
}

function ApplicationBody({ d, canDecide }: { d: Detail; canDecide: boolean }) {
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
      {d.decisions.length ? (
        <SheetSection title={t('historyTitle')}>
          <ul className="nl-ap-list">
            {d.decisions.map(h => (
              <li key={h.id} className="nl-ap-row"><span>{t(h.decision === 'approved' ? 'h_approved' : 'h_info_requested', { date: formatDate(h.decidedAt, locale, 'date') })}
                {h.checkKeys.length ? ` · ${h.checkKeys.map(k => checkLabel({ key: k, registry: null }, t)).join(', ')}` : ''}{h.note ? ` · ${h.note}` : ''}</span></li>
            ))}
          </ul>
        </SheetSection>
      ) : null}
    </>
  );
}

/** "Request info": the checks to redo (not passed ones preselected) and the note emailed to the owners. */
function RequestInfoDialog({ app, onClose, onSent }: { app: Application; onClose: () => void; onSent: () => void }) {
  const t = useVerifyT();
  const decide = useDecideApplication();
  const [keys, setKeys] = useState<string[]>(() => app.checks.filter(c => c.state !== 'passed').map(c => c.key));
  const [note, setNote] = useState('');
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
