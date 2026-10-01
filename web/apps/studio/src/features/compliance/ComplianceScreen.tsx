import { useState } from 'react';
import { useRegions } from '../shell/place';
import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Alert, Button, DataTable, ErrorState, FileButton, PageHeader, PageSkeleton, Tag, useFormatters, useLocale, type DataTableColumn } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useMerchantId, useRole } from '../shell/api';
import { screenHref } from '../shell/nav';
import { complianceQuery, useAcceptObligations, useRenewDocument, useStripeLink, type Compliance, type ComplianceDoc, type TaxRow } from './api';
import { docName, docNote, docShortName, docState, groupBn, requirementState } from './docs';
import { useComplianceT, type ComplianceT } from './messages';
import './Compliance.css';

const ACCEPT = 'application/pdf,image/png,image/jpeg';

/** Stripe & compliance (design 02 › STRIPE & COMPLIANCE): Stripe Connect, payment settings, tax, licences, obligations. */
export function ComplianceScreen() {
  const t = useComplianceT();
  const merchantId = useMerchantId();
  const q = useQuery(complianceQuery(merchantId));
  if (q.isPending) return <div className="nl-cmp"><PageHeader kicker={t('kicker')} title="…" /><PageSkeleton kpis={0} rows={8} /></div>;
  if (q.isError) return <div className="nl-cmp"><PageHeader kicker={t('kicker')} title={t('kicker')} /><ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /></div>;
  return <ComplianceView data={q.data} />;
}

function headline(d: Compliance, t: ComplianceT): string {
  const due = d.documents.filter(x => x.due);
  const first = due[0];
  if (!first) return t('titleNone');
  return due.length === 1 ? t('titleOne', { doc: docShortName(first, t) }) : t('titleMany', { n: due.length, doc: docShortName(first, t) });
}

export function ComplianceView({ data }: { data: Compliance }) {
  const t = useComplianceT();
  const owner = useRole() === 'owner';
  const kitchen = data.business.type === 'kitchen';
  return (
    <div className="nl-cmp">
      <PageHeader kicker={t('kicker')} title={headline(data, t)} lede={kitchen ? t('ledeKitchen') : t('lede')} />
      {!owner && <p className="nl-small nl-muted nl-cmp-viewonly">{t('ownerOnly')}</p>}
      <div className="nl-cmp-grid">
        <div>
          <StripeSection data={data} owner={owner} />
          <PaymentSettings data={data} />
          <h2 className="nl-cmp-h2 nl-cmp-gap">{t('taxTitle')}</h2>
          <TaxTable rows={data.tax} period={data.taxPeriod} takeRateBps={data.business.takeRateBps ?? null} />
        </div>
        <div>
          <Documents data={data} owner={owner} />
          <ObligationsSection data={data} owner={owner} />
        </div>
      </div>
    </div>
  );
}

function StripeSection({ data, owner }: { data: Compliance; owner: boolean }) {
  const t = useComplianceT();
  const merchantId = useMerchantId();
  const link = useStripeLink(merchantId);
  const s = data.stripe;
  const b = data.business;
  const open = (kind: 'dashboard' | 'update') => link.mutate(kind, {
    onSuccess: ({ url }) => { if (kind === 'dashboard') window.open(url, '_blank', 'noopener'); else window.location.assign(url); },
  });
  const typeName = s.type === 'standard' ? t('typeStandard') : s.type === 'custom' ? t('typeCustom') : t('typeExpress');
  const name = (r: Compliance['stripe']['requirements'][number]) => {
    switch (r.kind) {
      case 'identity': return b.ownerName ? t('req_identity', { name: b.ownerName }) : t('req_identityNoName');
      case 'business': return b.businessNumber ? t('req_businessBn', { name: b.legalName, bn: groupBn(b.businessNumber) }) : t('req_business', { name: b.legalName });
      case 'bank': return s.bankLabel ? t('req_bank', { bank: s.bankLabel }) : t('req_bankNone');
      case 'owners': return t('req_owners');
      case 'annual_reverification': return t('req_annual_reverification');
    }
  };
  return (
    <section aria-labelledby="cmp-stripe">
      <h2 id="cmp-stripe" className="nl-cmp-h2">{t('stripeTitle')}</h2>
      {s.status === 'not_connected' ? (
        <>
          <p className="nl-cmp-meta">{t('notConnected')}</p>
          {owner && <Button onClick={() => open('update')} disabled={link.isPending}>{link.isPending ? t('opening') : t('setUpStripe')}</Button>}
        </>
      ) : (
        <>
          <div className="nl-cmp-meta">{t('stripeMeta', { account: s.accountId ?? '', type: typeName, payouts: s.payoutsEnabled ? t('payoutsOn') : t('payoutsOff'), charges: s.chargesEnabled ? t('chargesOn') : t('chargesOff') })}</div>
          {s.status === 'unavailable' ? <Alert tone="error" role="status">{t('stripeUnavailable')}</Alert> : (
            <ul className="nl-cmp-rows">
              {s.requirements.map(r => {
                const st = requirementState(r, t);
                return <li key={r.kind} className="nl-cmp-row"><span>{name(r)}</span><Tag tone={st.tone}>{st.text}</Tag></li>;
              })}
            </ul>
          )}
          {owner && (
            <div className="nl-cmp-actions">
              <Button onClick={() => open('dashboard')} disabled={link.isPending}>{t('openDashboard')}</Button>
              <Button variant="secondary" onClick={() => open('update')} disabled={link.isPending}>{t('updateIdentity')}</Button>
            </div>
          )}
        </>
      )}
      {link.isError && <p role="alert" className="nl-error">{t('stripeError')}</p>}
    </section>
  );
}

function PaymentSettings({ data }: { data: Compliance }) {
  const t = useComplianceT();
  const merchantId = useMerchantId();
  const s = data.stripe;
  const day = s.payoutWeekday ? t(`day_${s.payoutWeekday}` as Parameters<ComplianceT>[0]) : '';
  const schedule = [
    s.payoutInterval === 'daily' ? t('payoutDaily') : s.payoutInterval === 'monthly' ? t('payoutMonthly') : s.payoutInterval === 'manual' ? t('payoutManual') : s.payoutInterval ? t('payoutWeekly', { day }) : null,
    s.instantPayouts ? t('instantAvailable') : null,
  ].filter(Boolean).join(' · ');
  return (
    <section aria-labelledby="cmp-pay">
      <h2 id="cmp-pay" className="nl-cmp-h2 nl-cmp-gap">{t('paymentSettings')}</h2>
      <dl className="nl-cmp-dl">
        <div><dt>{t('escrowModel')}</dt><dd>{t('escrowValue')}</dd></div>
        <div><dt>{t('payoutSchedule')}</dt><dd>{schedule ? <Link to={screenHref(merchantId, 'payouts')}>{schedule}</Link> : '—'}</dd></div>
        <div><dt>{t('statementDescriptor')}</dt><dd>{s.statementDescriptor ?? '—'}</dd></div>
        <div><dt>{t('radar')}</dt><dd><Tag tone="accent">{t('platformManaged')}</Tag></dd></div>
        <div><dt>{t('cra')}</dt><dd>{t('craValue')}</dd></div>
      </dl>
    </section>
  );
}

interface TaxLine { id: string; name: string; amt: number; note: string }

/** Jurisdiction codes the Stripe Tax sync writes (S-21): `<province>_<taxes>`. */
const TAX_JURISDICTIONS = new Set(['ab_gst', 'bc_gst_pst', 'mb_gst_pst', 'nb_hst', 'nl_hst', 'ns_hst', 'nt_gst', 'nu_gst', 'on_hst', 'pe_hst', 'qc_gst_qst', 'sk_gst_pst', 'yt_gst']);

function TaxTable({ rows, period, takeRateBps }: { rows: TaxRow[]; period: string; takeRateBps: number | null }) {
  const t = useComplianceT();
  const regions = useRegions();
  const provinceName = (code: string) => regions?.provinces.find(p => p.code === code)?.name ?? code;
  const role = useRole();
  const quarter = period.split('-')[1] ?? period;
  const lines: TaxLine[] = rows.map(r => ({
    id: r.jurisdiction,
    name: r.jurisdiction === 'platform_fee_gst'
      ? (takeRateBps ? t('tax_platform_fee_gst', { pct: takeRateBps / 100 }) : t('tax_platform_fee_gst_plain'))
      : TAX_JURISDICTIONS.has(r.jurisdiction) ? t(`tax_${r.jurisdiction}` as Parameters<ComplianceT>[0], { name: provinceName(r.jurisdiction.slice(0, 2).toUpperCase()) }) : r.jurisdiction,
    amt: r.collectedCents,
    note: r.handling === 'not_selling' && r.jurisdiction.startsWith('bc_') ? t('handling_not_selling_bc') : t(`handling_${r.handling}`),
  }));
  const columns: DataTableColumn<TaxLine>[] = [
    { key: 'name', label: t('colJurisdiction'), primary: true },
    { key: 'amt', label: t('colCollected', { quarter }), type: 'money' },
    { key: 'note', label: t('colHandling'), filter: 'facet' },
  ];
  return (
    <DataTable<TaxLine> entity={t('taxEntity')} plural={t('taxPlural')} columns={columns} rows={lines} roleName={role[0]!.toUpperCase() + role.slice(1)}
      can={{ create: false, update: false, delete: false, export: true }} emptyText={t('taxEmpty')} reportName={`tax-${period}`} />
  );
}

function Documents({ data, owner }: { data: Compliance; owner: boolean }) {
  const t = useComplianceT();
  const regions = useRegions();
  const provinceName = (code: string) => regions?.provinces.find(p => p.code === code)?.name ?? code;
  const { locale } = useLocale();
  const f = useFormatters();
  const merchantId = useMerchantId();
  const renew = useRenewDocument(merchantId);
  const [uploading, setUploading] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const b = data.business;
  const what = [b.requiredFor, b.province ? provinceName(b.province) : null].filter(Boolean).join(' · ');
  const upload = (d: ComplianceDoc, file: File) => {
    setUploading(d.id);
    setErrors(e => ({ ...e, [d.id]: '' }));
    renew.mutate({ id: d.id, file }, {
      onError: err => setErrors(e => ({ ...e, [d.id]: err instanceof ValidationError ? err.errors[0]?.message ?? t('uploadError') : t('uploadError') })),
      onSettled: () => setUploading(null),
    });
  };
  return (
    <section aria-labelledby="cmp-docs">
      <h2 id="cmp-docs" className="nl-cmp-h2">{t('docsTitle')}</h2>
      <div className="nl-cmp-meta">{what ? <>{t('docsForLead')}<strong>{what}</strong>{t('docsForTail')}</> : t('docsForNone')}</div>
      {data.documents.length === 0 ? <p className="nl-muted">{t('docsEmpty')}</p> : (
        <ul className="nl-cmp-docs">
          {data.documents.map(d => {
            const st = docState(d, t, locale);
            const name = docName(d, t);
            const errId = `cmp-err-${d.id}`;
            return (
              <li key={d.id} className="nl-cmp-doc">
                <span>
                  <strong>{name}</strong>
                  <span className="nl-cmp-note">{docNote(d, t)}</span>
                  {d.pausesAt && <span className="nl-cmp-warn">{new Date(d.pausesAt).getTime() > Date.now() ? t('doc_pauses', { date: f.date(d.pausesAt) }) : t('doc_paused')}</span>}
                  {errors[d.id] ? <span id={errId} role="alert" className="nl-error">{errors[d.id]}</span> : null}
                </span>
                <span className="nl-cmp-doc-side">
                  <Tag tone={st.tone}>{st.text}</Tag>
                  {st.action && owner && (
                    <FileButton accept={ACCEPT} className="nl-cmp-upload" pending={uploading === d.id} aria-describedby={errors[d.id] ? errId : undefined}
                      onFile={file => upload(d, file)}>
                      <span aria-hidden="true">{uploading === d.id ? t('uploading') : t('upload')}</span>
                      <span className="nl-sr-only">{t('uploadFor', { doc: name })}</span>
                    </FileButton>
                  )}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

function ObligationsSection({ data, owner }: { data: Compliance; owner: boolean }) {
  const t = useComplianceT();
  const f = useFormatters();
  const merchantId = useMerchantId();
  const accept = useAcceptObligations(merchantId);
  const o = data.obligations;
  const kitchen = data.business.type === 'kitchen';
  return (
    <section aria-labelledby="cmp-ob">
      <h2 id="cmp-ob" className="nl-cmp-h2 nl-cmp-gap">{t('obligationsTitle')}</h2>
      {!o.upToDate && (
        <Alert tone="highlight" actions={owner ? <Button onClick={() => accept.mutate(o.currentVersion)} disabled={accept.isPending}>{accept.isPending ? t('obAccepting') : t('obAccept', { version: o.currentVersion })}</Button> : undefined}>
          {t('obNew', { version: o.currentVersion })}
        </Alert>
      )}
      <ul className="nl-cmp-obligations">
        <li>{t('ob1')}</li><li>{t('ob2')}</li><li>{t('ob3')}</li><li>{t('ob4')}</li><li>{kitchen ? t('ob5Kitchen') : t('ob5')}</li>
      </ul>
      <div className="nl-cmp-meta nl-cmp-versions">
        {o.acceptedVersion && o.acceptedAt ? t('obAccepted', { version: o.acceptedVersion, date: f.date(o.acceptedAt, 'full') }) : t('obNever')}
        <a href="/legal/terms.html#business" target="_blank" rel="noopener">{t('obRead')}</a>
      </div>
      {accept.isError && <p role="alert" className="nl-error">{t('obError')}</p>}
    </section>
  );
}
