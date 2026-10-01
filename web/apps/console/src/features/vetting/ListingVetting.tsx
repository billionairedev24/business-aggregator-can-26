import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearch } from '@tanstack/react-router';
import { CheckCircle, XCircle } from '@phosphor-icons/react';
import { Button, Checkbox, DataTable, Dialog, ErrorState, Field, formatMoney, formatNumber, PageSkeleton, TextArea, useLocale, type DataTableAction, type DataTableColumn, type DataTableTone, type Locale } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { PlaceFilters, type PlaceFilter } from '../shell/PlaceFilters';
import { SCREEN_PATH } from '../shell/screens';
import { REJECT_REASONS, useDecideVetting, vettingQuery, type Item, type RejectReason } from './api';
import { useVettingT, type VettingKey, type VettingT } from './messages';
import '../shell/queues.css';

interface Row { id: string; name: string; price: string; seller: string; rule: string; evidence: string; status: string; item: Item }

const signedPct = (pct: number, locale: Locale) => `${pct > 0 ? '+' : pct < 0 ? '−' : ''}${formatNumber(Math.abs(pct) / 100, locale, { style: 'percent', maximumFractionDigits: 0 })}`;

/** "Price −72% vs median · Missing licence": the rules an item tripped, in the design's words. */
export function ruleText(i: Item, t: VettingT, locale: Locale): string {
  const parts = i.flags.map(f => {
    if (f === 'price_outlier' || f === 'price_check') return t(`r_${f}`, { pct: i.deviationPct == null ? '—' : signedPct(i.deviationPct, locale) });
    const key = `r_${f}` as VettingKey;
    const text = t(key);
    return text === key ? t('r_other') : text;
  });
  if (i.trustFlags.length) parts.push(t('r_ai'));
  return parts.join(' · ');
}

/** "Median $180 · Changed: price": what the reviewer weighs, from the api's figures (never place names in code). */
export function evidenceText(i: Item, t: VettingT, locale: Locale): string {
  const parts: string[] = [];
  for (const f of i.flags) {
    if ((f === 'price_outlier' || f === 'price_check') && i.medianCents != null) parts.push(t(f === 'price_check' ? 'e_cuisineMedian' : 'e_median', { median: formatMoney(i.medianCents, locale, { whole: i.medianCents % 100 === 0 }) }));
    if (f === 'missing_licence') parts.push(i.regulator ? t('e_licence', { registry: i.regulator }) : t('e_licenceAny'));
    if (f === 'banned_category') parts.push(i.category ? t('e_banned', { category: i.category }) : t('e_bannedAny'));
    if (f === 'duplicate_image') parts.push(t('e_duplicate'));
    if (f === 'main_not_on_white') parts.push(t('e_white'));
  }
  i.trustFlags.forEach(f => parts.push(f.explanation));
  if (i.revetReasons.length) parts.push(t('e_revet', { fields: i.revetReasons.map(r => t(`f_${r}` as VettingKey)).join(', ') }));
  return parts.join(' · ');
}

/**
 * Listing vetting (S-92, design 03 `vetting`): what the automated checks flagged (S-39 re-vets included), listings an
 * S-133 AI flag points at and dishes held by the S-67 price check — approve or reject with reasons (role `vet`);
 * rejected sellers are emailed. Admin and trust & safety open it.
 */
export function ListingVetting() {
  const t = useVettingT();
  const { locale } = useLocale();
  const search = useSearch({ strict: false }) as PlaceFilter;
  const filter = { province: search.province, market: search.market };
  const query = useQuery(vettingQuery(filter));
  const { can, roleName } = useGrant();
  const decide = useDecideVetting();
  const [rejecting, setRejecting] = useState<Item | null>(null);
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);

  const header = <div className="nl-q-top"><span className="nl-q-kicker">{t('kicker')}</span><PlaceFilters to={SCREEN_PATH.vetting} filter={filter} /></div>;
  if (query.isPending) return <PageSkeleton kpis={0} rows={6} />;
  if (query.isError && !query.data) {
    return <div>{header}{query.error instanceof ValidationError ? <p role="alert" className="nl-q-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}</div>;
  }
  const data = query.data!;
  const status = (i: Item) => (i.state === 'pending' ? t('pending') : i.state === 'approved' ? t('approved') : i.state === 'rejected' ? t('rejected') : i.state);
  const rows: Row[] = data.items.map(i => ({
    id: i.id, name: i.name, price: i.priceCents == null ? t('quote') : formatMoney(i.priceCents, locale), seller: i.businessName,
    rule: ruleText(i, t, locale), evidence: evidenceText(i, t, locale), status: status(i), item: i,
  }));
  const tones = (r: Row): Partial<Record<keyof Row, DataTableTone>> => ({
    rule: 'tag-accent-2', status: r.item.state === 'pending' ? 'tag-highlight' : r.item.state === 'approved' ? 'tag-accent' : 'tag-neutral',
  });
  const columns: DataTableColumn<Row>[] = [
    { key: 'name', label: t('colListing'), sub: 'price', subLabel: t('colPrice'), primary: true },
    { key: 'seller', label: t('colSeller'), filter: 'facet' },
    { key: 'rule', label: t('colRule'), type: 'tag', editable: false },
    { key: 'evidence', label: t('colEvidence') },
    { key: 'status', label: t('colDecision'), type: 'tag', editable: false },
  ];
  const pending = t('pending');
  const actions: DataTableAction<Row>[] = [
    { id: 'approve', label: t('approve'), icon: CheckCircle, perm: 'update', inline: true, bulk: false, when: { key: 'status', in: [pending] } },
    { id: 'reject', label: t('reject'), icon: XCircle, perm: 'update', inline: true, bulk: false, when: { key: 'status', in: [pending] } },
  ];
  const onAction = async (a: DataTableAction<Row>, hit: Row[]) => {
    const item = hit[0]!.item;
    if (a.id === 'reject') { setRejecting(item); return false; }
    setNotice(null);
    try {
      await decide.mutateAsync({ item, decision: 'approve' });
      setNotice({ tone: 'ok', text: t('approvedToast', { name: item.name }) });
    } catch (e) {
      setNotice({ tone: 'error', text: e instanceof ApiError ? e.message : String(e) });
    }
    return false;
  };
  return (
    <div>
      {header}
      <h1 className="nl-q-title">{t('title', { auto: formatNumber(data.autoApproved, locale), n: data.flagged })}</h1>
      <p className="nl-q-lede">{t('lede')}</p>
      {notice ? <p role={notice.tone === 'error' ? 'alert' : 'status'} className={notice.tone === 'error' ? 'nl-q-error' : 'nl-q-note'}>{notice.text}</p> : null}
      <DataTable<Row> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} rowTones={tones}
        can={{ create: false, update: can('vet'), delete: false }} roleName={roleName} actions={actions} onAction={onAction} emptyText={t('empty')} />
      {rejecting ? <RejectDialog item={rejecting} onClose={() => setRejecting(null)}
        onDone={() => { setNotice({ tone: 'ok', text: t('rejectedToast', { name: rejecting.name }) }); setRejecting(null); }} /> : null}
    </div>
  );
}

/** Reject with reasons (one or more) and an optional note; both reach the seller by email. */
function RejectDialog({ item, onClose, onDone }: { item: Item; onClose: () => void; onDone: () => void }) {
  const t = useVettingT();
  const decide = useDecideVetting();
  const [reasons, setReasons] = useState<RejectReason[]>([]);
  const [note, setNote] = useState('');
  const errors = decide.error instanceof ValidationError ? decide.error.byField() : {};
  const other = decide.error && !(decide.error instanceof ValidationError) ? (decide.error as Error).message : undefined;
  return (
    <Dialog open onClose={onClose} title={t('rejectTitle', { name: item.name })}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={decide.isPending} onClick={() => decide.mutate({ item, decision: 'reject', reasons, note: note || undefined }, { onSuccess: onDone })}>{t('rejectSend')}</Button></>}>
      <fieldset className="nl-q-field">
        <legend>{t('rejectWhy')}</legend>
        {REJECT_REASONS.map(r => (
          <Checkbox key={r} label={t(`why_${r}`)} checked={reasons.includes(r)} onChange={on => setReasons(x => (on ? [...x, r] : x.filter(y => y !== r)))} />
        ))}
        {errors.reasons ? <span className="nl-q-error" role="alert">{errors.reasons}</span> : null}
      </fieldset>
      <Field label={t('rejectNote')} hint={t('rejectHint')} error={errors.note}>
        <TextArea value={note} maxLength={500} onChange={e => setNote(e.target.value)} />
      </Field>
      {other ? <p role="alert" className="nl-q-error">{other}</p> : null}
    </Dialog>
  );
}
