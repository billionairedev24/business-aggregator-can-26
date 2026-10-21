import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Checkbox, EmptyState, ErrorState, PageHeader, PageSkeleton, useFormatters } from '@northline/ui';
import { useMerchantId, useRole } from '../shell/api';
import { appealFileUrl, standingQuery, useTickTask, type Appeal, type Coaching, type Consequence, type DisputeToAppeal } from './api';
import { AppealDialog } from './AppealDialog';
import { useStandingT, type StandingKey } from './messages';
import './standing.css';

const TICKS = new Set(['owner', 'technician', 'cook']);
type Subject = { type: 'dispute' | 'consequence'; id: string; what: string };

/**
 * /b/$merchantId/standing (S-156): what limits the business now — suspension, hidden from search, instant book paused,
 * a payout released later — with the reason, who decided and until when it can be appealed; dispute decisions an
 * agent took against it that it may still appeal; the coaching plan; its appeals and their outcome; history.
 */
export function StandingScreen() {
  const t = useStandingT();
  const merchantId = useMerchantId();
  const role = useRole();
  const q = useQuery(standingQuery(merchantId));
  const [appealing, setAppealing] = useState<Subject>();
  const [sent, setSent] = useState<string>();

  if (q.isPending) return <PageSkeleton kpis={0} rows={4} />;
  if (q.isError) return <><PageHeader kicker={t('kicker')} title={t('title')} /><ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /></>;
  const s = q.data;
  const owner = role === 'owner';
  const nothing = !s.active.length && !s.appealableDisputes.length && !s.coaching;

  return (
    <>
      <PageHeader kicker={t('kicker')} title={t('title')} />
      <p className="nl-st-lede">{t('lede')}</p>
      {sent ? <Alert tone="info" role="status">{sent}</Alert> : null}
      {nothing ? <EmptyState>{t('allClear')}</EmptyState> : null}
      {s.active.length ? (
        <section className="nl-st-section" aria-labelledby="st-in-force">
          <h2 id="st-in-force" className="nl-st-h2">{t('inForce')}</h2>
          <ul className="nl-st-list">
            {s.active.map(c => <ConsequenceCard key={c.id} c={c} owner={owner} onAppeal={() => setAppealing({ type: 'consequence', id: c.id, what: t(`kind_${c.kind}` as StandingKey) })} />)}
          </ul>
        </section>
      ) : null}
      {s.appealableDisputes.length ? (
        <section className="nl-st-section" aria-labelledby="st-disputes">
          <h2 id="st-disputes" className="nl-st-h2">{t('disputes')}</h2>
          <ul className="nl-st-list">
            {s.appealableDisputes.map(d => <DisputeCard key={d.disputeId} d={d} owner={owner} onAppeal={() => setAppealing({ type: 'dispute', id: d.disputeId, what: t('disputeWhat', { case: d.caseNumber }) })} />)}
          </ul>
        </section>
      ) : null}
      {s.coaching ? <CoachingPanel plan={s.coaching} merchantId={merchantId} canTick={TICKS.has(role)} /> : null}
      {s.appeals.length ? (
        <section className="nl-st-section" aria-labelledby="st-appeals">
          <h2 id="st-appeals" className="nl-st-h2">{t('appeals')}</h2>
          <ul className="nl-st-list">{s.appeals.map(a => <AppealCard key={a.id} a={a} merchantId={merchantId} />)}</ul>
        </section>
      ) : null}
      {s.past.length || s.pastCoaching.length ? (
        <section className="nl-st-section" aria-labelledby="st-history">
          <h2 id="st-history" className="nl-st-h2">{t('history')}</h2>
          <ul className="nl-st-list">
            {s.past.map(c => <PastCard key={c.id} c={c} />)}
            {s.pastCoaching.map(p => <PastCoaching key={p.id} plan={p} />)}
          </ul>
        </section>
      ) : null}
      {appealing ? (
        <AppealDialog merchantId={merchantId} subjectType={appealing.type} subjectId={appealing.id} what={appealing.what}
          onClose={() => setAppealing(undefined)}
          onSent={a => { setAppealing(undefined); setSent(t('sent', { number: a.number })); }} />
      ) : null}
    </>
  );
}

function ConsequenceCard({ c, owner, onAppeal }: { c: Consequence; owner: boolean; onAppeal: () => void }) {
  const t = useStandingT();
  const { date } = useFormatters();
  const by = c.cause === 'staff' ? t('by_staff') : t('by_rules');
  return (
    <li className="nl-st-card">
      <div className="nl-st-head">
        <h3 className="nl-st-title">{t(`kind_${c.kind}` as StandingKey)}</h3>
        <span className="tag tag-warning">{by}</span>
      </div>
      <p className="nl-st-meta">{t('since', { date: date(c.startedAt) })}{c.endsAt ? ` · ${t('until', { date: date(c.endsAt, 'dateTime') })}` : ''}</p>
      <p className="nl-st-text">{t(`what_${c.kind}` as StandingKey)}</p>
      <p className="nl-st-reason">{t('reason', { reason: c.reason })}</p>
      {c.appeal ? <p className="nl-st-meta">{t('appealOf', { number: c.appeal.number, what: t(`state_${c.appeal.state}` as StandingKey) })}</p>
        : c.appealBy ? (
          <div className="nl-st-actions">
            <span className="nl-st-meta">{c.appealable ? t('appealBy', { date: date(c.appealBy) }) : t('appealClosed')}</span>
            {c.appealable ? (owner ? <Button variant="secondary" onClick={onAppeal}>{t('appeal')}</Button> : <span className="nl-st-meta">{t('ownersAppeal')}</span>) : null}
          </div>
        ) : null}
    </li>
  );
}

function DisputeCard({ d, owner, onAppeal }: { d: DisputeToAppeal; owner: boolean; onAppeal: () => void }) {
  const t = useStandingT();
  const { date, money } = useFormatters();
  return (
    <li className="nl-st-card">
      <h3 className="nl-st-title">{t('disputeLine', { case: d.caseNumber, subject: d.subject })}</h3>
      <p className="nl-st-text">{t(`disputeDecision_${d.decision === 'partial' ? 'partial' : 'full_refund'}` as StandingKey, { amount: money(d.refundCents) })}</p>
      {d.note ? <p className="nl-st-reason">{t('disputeNote', { note: d.note })}</p> : null}
      <div className="nl-st-actions">
        <span className="nl-st-meta">{t('appealBy', { date: date(d.appealBy) })}</span>
        {owner ? <Button variant="secondary" onClick={onAppeal}>{t('appeal')}</Button> : <span className="nl-st-meta">{t('ownersAppeal')}</span>}
      </div>
    </li>
  );
}

function CoachingPanel({ plan, merchantId, canTick }: { plan: Coaching; merchantId: string; canTick: boolean }) {
  const t = useStandingT();
  const { date } = useFormatters();
  const tick = useTickTask(merchantId);
  const done = plan.tasks.filter(x => x.done).length;
  return (
    <section className="nl-st-section" aria-labelledby="st-coaching">
      <h2 id="st-coaching" className="nl-st-h2">{t('coaching')}</h2>
      <div className="nl-st-card">
        <div className="nl-st-head">
          <h3 className="nl-st-title">{plan.title}</h3>
          <span className="tag tag-neutral">{t('tasksDone', { done, total: plan.tasks.length })}</span>
        </div>
        <p className="nl-st-meta">{t('coachingFrom')}{plan.dueOn ? ` · ${t('dueOn', { date: date(`${plan.dueOn}T12:00:00Z`) })}` : ''}</p>
        {plan.note ? <p className="nl-st-text">{plan.note}</p> : null}
        <ul className="nl-st-tasks">
          {plan.tasks.map(x => (
            <li key={x.id}>
              <Checkbox checked={x.done} label={x.title} disabled={!canTick || tick.isPending}
                onChange={v => tick.mutate({ planId: plan.id, taskId: x.id, done: v })} />
            </li>
          ))}
        </ul>
        {tick.isError ? <Alert tone="error">{t('actionError')}</Alert> : null}
      </div>
    </section>
  );
}

function AppealCard({ a, merchantId }: { a: Appeal; merchantId: string }) {
  const t = useStandingT();
  const { date, money } = useFormatters();
  const what = a.subjectType === 'dispute' ? t('disputeWhat', { case: a.subjectLabel ?? a.subjectId }) : t(`kind_${a.subjectKind}` as StandingKey);
  const tone = a.state === 'granted' ? 'tag-success' : a.state === 'denied' ? 'tag-neutral' : 'tag-highlight';
  return (
    <li className="nl-st-card">
      <div className="nl-st-head">
        <h3 className="nl-st-title">{t('appealOf', { number: a.number, what })}</h3>
        <span className={`tag ${tone}`}>{t(`state_${a.state}` as StandingKey)}</span>
      </div>
      <p className="nl-st-meta">{t('filedOn', { date: date(a.filedAt) })}{a.decidedAt ? ` · ${t('decidedOn', { date: date(a.decidedAt) })}` : ''}</p>
      <p className="nl-st-text">{a.statement}</p>
      {a.files.length ? (
        <ul className="nl-st-files">
          {a.files.map(f => <li key={f.id} className="nl-st-file"><a href={appealFileUrl(merchantId, a.id, f.id)} target="_blank" rel="noreferrer">{f.name}</a></li>)}
        </ul>
      ) : null}
      {a.decisionReason ? <p className="nl-st-reason">{t('reason', { reason: a.decisionReason })}</p> : null}
      {a.compensationCents > 0 ? <p className="nl-st-text">{t('compensation', { amount: money(a.compensationCents) })}</p> : null}
    </li>
  );
}

function PastCard({ c }: { c: Consequence }) {
  const t = useStandingT();
  const { date } = useFormatters();
  return (
    <li className="nl-st-card">
      <h3 className="nl-st-title">{t(`kind_${c.kind}` as StandingKey)}</h3>
      <p className="nl-st-meta">{t('since', { date: date(c.startedAt) })}{c.liftedAt ? ` · ${t('lifted', { date: date(c.liftedAt) })} ${c.liftedByStaff ? t('liftedByStaff') : t('liftedByRules')}` : ''}</p>
      <p className="nl-st-reason">{t('reason', { reason: c.reason })}</p>
      {c.liftReason ? <p className="nl-st-meta">{c.liftReason}</p> : null}
    </li>
  );
}

function PastCoaching({ plan }: { plan: Coaching }) {
  const t = useStandingT();
  const { date } = useFormatters();
  return (
    <li className="nl-st-card">
      <h3 className="nl-st-title">{plan.title}</h3>
      {plan.closedAt ? <p className="nl-st-meta">{t(plan.state === 'completed' ? 'coachingClosed_completed' : 'coachingClosed_cancelled', { date: date(plan.closedAt) })}</p> : null}
    </li>
  );
}
