import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useSearch } from '@tanstack/react-router';
import { BoHeader, Button, DataTable, Drawer, ErrorState, Field, formatDate, formatPrice, PageSkeleton, SheetSection, TextArea, useLocale, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useAge } from '../shell/age';
import { useGrant } from '../shell/grant';
import type { PlaceFilter } from '../shell/PlaceFilters';
import { usePlace } from '../shell/scope';
import { appealFileUrl, appealQuery, appealsQuery, useAssignAppeal, useDecideAppeal, type QueueItem } from './api';
import { useAppealsT } from './messages';
import '../shell/queues.css';

export interface AppealsSearch extends PlaceFilter { appeal?: string }
const PATH = '/trust/appeals';
const TONE: Record<string, DataTableTone> = { open: 'status-warning', in_review: 'status-info', granted: 'status-success', denied: 'status-neutral' };

interface Row { id: string; number: string; business: string; about: string; filed: string; owner: string; state: string; item: QueueItem }

/**
 * Trust & safety › Appeals (S-156): businesses' appeals against a dispute decision or a consequence, waiting ones first.
 * A row opens the sheet (`?appeal=`): the statement and files, what was decided and by whom, take it, and grant or deny
 * with a reason (role `decide`). Admin and trust & safety open it.
 */
export function Appeals() {
  const t = useAppealsT();
  const search = useSearch({ strict: false }) as AppealsSearch;
  const filter = usePlace();
  const navigate = useNavigate();
  const query = useQuery(appealsQuery(filter));
  const age = useAge();
  const { roleName } = useGrant();
  const head = <BoHeader title={t('title')} sub={t('lede')} actions={<><Link className="btn btn-secondary" to="/trust">{t('back')}</Link></>} />;
  if (query.isPending) return <div className="bo-page">{head}<PageSkeleton kpis={0} rows={4} /></div>;
  if (query.isError && !query.data) {
    return <div className="bo-page">{head}{query.error instanceof ValidationError ? <p role="alert" className="nl-q-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}</div>;
  }
  const items = query.data!.items;
  const selected = search.appeal ? items.find(i => i.appeal.id === search.appeal) : undefined;
  const select = (id: string | undefined) => void navigate({ to: PATH, search: { ...search, appeal: id } as never });
  const rows: Row[] = items.map(i => ({
    id: i.appeal.id, number: i.appeal.number, business: i.businessName, about: about(t, i), filed: t('filed', { age: age(i.appeal.filedAt) }),
    owner: i.assigneeName ?? t('nobody'), state: t(`st_${i.appeal.state}`), item: i,
  }));
  const columns: DataTableColumn<Row>[] = [
    { key: 'number', label: t('c_appeal'), sub: 'about', subLabel: t('c_about'), primary: true },
    { key: 'business', label: t('c_business') },
    { key: 'filed', label: t('c_filed') },
    { key: 'owner', label: t('c_owner') },
    { key: 'state', label: t('c_state'), type: 'tag', filter: 'facet' },
  ];
  return (
    <div className="bo-page">
      {head}
      <DataTable<Row> dense entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} rowTones={r => ({ state: TONE[r.item.appeal.state] ?? 'tag-neutral' })}
        can={{ create: false, update: false, delete: false }} roleName={roleName} emptyText={t('empty')} openLabel={t('open')} onOpen={r => select(r.id)} reportName="appeals" />
      {selected ? (
        <Drawer open onClose={() => select(undefined)} title={`${selected.appeal.number} · ${selected.businessName}`}>
          <AppealBody key={selected.appeal.id} item={selected} />
        </Drawer>
      ) : null}
    </div>
  );
}

function about(t: ReturnType<typeof useAppealsT>, i: QueueItem) {
  if (i.appeal.subjectType === 'dispute') return t('about_dispute', { label: i.appeal.subjectLabel ?? '' });
  const k = `about_${i.appeal.subjectKind}` as Parameters<typeof t>[0];
  return t(k);
}

function AppealBody({ item }: { item: QueueItem }) {
  const t = useAppealsT();
  const { locale } = useLocale();
  const { can, roleName } = useGrant();
  const detail = useQuery(appealQuery(item.appeal.id));
  const assign = useAssignAppeal();
  const decide = useDecideAppeal();
  const [reason, setReason] = useState('');
  const d = detail.data;
  const a = d?.item.appeal ?? item.appeal;
  const decided = a.state === 'granted' || a.state === 'denied';
  const errors = decide.error instanceof ValidationError ? decide.error.byField() : {};
  const refusal = [assign.error, decide.error].find(e => e && !(e instanceof ValidationError)) as ApiError | Error | undefined;
  const money = (c: number) => formatPrice(c, locale);
  const date = (s: string) => formatDate(s, locale);
  return (
    <>
      <SheetSection title={t('secStatement')}><p className="nl-ds-quote">{a.statement}</p></SheetSection>
      <SheetSection title={t('secFiles')}>
        {a.files.length
          ? <ul className="nl-ds-files">{a.files.map(f => <li key={f.id}><a className="nl-ds-file" href={appealFileUrl(a.id, f.id)} target="_blank" rel="noreferrer">{f.name}</a></li>)}</ul>
          : <p className="bo-muted">{t('noFiles')}</p>}
      </SheetSection>
      <SheetSection title={t('secSubject')}>
        {d?.consequence ? <>
          <p>{t('subjectConsequence', { what: about(t, item), by: d.consequence.cause === 'staff' ? t('byStaff') : t('byRules'), date: date(d.consequence.startedAt) })}</p>
          <p className="bo-muted">{t('reason', { reason: d.consequence.reason })}</p>
        </> : null}
        {d?.dispute ? <>
          <p>{t('subjectDispute', { case: d.dispute.caseNumber, subject: d.dispute.subject, decision: t(d.dispute.decision === 'partial' ? 'd_partial' : 'd_full_refund'), amount: money(d.dispute.refundCents) })}</p>
          {d.dispute.note ? <p className="bo-muted">{t('note', { note: d.dispute.note })}</p> : null}
        </> : null}
        {d?.originalActorName ? <p className="bo-muted">{t('original', { name: d.originalActorName })}</p> : null}
      </SheetSection>
      <SheetSection title={t('secDecision')}>
        {decided ? (
          <>
            <p role="status">{t('decidedLine', { state: t(`st_${a.state}`), date: a.decidedAt ? date(a.decidedAt) : '' })}</p>
            {a.decisionReason ? <p className="nl-ds-quote">{a.decisionReason}</p> : null}
            {a.compensationCents > 0 ? <p>{t('paid', { amount: money(a.compensationCents) })}</p> : null}
          </>
        ) : (
          <>
            {d?.item.assigneeName ? <p className="bo-muted">{t('taken', { name: d.item.assigneeName })}</p> : null}
            <Field label={t('reasonLabel')} error={errors.reason}>
              <TextArea value={reason} maxLength={1000} onChange={e => setReason(e.target.value)} />
            </Field>
            {errors.outcome ? <p role="alert" className="nl-q-error">{errors.outcome}</p> : null}
            <div className="nl-q-actions">
              {a.state === 'open' ? <Button variant="secondary" disabled={!can('decide') || assign.isPending} onClick={() => assign.mutate(a.id)}>{t('take')}</Button> : null}
              <Button disabled={!can('decide') || decide.isPending} onClick={() => decide.mutate({ id: a.id, outcome: 'grant', reason })}>{t('grant')}</Button>
              <Button variant="secondary" disabled={!can('decide') || decide.isPending} onClick={() => decide.mutate({ id: a.id, outcome: 'deny', reason })}>{t('deny')}</Button>
              {!can('decide') ? <span className="nl-q-note">{t('viewOnly', { role: roleName })}</span> : null}
            </div>
          </>
        )}
        {refusal ? <p role="alert" className="nl-q-error">{refusal.message}</p> : null}
      </SheetSection>
    </>
  );
}
