import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Checkbox, Dialog, EmptyState, ErrorState, Field, PageSkeleton, Select, Tag, TextArea, TextInput, useFormatters } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { OUTCOMES, PERSONAS, participantsQuery, queueQuery, scriptsQuery, useAddParticipant, useParticipantAct, type Participant, type Persona } from './api';
import { useUatT, type UatKey, type UatT } from './messages';
import '../shell/queues.css';

const BUSINESS: readonly Persona[] = ['provider', 'seller', 'kitchen'];

/**
 * Pilot participants and their sign-offs (S-121): per participant, the persona's UAT script and where its latest
 * sign-off stands (signed off, with comments, blocked with the blocking items, or pending). Staff with the `uat` grant
 * add participants (a person by email or mobile, a business by id), record sign-offs from the form and stop someone
 * taking part.
 */
export function Participants() {
  const t = useUatT();
  const { date } = useFormatters();
  const list = useQuery(participantsQuery);
  const { can } = useGrant();
  const act = useParticipantAct();
  const [adding, setAdding] = useState(false);
  const [signing, setSigning] = useState<Participant>();

  if (list.isPending) return <PageSkeleton kpis={0} rows={6} />;
  if (list.isError) return <ErrorState message={t('loadError')} onRetry={() => void list.refetch()} />;
  const people = list.data;
  const signed = people.filter(p => p.active && p.signoffs.every(s => s.outcome === 'signed_off' || s.outcome === 'with_comments')).length;
  return (
    <div>
      <span className="nl-q-kicker">{t('pKicker')}</span>
      <h1 className="nl-q-title">{t('pTitle', { n: people.filter(p => p.active).length, signed })}</h1>
      <p className="nl-q-lede">{t('pLede')}</p>
      {can('uat') ? <div className="nl-q-actions"><Button type="button" variant="secondary" onClick={() => setAdding(true)}>{t('add')}</Button></div> : null}
      {people.length === 0 ? <EmptyState>{t('pEmpty')}</EmptyState> : (
        <ul className="nl-q-list nl-q-gap" aria-label={t('tabParticipants')}>
          {people.map(p => (
            <li key={p.id} className="nl-q-panel">
              <div className="nl-q-row">
                <div>
                  <strong>{p.label}</strong> · {t(`p_${p.persona}`)} · {p.who === 'business' ? t('business') : t('person')}
                  <div className="nl-q-note">{t('since', { date: date(p.since, 'full') })} · {t('feedbackCount', { n: p.feedbackCount })}</div>
                </div>
                {p.active ? null : <Tag tone="neutral">{t('inactive')}</Tag>}
              </div>
              {p.signoffs.map(s => (
                <div key={s.script} className="nl-q-row">
                  <div>
                    <Tag tone={s.outcome === 'blocked' ? 'accent-2' : s.outcome === 'pending' ? 'highlight' : 'accent'}>{t(`o_${s.outcome}`)}</Tag>{' '}
                    {t('signoffLine', { script: s.scriptTitle, version: s.scriptVersion, outcome: t(`o_${s.outcome}`) })}
                    {s.recordedAt ? <div className="nl-q-note">{t('signoffBy', { who: s.recordedByName ?? '—', date: date(s.recordedAt, 'dateTime') })}</div> : null}
                    {s.blockingRefs.length ? <div className="nl-q-note">{t('blockingRefs', { refs: s.blockingRefs.join(', ') })}</div> : null}
                    {s.comments ? <div className="nl-q-note">{s.comments}</div> : null}
                  </div>
                </div>
              ))}
              {can('uat') && p.active ? <div className="nl-q-actions">
                <Button type="button" variant="secondary" onClick={() => setSigning(p)}>{t('record')}</Button>
                <Button type="button" variant="ghost" disabled={act.isPending} onClick={() => act.mutate({ id: p.id, deactivate: true })}>{t('deactivate')}</Button>
              </div> : null}
            </li>
          ))}
        </ul>
      )}
      {adding ? <AddDialog t={t} onClose={() => setAdding(false)} /> : null}
      {signing ? <SignoffDialog t={t} p={signing} onClose={() => setSigning(undefined)} /> : null}
    </div>
  );
}

function AddDialog({ t, onClose }: { t: UatT; onClose: () => void }) {
  const add = useAddParticipant();
  const [persona, setPersona] = useState<Persona>('customer');
  const [label, setLabel] = useState('');
  const [who, setWho] = useState('');
  const errors = add.error instanceof ValidationError ? add.error.byField() : {};
  const business = BUSINESS.includes(persona);
  return (
    <Dialog open onClose={onClose} title={t('add')}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={add.isPending} onClick={() => add.mutate(
          { persona, label: label.trim(), ...(business ? { merchantId: who.trim() } : { contact: who.trim() }) },
          { onSuccess: onClose })}>{t('save')}</Button></>}>
      <Field label={t('persona')} error={errors.persona}>
        <Select value={persona} onChange={e => setPersona(e.target.value as Persona)} options={PERSONAS.map(p => ({ value: p, label: t(`p_${p}`) }))} />
      </Field>
      <Field label={t('label')} error={errors.label}><TextInput value={label} maxLength={80} onChange={e => setLabel(e.target.value)} /></Field>
      <Field label={business ? t('merchantId') : t('contact')} hint={business ? t('merchantHint') : t('contactHint')} error={business ? errors.merchantId : errors.contact}>
        <TextInput value={who} onChange={e => setWho(e.target.value)} />
      </Field>
      {add.error && !(add.error instanceof ValidationError) ? <p role="alert" className="nl-q-error">{add.error.message}</p> : null}
    </Dialog>
  );
}

function SignoffDialog({ t, p, onClose }: { t: UatT; p: Participant; onClose: () => void }) {
  const act = useParticipantAct();
  const scripts = useQuery(scriptsQuery).data?.filter(s => s.persona === p.persona) ?? [];
  const open = useQuery(queueQuery({ state: 'open' })).data ?? [];
  const [outcome, setOutcome] = useState<(typeof OUTCOMES)[number]>('signed_off');
  const [comments, setComments] = useState('');
  const [blocking, setBlocking] = useState<string[]>([]);
  const script = scripts[0];
  const errors = act.error instanceof ValidationError ? act.error.byField() : {};
  return (
    <Dialog open onClose={onClose} title={`${t('record')} · ${p.label}`}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={act.isPending || !script} onClick={() => script && act.mutate(
          { id: p.id, script: script.code, outcome, comments: comments.trim() || undefined, blockingIds: outcome === 'blocked' ? blocking : [] },
          { onSuccess: onClose })}>{t('save')}</Button></>}>
      {script ? <p className="nl-q-note">{t('script')}: {script.title} v{script.version} · {t('printForm', { path: script.formPath })}</p> : null}
      <Field label={t('outcome')} error={errors.outcome}>
        <Select value={outcome} onChange={e => setOutcome(e.target.value as typeof outcome)} options={OUTCOMES.map(o => ({ value: o, label: t(`o_${o}` as UatKey) }))} />
      </Field>
      {outcome === 'blocked' ? <fieldset className="nl-q-field">
        <legend>{t('blockingItems')}</legend>
        {open.length === 0 ? <p className="nl-q-note">{t('noOpenItems')}</p> : open.map(i => (
          <Checkbox key={i.id} checked={blocking.includes(i.id)} label={`${i.reference} · ${i.summary}`}
            onChange={on => setBlocking(b => (on ? [...b, i.id] : b.filter(x => x !== i.id)))} />
        ))}
        {errors.blockingIds ? <div role="alert" className="nl-error">{errors.blockingIds}</div> : null}
      </fieldset> : null}
      <Field label={t('comments')} error={errors.comments}><TextArea rows={3} value={comments} maxLength={2000} onChange={e => setComments(e.target.value)} /></Field>
      {act.error && !(act.error instanceof ValidationError) ? <p role="alert" className="nl-q-error">{act.error.message}</p> : null}
    </Dialog>
  );
}
