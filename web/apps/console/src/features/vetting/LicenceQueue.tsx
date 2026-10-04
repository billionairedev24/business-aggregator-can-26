import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CheckCircle, FileText, XCircle } from '@phosphor-icons/react';
import { Button, DataTable, Dialog, ErrorState, Field, PageSkeleton, Segmented, Select, TextArea, formatDate, useLocale, type DataTableAction, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import type { PlaceFilter } from '../shell/PlaceFilters';
import { LICENCE_REASONS, documentHref, licencesQuery, useDecideLicence, type LicenceItem, type LicenceReason } from './ageApi';
import { useAgeVetT, type AgeVetKey } from './ageMessages';

interface Row { id: string; business: string; cls: string; number: string; expires: string; province: string; status: string; item: LicenceItem }
const FILTERS = ['pending', 'approved', 'rejected', 'expired'] as const;

/**
 * Console › Listing vetting › Licences (2026-10-04): licences for age-restricted sales in review. Approve, or reject
 * with a reason (role `vet`); the document opens in a new tab; every decision is audited and emailed to the owners.
 */
export function LicenceQueue({ filter }: { filter: PlaceFilter }) {
  const t = useAgeVetT();
  const { locale } = useLocale();
  const [status, setStatus] = useState<(typeof FILTERS)[number]>('pending');
  const q = useQuery(licencesQuery(filter, status));
  const { can, roleName } = useGrant();
  const decide = useDecideLicence();
  const [rejecting, setRejecting] = useState<LicenceItem | null>(null);
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);
  if (q.isPending) return <PageSkeleton kpis={0} rows={4} />;
  if (q.isError && !q.data) return <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} />;
  const items = q.data!.items;
  const st = (s: string) => t(`st_${s}` as AgeVetKey);
  const rows: Row[] = items.map(i => ({
    id: i.licence.id, business: i.businessName, cls: t(`cls_${i.licence.ageClass}`), number: i.licence.licenceNumber,
    expires: formatDate(`${i.licence.expiresOn}T12:00:00Z`, locale, 'long'), province: i.licence.province, status: st(i.licence.status), item: i,
  }));
  const tones = (r: Row): Partial<Record<keyof Row, DataTableTone>> => ({
    status: r.item.licence.status === 'pending' ? 'tag-highlight' : r.item.licence.status === 'approved' ? 'tag-accent' : 'tag-neutral',
  });
  const columns: DataTableColumn<Row>[] = [
    { key: 'business', label: t('colBusiness'), sub: 'cls', subLabel: t('colClass'), primary: true },
    { key: 'number', label: t('colNumber') },
    { key: 'expires', label: t('colExpires') },
    { key: 'province', label: t('colProvince'), filter: 'facet' },
    { key: 'status', label: t('colStatus'), type: 'tag', editable: false },
  ];
  const pending = st('pending');
  const actions: DataTableAction<Row>[] = [
    { id: 'document', label: t('document'), icon: FileText, inline: true, bulk: false },
    { id: 'approve', label: t('approve'), icon: CheckCircle, perm: 'update', inline: true, bulk: false, when: { key: 'status', in: [pending] } },
    { id: 'reject', label: t('reject'), icon: XCircle, perm: 'update', inline: true, bulk: false, when: { key: 'status', in: [pending] } },
  ];
  const onAction = async (a: DataTableAction<Row>, hit: Row[]) => {
    const item = hit[0]!.item;
    if (a.id === 'document') { window.open(documentHref(item.licence.id), '_blank', 'noopener'); return false; }
    if (a.id === 'reject') { setRejecting(item); return false; }
    setNotice(null);
    try {
      await decide.mutateAsync({ id: item.licence.id, decision: 'approve' });
      setNotice({ tone: 'ok', text: t('approvedToast', { name: item.businessName }) });
    } catch (e) {
      setNotice({ tone: 'error', text: e instanceof ApiError ? e.message : String(e) });
    }
    return false;
  };
  const elsewhere = items.filter(i => i.businessProvince && i.businessProvince !== i.licence.province && i.licence.status === 'pending');
  return (
    <div>
      <h1 className="nl-q-title">{t('licTitle', { n: status === 'pending' ? items.length : '…' })}</h1>
      <p className="nl-q-lede">{t('licLede')}</p>
      <Segmented name="licence-status" aria-label={t('filter')} value={status} onChange={v => setStatus(v)} options={FILTERS.map(f => ({ value: f, label: t(`f_${f}`) }))} />
      {elsewhere.map(i => <p key={i.licence.id} className="nl-q-note">{i.businessName}: {t('otherProvince', { licence: i.licence.province, business: i.businessProvince ?? '' })}</p>)}
      {notice ? <p role={notice.tone === 'error' ? 'alert' : 'status'} className={notice.tone === 'error' ? 'nl-q-error' : 'nl-q-note'}>{notice.text}</p> : null}
      <DataTable<Row> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} rowTones={tones}
        can={{ create: false, update: can('vet'), delete: false }} roleName={roleName} actions={actions} onAction={onAction} emptyText={t('empty')} />
      {rejecting ? <RejectLicence item={rejecting} onClose={() => setRejecting(null)}
        onDone={() => { setNotice({ tone: 'ok', text: t('rejectedToast', { name: rejecting.businessName }) }); setRejecting(null); }} /> : null}
    </div>
  );
}

function RejectLicence({ item, onClose, onDone }: { item: LicenceItem; onClose: () => void; onDone: () => void }) {
  const t = useAgeVetT();
  const decide = useDecideLicence();
  const [reason, setReason] = useState<LicenceReason>('unreadable');
  const [note, setNote] = useState('');
  const errors = decide.error instanceof ValidationError ? decide.error.byField() : {};
  return (
    <Dialog open onClose={onClose} title={t('rejectTitle', { name: item.businessName })}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={decide.isPending} onClick={() => decide.mutate({ id: item.licence.id, decision: 'reject', reason, note: note || undefined }, { onSuccess: onDone })}>{t('rejectSend')}</Button></>}>
      <Field label={t('why')} error={errors.reason}>
        <Select value={reason} onChange={e => setReason(e.target.value as LicenceReason)} options={LICENCE_REASONS.map(r => ({ value: r, label: t(`why_${r}`) }))} />
      </Field>
      <Field label={t('note')} hint={t('noteHint')} error={errors.note}>
        <TextArea value={note} maxLength={500} onChange={e => setNote(e.target.value)} />
      </Field>
    </Dialog>
  );
}
