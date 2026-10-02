import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { Button, DataTable, Dialog, Drawer, ErrorState, Field, PageSkeleton, Segmented, Select, Tag, TextArea, TextInput, useFormatters, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { SCREEN_PATH } from '../shell/screens';
import { DECISIONS, detailQuery, EXTENSIONS, queueQuery, TYPES, useAct, useRecord, type Detail, type Item } from './api';
import { usePrivacyT, type PrivacyKey, type PrivacyT } from './messages';
import '../shell/queues.css';

interface Row { id: string; reference: string; type: string; person: string; law: string; received: string; due: string; state: string; item: Item }

const FIELDS = ['phone', 'receiptName', 'reviewName', 'legalName'] as const;

/**
 * Privacy requests (S-105): the queue of access, correction and erasure requests with each one's law and deadline
 * (overdue first), the detail with what was asked and the erasure's progress per module, and the actions of the
 * `privacy` grant (privacy officer, support lead, admin): verify the person, extend once, refuse, start an erasure now,
 * apply corrections, retry failed steps, record a request made by email or mail.
 */
export function PrivacyQueue() {
  const t = usePrivacyT();
  const { date } = useFormatters();
  const search = useSearch({ strict: false }) as { state?: 'open' | 'closed'; request?: string };
  const navigate = useNavigate();
  const state = search.state ?? 'open';
  const queue = useQuery(queueQuery(state));
  const { can, roleName } = useGrant();
  const [recording, setRecording] = useState(false);
  const go = (next: { state?: 'open' | 'closed'; request?: string }) =>
    void navigate({ to: SCREEN_PATH.privacy, search: { state: next.state ?? state, request: next.request } });

  if (queue.isPending) return <PageSkeleton kpis={0} rows={6} />;
  if (queue.isError) return <ErrorState message={t('loadError')} onRetry={() => void queue.refetch()} />;
  const rows: Row[] = queue.data.map(i => ({
    id: i.id, reference: i.reference, type: t(`t_${i.type}`), person: i.subjectName || t('noName'), law: i.law,
    received: date(i.receivedAt), due: i.overdue ? `${date(i.dueAt)} · ${t('overdue')}` : date(i.dueAt),
    state: t(`s_${i.state}` as PrivacyKey), item: i,
  }));
  const columns: DataTableColumn<Row>[] = [
    { key: 'reference', label: t('colRef'), sub: 'type', subLabel: t('colType'), primary: true },
    { key: 'person', label: t('colPerson') },
    { key: 'law', label: t('colLaw'), type: 'tag', editable: false },
    { key: 'received', label: t('colReceived') },
    { key: 'due', label: t('colDue') },
    { key: 'state', label: t('colState'), type: 'tag', editable: false },
  ];
  const tones = (r: Row): Partial<Record<keyof Row, DataTableTone>> => ({
    law: 'tag-neutral', state: r.item.overdue ? 'tag-accent-2' : r.item.state === 'awaiting_verification' ? 'tag-highlight' : 'tag-accent',
  });
  const overdue = queue.data.filter(i => i.overdue).length;
  return (
    <div>
      <div className="nl-q-top">
        <span className="nl-q-kicker">{t('kicker')}</span>
        <Segmented name="privacy-state" aria-label={t('kicker')} value={state}
          options={[{ value: 'open', label: t('open') }, { value: 'closed', label: t('closed') }]} onChange={v => go({ state: v })} />
      </div>
      <h1 className="nl-q-title">{state === 'open' ? t('title', { open: queue.data.length, overdue }) : t('closedTitle')}</h1>
      <p className="nl-q-lede">{t('lede')}</p>
      {can('privacy') ? <div className="nl-q-actions"><Button type="button" variant="secondary" onClick={() => setRecording(true)}>{t('record')}</Button></div> : null}
      <DataTable<Row> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} rowTones={tones}
        can={{ create: false, update: false, delete: false }} roleName={roleName} emptyText={t('empty')} onOpen={r => go({ request: r.id })} />
      {search.request ? <RequestDrawer id={search.request} canAct={can('privacy')} t={t} onClose={() => go({ request: undefined })} /> : null}
      {recording ? <RecordDialog t={t} onClose={() => setRecording(false)} onDone={id => { setRecording(false); go({ state: 'open', request: id }); }} /> : null}
    </div>
  );
}

function RequestDrawer({ id, canAct, t, onClose }: { id: string; canAct: boolean; t: PrivacyT; onClose: () => void }) {
  const { date } = useFormatters();
  const detail = useQuery(detailQuery(id));
  const act = useAct();
  const [dialog, setDialog] = useState<'extend' | 'reject'>();
  const [notice, setNotice] = useState<string>();
  const run = (a: Parameters<typeof act.mutate>[0]) => act.mutate(a, { onSuccess: () => { setNotice(t('done')); setDialog(undefined); } });
  const d = detail.data;
  const error = act.error ? (act.error instanceof ValidationError ? act.error.message : act.error.message) : undefined;
  return (
    <Drawer open onClose={onClose} title={d ? `${d.reference} · ${t(`t_${d.type}`)}` : '…'} width={560}
      footer={d && canAct ? <Actions d={d} t={t} busy={act.isPending} run={run} open={setDialog} /> : null}>
      {detail.isPending ? <PageSkeleton kpis={0} rows={4} />
        : detail.isError || !d ? <ErrorState message={t('loadError')} onRetry={() => void detail.refetch()} />
          : <>
            <p><Tag tone={d.overdue ? 'accent-2' : 'neutral'}>{t(`s_${d.state}` as PrivacyKey)}</Tag></p>
            <p>{t('detailLaw', { law: d.law.name, days: d.law.responseDays, business: String(d.law.businessDays), ext: d.law.extensionDays })}</p>
            <ul className="nl-q-list">
              <li>{t('receivedLine', { date: date(d.receivedAt, 'dateTime'), channel: t(`ch_${d.channel}` as PrivacyKey) })}</li>
              <li>{t('dueLine', { date: date(d.dueAt, 'long') })}</li>
              {d.extendedTo ? <li>{t('extendedLine', { date: date(d.extendedTo, 'long'), reason: d.extensionReason ?? '' })}</li> : null}
              {d.verifiedAt ? <li>{t('verifiedLine', { date: date(d.verifiedAt, 'dateTime'), how: t(`v_${d.verification ?? 'staff'}` as PrivacyKey) })}</li> : null}
              {d.scheduledFor && d.state === 'verified' ? <li>{t('scheduledLine', { date: date(d.scheduledFor, 'dateTime') })}</li> : null}
              {d.completedAt ? <li>{t('completedLine', { date: date(d.completedAt, 'dateTime') })}</li> : null}
              {d.holdsOpen ? <li>{t('holdsLine', { n: d.holdsOpen })}</li> : null}
            </ul>
            {d.corrections.length || d.note ? <>
              <h3>{t('asked')}</h3>
              <ul>{d.corrections.map(c => <li key={c.field}>{t(`f_${c.field}` as PrivacyKey)}: <strong>{c.value}</strong></li>)}</ul>
              {d.note ? <p>{t('noteLabel')}: {d.note}</p> : null}
            </> : null}
            {d.steps.length ? <>
              <h3>{t('steps')}</h3>
              <ul className="nl-q-list">
                {d.steps.map(s => (
                  <li key={s.module}>
                    <strong>{s.module}</strong> · {t(`st_${s.status}` as PrivacyKey)}
                    {s.retained.length ? <> · {t('kept')}: {s.retained.map(k => `${k.category} (${k.reason})`).join(', ')}</> : null}
                    {s.holds.length ? <> · {t('held')}: {s.holds.map(k => `${k.category} (${k.reason})`).join(', ')}</> : null}
                  </li>
                ))}
              </ul>
            </> : null}
            {notice ? <p role="status" className="nl-q-note">{notice}</p> : null}
            {error ? <p role="alert" className="nl-q-error">{error}</p> : null}
          </>}
      {dialog === 'extend' && d ? <ExtendDialog t={t} busy={act.isPending} onClose={() => setDialog(undefined)} onSave={reason => run({ id: d.id, act: 'extend', reason })} /> : null}
      {dialog === 'reject' && d ? <RejectDialog t={t} busy={act.isPending} onClose={() => setDialog(undefined)} onSave={(decision, note) => run({ id: d.id, act: 'reject', decision, note })} /> : null}
    </Drawer>
  );
}

function Actions({ d, t, busy, run, open }: { d: Detail; t: PrivacyT; busy: boolean; run: (a: Parameters<ReturnType<typeof useAct>['mutate']>[0]) => void; open: (x: 'extend' | 'reject') => void }) {
  const openState = ['awaiting_verification', 'verified', 'in_progress'].includes(d.state);
  return (
    <div className="nl-q-actions">
      {d.state === 'awaiting_verification' ? <Button type="button" disabled={busy} onClick={() => run({ id: d.id, act: 'verify' })}>{t('verify')}</Button> : null}
      {d.type === 'erasure' && d.state === 'verified' ? <Button type="button" disabled={busy} onClick={() => run({ id: d.id, act: 'start' })}>{t('start')}</Button> : null}
      {d.type === 'correction' && d.state === 'verified' && d.corrections.length
        ? <Button type="button" disabled={busy} onClick={() => run({ id: d.id, act: 'corrections', corrections: d.corrections })}>{t('apply')}</Button> : null}
      {d.steps.some(s => s.status === 'failed' || s.status === 'held') ? <Button type="button" variant="ghost" disabled={busy} onClick={() => run({ id: d.id, act: 'retry' })}>{t('retry')}</Button> : null}
      {openState && !d.extendedTo && d.law.extensionDays > 0 ? <Button type="button" variant="ghost" disabled={busy} onClick={() => open('extend')}>{t('extend')}</Button> : null}
      {d.state === 'awaiting_verification' || d.state === 'verified' ? <Button type="button" variant="ghost" className="nl-danger" disabled={busy} onClick={() => open('reject')}>{t('reject')}</Button> : null}
    </div>
  );
}

function ExtendDialog({ t, busy, onClose, onSave }: { t: PrivacyT; busy: boolean; onClose: () => void; onSave: (reason: string) => void }) {
  const [reason, setReason] = useState<string>(EXTENSIONS[0]);
  const label: Record<(typeof EXTENSIONS)[number], PrivacyKey> = { volume: 'reasonVolume', consultation: 'reasonConsultation', conversion: 'reasonConversion' };
  return (
    <Dialog open onClose={onClose} title={t('extendTitle')}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button><Button disabled={busy} onClick={() => onSave(reason)}>{t('save')}</Button></>}>
      <Field label={t('extendWhy')}><Select value={reason} onChange={e => setReason(e.target.value)} options={EXTENSIONS.map(r => ({ value: r, label: t(label[r]) }))} /></Field>
    </Dialog>
  );
}

function RejectDialog({ t, busy, onClose, onSave }: { t: PrivacyT; busy: boolean; onClose: () => void; onSave: (decision: string, note?: string) => void }) {
  const [decision, setDecision] = useState<string>(DECISIONS[0]);
  const [note, setNote] = useState('');
  return (
    <Dialog open onClose={onClose} title={t('rejectTitle')}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button><Button disabled={busy} onClick={() => onSave(decision, note.trim() || undefined)}>{t('reject')}</Button></>}>
      <Field label={t('rejectWhy')}><Select value={decision} onChange={e => setDecision(e.target.value)} options={DECISIONS.map(x => ({ value: x, label: t(`d_${x}`) }))} /></Field>
      <Field label={t('rejectNote')}><TextArea value={note} maxLength={500} onChange={e => setNote(e.target.value)} /></Field>
    </Dialog>
  );
}

function RecordDialog({ t, onClose, onDone }: { t: PrivacyT; onClose: () => void; onDone: (id: string) => void }) {
  const record = useRecord();
  const [contact, setContact] = useState('');
  const [type, setType] = useState<(typeof TYPES)[number]>('access');
  const [field, setField] = useState<string>(FIELDS[0]);
  const [value, setValue] = useState('');
  const [note, setNote] = useState('');
  const errors = record.error instanceof ValidationError ? record.error.byField() : {};
  const other = record.error && !(record.error instanceof ValidationError) ? record.error.message : undefined;
  const save = () => record.mutate({
    contact, type, note: note.trim() || undefined, corrections: type === 'correction' ? [{ field, value }] : undefined,
  }, { onSuccess: d => onDone(d.id) });
  return (
    <Dialog open onClose={onClose} title={t('recordTitle')}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button><Button disabled={record.isPending} onClick={save}>{t('save')}</Button></>}>
      <Field label={t('contact')} error={errors.contact}><TextInput value={contact} onChange={e => setContact(e.target.value)} /></Field>
      <Field label={t('type')} error={errors.type}><Select value={type} onChange={e => setType(e.target.value as (typeof TYPES)[number])} options={TYPES.map(x => ({ value: x, label: t(`t_${x}`) }))} /></Field>
      {type === 'correction' ? <>
        <Field label={t('field')}><Select value={field} onChange={e => setField(e.target.value)} options={FIELDS.map(f => ({ value: f, label: t(`f_${f}`) }))} /></Field>
        <Field label={t('value')} error={errors['corrections[0].value'] ?? errors.corrections}><TextInput value={value} onChange={e => setValue(e.target.value)} maxLength={200} /></Field>
      </> : null}
      <Field label={t('note')} error={errors.note}><TextArea value={note} maxLength={1000} onChange={e => setNote(e.target.value)} /></Field>
      {other ? <p role="alert" className="nl-q-error">{other}</p> : null}
    </Dialog>
  );
}
