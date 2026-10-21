import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, ChartCard, Dialog, Field, formatDate, TextArea, TextInput, useLocale } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { sellerStandingQuery, useCloseCoaching, useLift, useStartCoaching, type StandingConsequence } from './standingApi';
import { useSellerStandingT, type SellerStandingKey } from './standingMessages';

/**
 * S-156: the business's standing on its seller detail — the consequences in force (lift one with a reason: role
 * `suspend`), its appeals and the coaching plan (start or close one: role `decide`).
 */
export function StandingCard({ businessId }: { businessId: string }) {
  const t = useSellerStandingT();
  const { locale } = useLocale();
  const { can } = useGrant();
  const q = useQuery(sellerStandingQuery(businessId));
  const close = useCloseCoaching(businessId);
  const [lifting, setLifting] = useState<StandingConsequence>();
  const [starting, setStarting] = useState(false);
  const s = q.data;
  const what = (c: StandingConsequence) => t(`k_${c.kind}` as SellerStandingKey);
  return (
    <ChartCard id="sl-standing" title={t('title')}>
      {!s ? null : (
        <>
          {s.active.length ? (
            <ul className="nl-rows">
              {s.active.map(c => (
                <li key={c.id}>
                  <span>{t('since', { what: what(c), by: c.cause === 'staff' ? t('by_staff') : t('by_rules'), date: formatDate(c.startedAt, locale) })}</span>
                  <span className="bo-muted"> — {c.reason}</span>
                  {c.appeal ? <span className="bo-muted"> · {t('appeal', { number: c.appeal.number, state: c.appeal.state })}</span> : null}
                  {can('suspend') ? <> <Button variant="ghost" onClick={() => setLifting(c)}>{t('lift')}</Button></> : null}
                </li>
              ))}
            </ul>
          ) : <p className="bo-muted">{t('none')}</p>}
          {s.appeals.length ? <p className="bo-muted">{t('appeals', { n: s.appeals.length })}</p> : null}
          <h3 className="nl-h3">{t('coaching')}</h3>
          {s.coaching ? (
            <>
              <p><strong>{s.coaching.title}</strong> · {t('tasksDone', { done: s.coaching.tasks.filter(x => x.done).length, total: s.coaching.tasks.length })}</p>
              {can('decide') ? (
                <div className="nl-q-actions">
                  <Button variant="secondary" disabled={close.isPending} onClick={() => close.mutate({ planId: s.coaching!.id, completed: true })}>{t('complete')}</Button>
                  <Button variant="ghost" disabled={close.isPending} onClick={() => close.mutate({ planId: s.coaching!.id, completed: false })}>{t('cancelPlan')}</Button>
                </div>
              ) : null}
            </>
          ) : (
            <>
              <p className="bo-muted">{t('noCoaching')}</p>
              {can('decide') ? <Button variant="secondary" onClick={() => setStarting(true)}>{t('start')}</Button> : null}
            </>
          )}
          {close.isError ? <p role="alert" className="nl-q-error">{close.error.message}</p> : null}
        </>
      )}
      {lifting ? <LiftDialog businessId={businessId} consequence={lifting} what={what(lifting)} onClose={() => setLifting(undefined)} /> : null}
      {starting ? <CoachingDialog businessId={businessId} onClose={() => setStarting(false)} /> : null}
    </ChartCard>
  );
}

function LiftDialog({ businessId, consequence, what, onClose }: { businessId: string; consequence: StandingConsequence; what: string; onClose: () => void }) {
  const t = useSellerStandingT();
  const lift = useLift(businessId);
  const [reason, setReason] = useState('');
  const errors = lift.error instanceof ValidationError ? lift.error.byField() : {};
  return (
    <Dialog open onClose={onClose} title={t('liftTitle', { what })}
      actions={<><Button variant="secondary" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={lift.isPending} onClick={() => lift.mutate({ id: consequence.id, reason }, { onSuccess: onClose })}>{t('liftCta')}</Button></>}>
      <Field label={t('liftReason')} error={errors.reason}><TextArea value={reason} maxLength={1000} onChange={e => setReason(e.target.value)} /></Field>
      {lift.isError && !(lift.error instanceof ValidationError) ? <p role="alert" className="nl-q-error">{lift.error.message}</p> : null}
    </Dialog>
  );
}

function CoachingDialog({ businessId, onClose }: { businessId: string; onClose: () => void }) {
  const t = useSellerStandingT();
  const start = useStartCoaching(businessId);
  const [title, setTitle] = useState('');
  const [note, setNote] = useState('');
  const [due, setDue] = useState('');
  const [tasks, setTasks] = useState('');
  const errors = start.error instanceof ValidationError ? start.error.byField() : {};
  const send = () => start.mutate({ title, note: note || undefined, dueOn: due || undefined, tasks: tasks.split('\n').map(x => x.trim()).filter(Boolean) }, { onSuccess: onClose });
  return (
    <Dialog open onClose={onClose} title={t('start')}
      actions={<><Button variant="secondary" onClick={onClose}>{t('cancel')}</Button><Button disabled={start.isPending} onClick={send}>{t('startCta')}</Button></>}>
      <Field label={t('planTitle')} error={errors.title}><TextInput value={title} maxLength={120} onChange={e => setTitle(e.target.value)} /></Field>
      <Field label={t('planNote')} error={errors.note}><TextArea value={note} maxLength={1000} onChange={e => setNote(e.target.value)} /></Field>
      <Field label={t('planDue')} error={errors.dueOn}><TextInput type="date" value={due} onChange={e => setDue(e.target.value)} /></Field>
      <Field label={t('planTasks')} error={errors.tasks}><TextArea value={tasks} rows={5} onChange={e => setTasks(e.target.value)} /></Field>
      {start.isError && !(start.error instanceof ValidationError) ? <p role="alert" className="nl-q-error">{start.error.message}</p> : null}
    </Dialog>
  );
}
