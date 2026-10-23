import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useSearch } from '@tanstack/react-router';
import { BoHeader, Button, DataTable, Drawer, ErrorState, Field, formatDate, formatPrice, PageSkeleton, Select, SheetSection, TextArea, TextInput, useLocale, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import type { PlaceFilter } from '../shell/PlaceFilters';
import { usePlace } from '../shell/scope';
import { evidenceUrl } from './api';
import { cardDisputeQuery, cardDisputesQuery, FILE_FIELDS, TEXT_FIELDS, useSaveEvidence, useSubmitEvidence, type CardDetail, type CardItem } from './cardApi';
import { useCardDisputesT, type CardKey } from './cardMessages';
import '../shell/queues.css';
import './disputes.css';

export interface CardSearch extends PlaceFilter { dispute?: string }
const PATH = '/disputes/card';
type State = 'open' | 'submitted' | 'won' | 'lost';
const stateOf = (r: CardItem['row']): State => (r.decision === 'release' ? 'won' : r.decision ? 'lost' : r.submittedAt ? 'submitted' : 'open');
const TONE: Record<State, DataTableTone> = { open: 'status-warning', submitted: 'status-info', won: 'status-success', lost: 'status-danger' };

interface Row { id: string; ref: string; business: string; amount: number; due: string; state: string; item: CardItem }

/**
 * Disputes › Card disputes (S-156): chargebacks Stripe told us about, the issuer's deadline first. The sheet
 * (`?dispute=`) assembles Stripe's evidence from the case — text answers, the business's files — saves it as a draft
 * and submits it once (role `decide`). A refusal from Stripe is shown with the draft kept.
 */
export function CardDisputes() {
  const t = useCardDisputesT();
  const { locale } = useLocale();
  const search = useSearch({ strict: false }) as CardSearch;
  const filter = usePlace();
  const navigate = useNavigate();
  const query = useQuery(cardDisputesQuery(filter));
  const { roleName } = useGrant();
  const head = <BoHeader title={t('title')} sub={t('lede')} actions={<><Link className="btn btn-secondary" to="/disputes">{t('back')}</Link></>} />;
  if (query.isPending) return <div className="bo-page">{head}<PageSkeleton kpis={0} rows={4} /></div>;
  if (query.isError && !query.data) {
    return <div className="bo-page">{head}{query.error instanceof ValidationError ? <p role="alert" className="nl-q-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}</div>;
  }
  const items = query.data!.items;
  const selected = search.dispute ? items.find(i => i.row.id === search.dispute) : undefined;
  const select = (id: string | undefined) => void navigate({ to: PATH, search: { ...search, dispute: id } as never });
  const rows: Row[] = items.map(i => ({
    id: i.row.id, ref: i.row.caseNumber, business: i.businessName, amount: i.row.amountCents,
    due: i.row.dueBy ? t('due', { date: formatDate(i.row.dueBy, locale, 'dateTime') }) : t('noDue'), state: t(`s_${stateOf(i.row)}`), item: i,
  }));
  const columns: DataTableColumn<Row>[] = [
    { key: 'ref', label: t('c_case'), primary: true },
    { key: 'business', label: t('c_business') },
    { key: 'amount', label: t('c_amount'), type: 'money' },
    { key: 'due', label: t('c_due') },
    { key: 'state', label: t('c_state'), type: 'tag', filter: 'facet' },
  ];
  return (
    <div className="bo-page">
      {head}
      <DataTable<Row> dense entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} rowTones={r => ({ state: TONE[stateOf(r.item.row)] })}
        can={{ create: false, update: false, delete: false }} roleName={roleName} emptyText={t('empty')} openLabel={t('open')} onOpen={r => select(r.id)} reportName="card-disputes" />
      {selected ? (
        <Drawer open onClose={() => select(undefined)} title={`${selected.row.caseNumber} · ${selected.businessName}`}>
          <CardBody key={selected.row.id} item={selected} />
        </Drawer>
      ) : null}
    </div>
  );
}

function CardBody({ item }: { item: CardItem }) {
  const t = useCardDisputesT();
  const { locale } = useLocale();
  const { can, roleName } = useGrant();
  const detail = useQuery(cardDisputeQuery(item.row.id));
  const save = useSaveEvidence();
  const submit = useSubmitEvidence();
  const [fields, setFields] = useState<Record<string, string>>({});
  const [files, setFiles] = useState<Record<string, string>>({});
  const d: CardDetail | undefined = detail.data;
  useEffect(() => { if (d) { setFields(d.detail.fields); setFiles(d.detail.files); } }, [d]);
  const row = d?.item.row ?? item.row;
  const state = stateOf(row);
  const editable = state === 'open';
  const money = (c: number) => formatPrice(c, locale);
  const errors = save.error instanceof ValidationError ? save.error.byField() : {};
  const refusal = [save.error, submit.error].find(e => e && !(e instanceof ValidationError)) as ApiError | Error | undefined;
  const stored = (d?.detail.caseEvidence ?? []).filter(e => e.file);
  return (
    <>
      <p className="bo-muted">{t('meta', { business: item.businessName, amount: money(row.amountCents), reason: (row.stripeReason ?? '').replace(/_/g, ' '), status: (row.stripeStatus ?? '').replace(/_/g, ' ') })}
        {row.feeCents ? ` · ${t('fee', { amount: money(row.feeCents) })}` : ''}</p>
      <SheetSection title={t('secResponse')}>
        <p className="nl-ds-quote">{d?.detail.merchantResponse ?? t('noResponse')}</p>
        {stored.length ? <ul className="nl-ds-files">{stored.map(e => <li key={e.id}><a className="nl-ds-file" href={evidenceUrl(row.id, e.id)} target="_blank" rel="noreferrer">{e.name}</a></li>)}</ul> : null}
      </SheetSection>
      <SheetSection title={t('secEvidence')}>
        {row.decision ? <p role="status">{t('closed', { decision: t(row.decision === 'release' ? 'd_release' : 'd_full_refund') })}</p>
          : row.submittedAt ? <p role="status">{t('submitted', { date: formatDate(row.submittedAt, locale, 'dateTime') })}</p> : null}
        {d?.detail.lastError && editable ? <p role="alert" className="nl-q-error">{t('lastError', { error: d.detail.lastError })}</p> : null}
        {TEXT_FIELDS.map(f => (
          <Field key={f} label={t(`f_${f}` as CardKey)} error={errors[`fields.${f}`]}>
            {f === 'service_date'
              ? <TextInput value={fields[f] ?? ''} disabled={!editable || !can('decide')} onChange={e => setFields(x => ({ ...x, [f]: e.target.value }))} />
              : <TextArea value={fields[f] ?? ''} rows={3} maxLength={5000} disabled={!editable || !can('decide')} onChange={e => setFields(x => ({ ...x, [f]: e.target.value }))} />}
          </Field>
        ))}
        {editable && can('decide') && d?.detail.merchantResponse && !fields.uncategorized_text ? (
          <Button variant="tertiary" onClick={() => setFields(x => ({ ...x, uncategorized_text: d.detail.merchantResponse ?? '' }))}>{t('useResponse')}</Button>
        ) : null}
      </SheetSection>
      <SheetSection title={t('secFiles')}>
        {FILE_FIELDS.map(f => (
          <Field key={f} label={t(`f_${f}` as CardKey)} error={errors[`files.${f}`]}>
            <Select value={files[f] ?? ''} disabled={!editable || !can('decide')} placeholder={t('noFile')}
              options={stored.map(e => ({ value: e.id, label: e.name }))} onChange={e => setFiles(x => ({ ...x, [f]: e.target.value }))} />
          </Field>
        ))}
        {editable ? (
          <div className="nl-q-actions">
            <Button variant="secondary" disabled={!can('decide') || save.isPending} onClick={() => save.mutate({ id: row.id, fields, files })}>{t('save')}</Button>
            <Button disabled={!can('decide') || submit.isPending || save.isPending}
              onClick={() => save.mutate({ id: row.id, fields, files }, { onSuccess: () => submit.mutate(row.id) })}>{t('submit')}</Button>
            {!can('decide') ? <span className="nl-q-note">{t('viewOnly', { role: roleName })}</span> : null}
          </div>
        ) : null}
        {save.isSuccess && !submit.isPending && !submit.isSuccess ? <p role="status" className="nl-q-note">{t('saved')}</p> : null}
        {refusal ? <p role="alert" className="nl-q-error">{refusal.message}</p> : null}
      </SheetSection>
    </>
  );
}
