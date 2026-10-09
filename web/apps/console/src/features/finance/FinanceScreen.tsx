import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, DataTable, Drawer, ErrorState, Field, formatNumber, Kpi, KpiRow, PageSkeleton, TextArea, TextInput, useFormatters, useLocale, type DataTableColumn, type DataTableTone, type Locale } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { dayQuery, daysQuery, exportUrl, financeQuery, ledgerExportUrl, useReconcileTax, useResolveDay, useRunDay, type Day, type Finance, type Item } from './api';
import { useFinanceT, type FinanceKey } from './messages';
import { RefundRequests } from './RefundRequests';
import { PromoCodes } from './PromoCodes';
import './finance.css';

const STATUS_TONE: Record<Day['status'], DataTableTone> = { matched: 'tag-accent', mismatch: 'tag-accent-2', resolved: 'tag-neutral' };
const errText = (e: unknown) => (e instanceof ValidationError ? Object.values(e.byField())[0] : e instanceof ApiError ? e.message : undefined);
/** A date in the platform zone, ISO (YYYY-MM-DD). */
const isoDay = (at: number, zone: string) => new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(at));
/** "Sep 7" / "7 sept." for a calendar day (no zone shift). */
const dayLabel = (day: string, locale: Locale) => new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${day}T12:00:00Z`));

/**
 * Finance (S-85, design 03 `finance`): escrow held, payouts in flight, the week's net revenue and its mix, take rate by
 * tier, the daily Stripe ↔ ledger reconciliation with drill-down, resolution and export, and the quarter's tax with S-21's
 * Stripe Tax reconciliation, and support's refund requests waiting for finance. Admin and finance; changes need
 * `payouts`, refund decisions `refund`.
 */
export function FinanceScreen() {
  const t = useFinanceT();
  const finance = useQuery(financeQuery);
  const days = useQuery(daysQuery);
  if (finance.isPending || days.isPending) return <PageSkeleton kpis={5} rows={8} />;
  if (finance.isError || days.isError) return <ErrorState message={t('loadError')} onRetry={() => { void finance.refetch(); void days.refetch(); }} />;
  return <FinanceView data={finance.data} days={days.data} />;
}

function FinanceView({ data, days }: { data: Finance; days: Day[] }) {
  const t = useFinanceT();
  const { locale } = useLocale();
  const fmt = useFormatters();
  const latest = days[0];
  const mix: { key: FinanceKey; cents: number | null | undefined; pass?: boolean }[] = [
    { key: 'm_take', cents: data.mix.takeCents }, { key: 'm_delivery', cents: data.mix.deliveryCents },
    { key: 'm_plus', cents: data.mix.plusCents }, { key: 'm_rewards', cents: data.mix.rewardsCents, pass: true },
    ...(data.mix.adjustmentsCents ? [{ key: 'm_adjustments' as FinanceKey, cents: data.mix.adjustmentsCents }] : []),
  ];
  const max = Math.max(1, ...mix.map(m => Math.abs(m.cents ?? 0)));
  return (
    <div>
      <span className="nl-fi-kicker">{t('kicker')}</span>
      <h1 className="nl-fi-title">{t('title')}</h1>
      <KpiRow>
        <Kpi value={fmt.money(data.escrowHeldCents, { whole: true })} label={t('k_escrow', { n: data.escrowItems })} />
        <Kpi value={fmt.money(data.payoutsInFlightCents, { whole: true })} label={t('k_payouts', { n: data.payoutsInFlightSellers })} />
        <Kpi value={fmt.money(data.revenueWeekCents, { whole: true })} label={t('k_revenue')} />
        <Kpi value={t('notRecorded')} label={t('k_plus')} />
        <Kpi value={latest ? <span data-tone={latest.varianceCents === 0 ? 'ok' : 'off'} className="nl-fi-variance">{fmt.money(latest.varianceCents)}</span> : t('notRecorded')}
          label={latest ? `${t('k_variance')} · ${dayLabel(latest.day, locale)}` : t('k_varianceNone')} />
      </KpiRow>
      <div className="nl-fi-cols">
        <div>
          <h2 className="nl-fi-h2">{t('mixTitle')}</h2>
          <div className="nl-fi-sub">{t('mixSub')}</div>
          {mix.map(m => (
            <div key={m.key} className="nl-fi-bar">
              <span>{t(m.key)}</span>
              <div className="nl-fi-track"><div className="nl-fi-fill" data-pass={m.pass || undefined} style={{ width: `${(Math.abs(m.cents ?? 0) / max) * 100}%` }} /></div>
              <strong>{m.cents == null ? t('notRecorded') : fmt.money(m.cents, { whole: true })}</strong>
            </div>
          ))}
          <h2 className="nl-fi-h2 nl-fi-gap">{t('tiersTitle')}</h2>
          <Tiers data={data} />
        </div>
        <div>
          <h2 className="nl-fi-h2">{t('reconTitle')}</h2>
          <Reconciliation days={days} zone={data.timeZone} />
          <h2 className="nl-fi-h2 nl-fi-gap">{t('taxTitle')}</h2>
          <TaxBlock data={data} />
        </div>
      </div>
      <h2 className="nl-fi-h2 nl-fi-gap">{t('refundsTitle')}</h2>
      <p className="nl-fi-sub">{t('refundsSub')}</p>
      <RefundRequests />
      <PromoCodes />
    </div>
  );
}

interface TierRow { id: string; tier: string; sellers: number; rate: string; share: string }

function Tiers({ data }: { data: Finance }) {
  const t = useFinanceT();
  const { locale } = useLocale();
  const { roleName } = useGrant();
  const rows: TierRow[] = data.tiers.map(r => ({
    id: r.tier, tier: t(`tier_${r.tier}` as FinanceKey), sellers: r.sellers, rate: `${formatNumber(r.rateBps / 100, locale)}%`,
    share: r.gmvShare == null ? t('notRecorded') : formatNumber(r.gmvShare, locale, { style: 'percent', maximumFractionDigits: 0 }),
  }));
  const columns: DataTableColumn<TierRow>[] = [
    { key: 'tier', label: t('t_tier'), primary: true }, { key: 'sellers', label: t('t_sellers'), type: 'num' },
    { key: 'rate', label: t('t_rate') }, { key: 'share', label: t('t_share') },
  ];
  return <DataTable<TierRow> entity={t('tierEntity')} plural={t('tierPlural')} columns={columns} rows={rows} pageSize={5}
    can={{ create: false, update: false, delete: false }} roleName={roleName} />;
}

interface DayRow { id: string; date: string; stripe: number; ledger: number; variance: number; status: string; src: Day }

function Reconciliation({ days, zone }: { days: Day[]; zone: string }) {
  const t = useFinanceT();
  const { locale } = useLocale();
  const { can, roleName } = useGrant();
  const run = useRunDay();
  const yesterday = isoDay(Date.now() - 86_400_000, zone);
  const [day, setDay] = useState(yesterday);
  const [open, setOpen] = useState<string | null>(null);
  const rows: DayRow[] = days.map(d => ({
    id: d.day, date: dayLabel(d.day, locale), stripe: d.stripeCents, ledger: d.ledgerCents, variance: d.varianceCents,
    status: d.status === 'resolved' && d.resolvedNote ? t('s_resolvedNote', { note: d.resolvedNote }) : t(`s_${d.status}` as FinanceKey), src: d,
  }));
  const columns: DataTableColumn<DayRow>[] = [
    { key: 'date', label: t('r_date'), primary: true }, { key: 'stripe', label: t('r_stripe'), type: 'money' }, { key: 'ledger', label: t('r_ledger'), type: 'money' },
    { key: 'variance', label: t('r_variance'), type: 'money' }, { key: 'status', label: t('r_status'), type: 'tag' },
  ];
  const from = days.at(-1)?.day ?? yesterday;
  const to = days[0]?.day ?? yesterday;
  return (
    <>
      <DataTable<DayRow> entity={t('dayEntity')} plural={t('dayPlural')} columns={columns} rows={rows} pageSize={10} emptyText={t('noDays')}
        rowTones={r => ({ status: STATUS_TONE[r.src.status] })} can={{ create: false, update: false, delete: false }} roleName={roleName}
        openLabel={t('open')} onOpen={r => setOpen(r.id)} reportName="reconciliation" />
      <div className="nl-fi-actions">
        {can('payouts') ? (
          <form className="nl-fi-run" onSubmit={e => { e.preventDefault(); run.mutate(day); }}>
            <TextInput type="date" aria-label={t('runDayLabel')} value={day} max={yesterday} onChange={e => setDay(e.target.value)} />
            <Button type="submit" variant="secondary" disabled={!day || run.isPending}>{t('run')}</Button>
          </form>
        ) : null}
        <a className="btn btn-secondary" href={exportUrl(from, to)} download>{t('exportRecon')}</a>
      </div>
      {run.error ? <p role="alert" className="nl-fi-error">{errText(run.error)}</p> : null}
      {open ? <DayDrawer day={open} onClose={() => setOpen(null)} /> : null}
    </>
  );
}

interface ItemRow { id: string; kind: string; stripe: string; ledger: string; status: string; src: Item }

function DayDrawer({ day, onClose }: { day: string; onClose: () => void }) {
  const t = useFinanceT();
  const { locale } = useLocale();
  const fmt = useFormatters();
  const { can, roleName } = useGrant();
  const detail = useQuery(dayQuery(day));
  const resolve = useResolveDay();
  const rerun = useRunDay();
  const [note, setNote] = useState('');
  const d = detail.data?.day;
  const money = (c: number | null | undefined) => (c == null ? t('notRecorded') : fmt.money(c));
  const rows: ItemRow[] = (detail.data?.items ?? []).map((i, n) => ({
    id: `${n}`, kind: t(`k_${i.kind}` as FinanceKey), status: t(`is_${i.status}` as FinanceKey), src: i,
    stripe: i.stripeId ? `${i.stripeId} · ${money(i.stripeCents)}` : t('notRecorded'),
    ledger: i.ledgerRefType ? `${i.ledgerRefType} ${i.ledgerRefId ?? ''} · ${money(i.ledgerCents)}` : t('notRecorded'),
  }));
  const columns: DataTableColumn<ItemRow>[] = [
    { key: 'kind', label: t('i_kind'), primary: true }, { key: 'stripe', label: t('i_stripe') }, { key: 'ledger', label: t('i_ledger') },
    { key: 'status', label: t('i_status'), type: 'tag' },
  ];
  return (
    <Drawer open onClose={onClose} title={t('detailTitle', { day: dayLabel(day, locale) })} width={720}>
      {d ? (
        <>
          <p className="nl-fi-sub">{t('d_summary', { stripe: money(d.stripeCents), ledger: money(d.ledgerCents), variance: money(d.varianceCents), fees: money(d.feeCents) })}</p>
          <p className="nl-fi-sub">{t('d_items', { n: d.items, m: d.mismatches })}</p>
          <DataTable<ItemRow> entity={t('k_charge')} columns={columns} rows={rows} pageSize={10} roleName={roleName}
            rowTones={r => ({ status: r.src.status === 'matched' ? 'tag-accent' : 'tag-accent-2' })} can={{ create: false, update: false, delete: false }} />
          {d.status === 'resolved' && d.resolvedNote ? <p className="nl-fi-sub">{t('resolvedBy', { date: fmt.date(d.resolvedAt ?? d.computedAt), note: d.resolvedNote })}</p> : null}
          {can('payouts') ? (
            <div className="nl-fi-resolve">
              {d.status === 'mismatch' ? (
                <Field label={t('resolveLabel')} error={resolve.error instanceof ValidationError ? resolve.error.byField().note : undefined}>
                  <TextArea value={note} maxLength={500} onChange={e => setNote(e.target.value)} />
                </Field>
              ) : null}
              <div className="nl-fi-actions">
                {d.status === 'mismatch' ? <Button disabled={resolve.isPending} onClick={() => resolve.mutate({ day, note })}>{t('resolve')}</Button> : null}
                <Button variant="secondary" disabled={rerun.isPending} onClick={() => rerun.mutate(day, { onSuccess: () => void detail.refetch() })}>{t('rerun')}</Button>
              </div>
              {resolve.error && !(resolve.error instanceof ValidationError) ? <p role="alert" className="nl-fi-error">{errText(resolve.error)}</p> : null}
            </div>
          ) : null}
        </>
      ) : null}
    </Drawer>
  );
}

function TaxBlock({ data }: { data: Finance }) {
  const t = useFinanceT();
  const { locale } = useLocale();
  const fmt = useFormatters();
  const { can } = useGrant();
  const tax = useReconcileTax();
  const quarter = data.tax.period.split('-')[1] ?? data.tax.period;
  const filing = new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${data.tax.nextFiling}T12:00:00Z`));
  const [year, q] = data.tax.period.split('-Q');
  const startMonth = (Number(q) - 1) * 3 + 1;
  const from = `${year}-${String(startMonth).padStart(2, '0')}-01`;
  const to = isoDay(Date.now(), data.timeZone);
  return (
    <>
      <dl className="nl-fi-tax">
        <div><dt>{t('tax_fees', { quarter })}</dt><dd>{fmt.money(data.tax.platformFeeCents, { whole: true })}</dd></div>
        <div><dt>{t('tax_goods', { quarter })}</dt><dd>{fmt.money(data.tax.facilitatorCents, { whole: true })}</dd></div>
        <div><dt>{t('tax_next')}</dt><dd>{filing}</dd></div>
      </dl>
      <div className="nl-fi-actions">
        <a className="btn btn-secondary" href={ledgerExportUrl(from, to)} download>{t('exportAccounting')}</a>
        {can('payouts') ? <Button variant="secondary" disabled={tax.isPending} onClick={() => tax.mutate(data.tax.period)}>{t('reconcileTax')}</Button> : null}
      </div>
      {tax.data ? <p role="status" className="nl-fi-sub">{t('taxReport', { checked: tax.data.checked, mismatched: tax.data.mismatched, pending: tax.data.stillPending })}</p> : null}
      {tax.error ? <p role="alert" className="nl-fi-error">{errText(tax.error)}</p> : null}
    </>
  );
}
