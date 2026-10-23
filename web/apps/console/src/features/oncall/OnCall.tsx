import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { BoHeader, Button, ChartCard, Dialog, ErrorState, Field, PageSkeleton, Select, Tag, TextInput, useFormatters } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { meQuery } from '../shell/api';
import { useGrant } from '../shell/grant';
import { rotaQuery, useAddShift, useHandOver, useRemoveShift, type Rota, type Shift } from './api';
import { useOncallT, type OncallT } from './messages';
import '../team/team.css';

const PATHS = [1, 2, 3, 4, 5] as const;
const errorOf = (e: unknown) => (e instanceof ValidationError ? Object.values(e.byField())[0] : e instanceof ApiError ? e.message : undefined);

/**
 * On-call & escalations (S-96, design 03 `oncall`; every staff member): the rota from now to a week ahead with who is on
 * call, handing one's own shift to a colleague (admins: any shift), admins adding and removing shifts, and the
 * escalation paths. Every change is audit-logged.
 */
export function OnCall() {
  const t = useOncallT();
  const query = useQuery(rotaQuery);
  if (query.isPending) return <div className="bo-page"><BoHeader title={t('heading')} /><PageSkeleton kpis={0} rows={5} /></div>;
  if (query.isError) return <div className="bo-page"><BoHeader title={t('heading')} /><ErrorState message={t('loadError')} onRetry={() => void query.refetch()} /></div>;
  return <RotaView rota={query.data} />;
}

function RotaView({ rota }: { rota: Rota }) {
  const t = useOncallT();
  const fmt = useFormatters();
  const me = useQuery(meQuery).data?.userId;
  const { can } = useGrant();
  const admin = can('province');
  const remove = useRemoveShift();
  const [swapping, setSwapping] = useState<Shift | null>(null);
  const [adding, setAdding] = useState(false);
  const mine = rota.shifts.filter(s => s.userId === me || admin);
  const onNow = new Set(rota.now.map(s => s.id));
  return (
    <div className="bo-page">
      <BoHeader title={t('heading')} sub={t('summary')} actions={<>
        <Button variant="secondary" disabled={!mine.length} onClick={() => setSwapping(mine[0] ?? null)}>{t('swap')}</Button>
        {admin ? <Button onClick={() => setAdding(true)}>{t('addShift')}</Button> : null}</>} />
      <div className="nl-cols-wrap"><div className="nl-cols">
        <ChartCard id="oc-rota" title={t('rotaTitle')}>
          {rota.shifts.length ? (
            <ul className="nl-rows">
              {rota.shifts.map(s => (
                <li key={s.id}>
                  <span className="nl-row-main">
                    <span><strong>{s.userId === me ? t('you', { name: s.name }) : s.name}</strong> · {s.duty}</span>
                    <span className="nl-row-sub">{t('when', { from: fmt.date(s.startsAt, 'dateTime'), to: fmt.date(s.endsAt, 'dateTime') })}</span>
                  </span>
                  <span className="nl-row-actions">
                    {onNow.has(s.id) ? <Tag tone="success">{t('onCall')}</Tag> : null}
                    {admin ? <Button variant="tertiary" disabled={remove.isPending} onClick={() => remove.mutate(s.id)}>{t('remove')}</Button> : null}
                  </span>
                </li>
              ))}
            </ul>
          ) : <p className="bo-muted">{t('noShifts')}</p>}
          {errorOf(remove.error) ? <p role="alert" className="nl-tm-error">{errorOf(remove.error)}</p> : null}
        </ChartCard>
        <ChartCard id="oc-paths" title={t('escalationTitle')}>
          <ul className="nl-rows">
            {PATHS.map(n => <li key={n}><span className="nl-row-main"><strong>{t(`e${n}` as Parameters<OncallT>[0])}</strong><span className="nl-row-sub">{t(`p${n}` as Parameters<OncallT>[0])}</span></span></li>)}
          </ul>
          <p className="bo-muted">{t('targets')}</p>
        </ChartCard>
      </div></div>
      {swapping ? <SwapDialog rota={rota} shifts={mine} first={swapping} onClose={() => setSwapping(null)} /> : null}
      {adding ? <AddDialog rota={rota} onClose={() => setAdding(false)} /> : null}
    </div>
  );
}

function SwapDialog({ rota, shifts, first, onClose }: { rota: Rota; shifts: Shift[]; first: Shift; onClose: () => void }) {
  const t = useOncallT();
  const fmt = useFormatters();
  const hand = useHandOver();
  const [shiftId, setShiftId] = useState(first.id);
  const shift = shifts.find(s => s.id === shiftId) ?? first;
  const others = rota.staff.filter(m => m.id !== shift.userId);
  const [to, setTo] = useState(others[0]?.id ?? '');
  return (
    <Dialog open onClose={onClose} title={t('swapTitle', { duty: shift.duty })}
      actions={<><Button variant="tertiary" onClick={onClose}>{t('cancel')}</Button><Button disabled={hand.isPending || !to} onClick={() => hand.mutate({ id: shift.id, userId: to }, { onSuccess: onClose })}>{t('handOver')}</Button></>}>
      <Field label={t('f_shift')}>
        <Select value={shiftId} onChange={e => setShiftId(e.target.value)}
          options={shifts.map(s => ({ value: s.id, label: `${s.name} · ${fmt.date(s.startsAt, 'dateTime')} · ${s.duty}` }))} />
      </Field>
      <Field label={t('f_to')}><Select value={to} onChange={e => setTo(e.target.value)} options={others.map(m => ({ value: m.id, label: m.name }))} /></Field>
      {errorOf(hand.error) ? <p role="alert" className="nl-tm-error">{errorOf(hand.error)}</p> : null}
    </Dialog>
  );
}

function AddDialog({ rota, onClose }: { rota: Rota; onClose: () => void }) {
  const t = useOncallT();
  const add = useAddShift();
  const [userId, setUserId] = useState(rota.staff[0]?.id ?? '');
  const [starts, setStarts] = useState('');
  const [ends, setEnds] = useState('');
  const [duty, setDuty] = useState('');
  const errors = add.error instanceof ValidationError ? add.error.byField() : {};
  const iso = (local: string) => (local ? new Date(local).toISOString() : '');
  return (
    <Dialog open onClose={onClose} title={t('addTitle')}
      actions={<><Button variant="tertiary" onClick={onClose}>{t('cancel')}</Button><Button disabled={add.isPending} onClick={() => add.mutate({ userId, startsAt: iso(starts), endsAt: iso(ends), duty }, { onSuccess: onClose })}>{t('add')}</Button></>}>
      <Field label={t('f_who')} error={errors.userId}><Select value={userId} onChange={e => setUserId(e.target.value)} options={rota.staff.map(m => ({ value: m.id, label: m.name }))} /></Field>
      <Field label={t('f_starts')} error={errors.startsAt}><TextInput type="datetime-local" value={starts} onChange={e => setStarts(e.target.value)} /></Field>
      <Field label={t('f_ends')} error={errors.endsAt}><TextInput type="datetime-local" value={ends} onChange={e => setEnds(e.target.value)} /></Field>
      <Field label={t('f_duty')} error={errors.duty}><TextInput value={duty} maxLength={120} onChange={e => setDuty(e.target.value)} /></Field>
      {add.error && !(add.error instanceof ValidationError) ? <p role="alert" className="nl-tm-error">{errorOf(add.error)}</p> : null}
    </Dialog>
  );
}
