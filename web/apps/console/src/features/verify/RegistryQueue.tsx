import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ArrowClockwise, Check as CheckIcon, X } from '@phosphor-icons/react';
import { Button, DataTable, Dialog, ErrorState, Field, PageSkeleton, TextArea, TextInput, type DataTableAction, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useAge } from '../shell/age';
import { useGrant } from '../shell/grant';
import type { PlaceFilter } from '../shell/PlaceFilters';
import { registryQueueQuery, useDecideRegistryWithEvidence, useRecheckRegistry, type Rechecked, type RegistryQueueRow } from './api';
import { ApplicationsHead } from './ApplicationsHead';
import { useRegistryT, type RegistryT } from './registryMessages';
import { checkLabel } from './VerificationQueue';
import { useVerifyT } from './messages';
import '../shell/queues.css';

interface Row { id: string; business: string; check: string; number: string; source: string; found: string; outcome: string; checked: string; src: RegistryQueueRow }

const OUTCOMES = ['matched', 'mismatch', 'not_found', 'manual', 'unavailable'] as const;
const SOURCES = ['corporations_canada', 'alberta_corporate_registry', 'calgary_business_licences', 'manual'] as const;

/** "Doesn't match · name, status" — the outcome and why, in the console's words. */
export function outcomeText(r: Pick<RegistryQueueRow, 'outcome' | 'reasons'>, t: RegistryT): string {
  const outcome = (OUTCOMES as readonly string[]).includes(r.outcome) ? t(`out_${r.outcome as (typeof OUTCOMES)[number]}`) : r.outcome;
  const reasons = r.reasons.map(x => (x === 'name' || x === 'status' || x === 'expired' ? t(`rsn_${x}`) : x));
  return reasons.length ? t('outcomeWith', { outcome, reasons: reasons.join(', ') }) : outcome;
}
/** The 409's `code` (ProblemDetail), when the api sent one. */
const codeOf = (e: unknown) => (e instanceof ApiError && e.body && typeof e.body === 'object' ? (e.body as { code?: string }).code : undefined);
const sourceText = (s: string, t: RegistryT) => ((SOURCES as readonly string[]).includes(s) ? t(`src_${s as (typeof SOURCES)[number]}`) : s);

/**
 * Applications › Registry checks (S-158, S-23's queue): every business-registry lookup that needs a human — not found,
 * not matching, a source that was down, a regulator without an API — with what was entered and what the registry
 * says. An agent re-runs the lookup (the open review is superseded by the new outcome), or approves / rejects with what
 * they checked. Opening needs the verification screen, acting the `verify` action; the api audit-logs every change.
 */
export function RegistryQueue({ filter }: { filter: PlaceFilter }) {
  const t = useRegistryT();
  const tv = useVerifyT();
  const age = useAge();
  const { can, roleName } = useGrant();
  const q = useQuery(registryQueueQuery);
  const recheck = useRecheckRegistry();
  const [deciding, setDeciding] = useState<{ row: RegistryQueueRow; decision: 'approve' | 'reject' } | null>(null);
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);

  if (q.isPending) return <div className="bo-page"><ApplicationsHead tab="registry" filter={filter} /><PageSkeleton kpis={0} rows={6} /></div>;
  if (q.isError) return <div className="bo-page"><ApplicationsHead tab="registry" filter={filter} /><ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /></div>;

  const rows: Row[] = q.data.map(r => ({
    id: r.id, business: r.businessName, check: checkLabel({ key: r.checkKey, registry: r.registry ?? null }, tv), number: r.queryNumber,
    source: sourceText(r.source, t),
    found: r.recordName ? t('record', { name: r.recordName, status: r.recordStatus ?? '—' }) : t('noRecord'),
    outcome: outcomeText(r, t), checked: age(r.checkedAt), src: r,
  }));
  const columns: DataTableColumn<Row>[] = [
    { key: 'business', label: t('colBusiness'), sub: 'check', subLabel: t('colCheck'), primary: true },
    { key: 'number', label: t('colNumber') },
    { key: 'source', label: t('colSource'), filter: 'facet' },
    { key: 'found', label: t('colFound') },
    { key: 'outcome', label: t('colOutcome'), type: 'tag', editable: false },
    { key: 'checked', label: t('colChecked'), editable: false },
  ];
  const tones = (r: Row): Partial<Record<keyof Row, DataTableTone>> => ({ outcome: r.src.outcome === 'unavailable' || r.src.outcome === 'manual' ? 'tag-neutral' : 'tag-accent-2' });
  const actions: DataTableAction<Row>[] = can('verify') ? [
    { id: 'recheck', label: t('recheck'), icon: ArrowClockwise, perm: 'update', inline: true, bulk: false },
    { id: 'approve', label: t('approve'), icon: CheckIcon, perm: 'update', inline: true, bulk: false },
    { id: 'reject', label: t('reject'), icon: X, perm: 'update', inline: true, bulk: false },
  ] : [];
  const onAction = async (action: DataTableAction<Row>, hit: Row[]) => {
    const row = hit[0]!.src;
    if (action.id === 'recheck') {
      setNotice(null);
      try {
        const r = (await recheck.mutateAsync(row.id)) as Rechecked;
        const first = r.checks[0];
        setNotice({ tone: 'ok', text: r.verificationStatus === 'verified'
          ? t('recheckVerified', { business: row.businessName })
          : t('recheckDone', { business: row.businessName, outcome: first ? outcomeText(first, t) : r.verificationStatus }) });
      } catch (e) {
        setNotice({ tone: 'error', text: codeOf(e) === 'review_closed' ? t('closed') : codeOf(e) === 'nothing_to_look_up' ? t('nothingToLookUp') : t('failed') });
      }
      return false;
    }
    setDeciding({ row, decision: action.id === 'approve' ? 'approve' : 'reject' });
    return false;
  };
  return (
    <div className="bo-page">
      <ApplicationsHead tab="registry" filter={filter} sub={t('sub', { n: q.data.length })} />
      {notice ? <p role={notice.tone === 'error' ? 'alert' : 'status'} className={notice.tone === 'error' ? 'nl-q-error' : 'nl-q-note'}>{notice.text}</p> : null}
      <DataTable<Row> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} rowTones={tones} pageSize={25}
        can={{ create: false, update: can('verify'), delete: false }} roleName={roleName} actions={actions} onAction={onAction} emptyText={t('empty')} />
      {deciding ? <DecideDialog row={deciding.row} decision={deciding.decision} check={checkLabel({ key: deciding.row.checkKey, registry: deciding.row.registry ?? null }, tv)}
        onClose={() => setDeciding(null)} onDone={text => { setDeciding(null); setNotice({ tone: 'ok', text }); }} /> : null}
    </div>
  );
}

function DecideDialog({ row, decision, check, onClose, onDone }: { row: RegistryQueueRow; decision: 'approve' | 'reject'; check: string; onClose: () => void; onDone: (text: string) => void }) {
  const t = useRegistryT();
  const decide = useDecideRegistryWithEvidence();
  const [reference, setReference] = useState('');
  const [expiresOn, setExpiresOn] = useState('');
  const [note, setNote] = useState('');
  const errors = decide.error instanceof ValidationError ? decide.error.byField() : {};
  const other = decide.error && !(decide.error instanceof ValidationError)
    ? (codeOf(decide.error) === 'review_closed' ? t('closed') : t('failed')) : null;
  const submit = () => decide.mutate(
    { reviewId: row.id, decision, reference: reference.trim() || undefined, expiresOn: expiresOn || undefined, note: note.trim() || undefined },
    { onSuccess: () => onDone(t(decision === 'approve' ? 'approved' : 'rejected', { business: row.businessName })) },
  );
  return (
    <Dialog open onClose={onClose} title={t('decideTitle', { decision: t(decision), check, business: row.businessName })}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button variant={decision === 'reject' ? 'danger' : 'primary'} disabled={decide.isPending} onClick={submit}>{t(decision === 'approve' ? 'confirmApprove' : 'confirmReject')}</Button></>}>
      {decision === 'approve' ? <>
        <Field label={t('reference')} hint={t('referenceHint')} error={errors.reference}><TextInput value={reference} maxLength={120} onChange={e => setReference(e.target.value)} /></Field>
        <Field label={t('expiresOn')} hint={t('expiresHint')} error={errors.expiresOn}><TextInput type="date" value={expiresOn} onChange={e => setExpiresOn(e.target.value)} /></Field>
      </> : null}
      <Field label={t('note')} hint={t('noteHint')} error={errors.note}><TextArea value={note} maxLength={500} rows={3} onChange={e => setNote(e.target.value)} /></Field>
      {other ? <p role="alert" className="nl-q-error">{other}</p> : null}
    </Dialog>
  );
}
