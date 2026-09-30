import { useEffect, useId, useState } from 'react';
import { TextInput, useFormatters } from '@northline/ui';
import type { MerchantType } from '../shell/api';
import { ValidationError } from '../../lib/http';
import { useSimulateDns, useUpdateStorefront, useVerifyDomain, type DomainSetup, type Storefront } from './api';
import { useStorefrontT } from './messages';

/**
 * Custom domain (design 02: "Custom domain (optional)" + "Point a CNAME at pages.northline.ca; we issue the
 * certificate. Your northline.ca address keeps working."), S-31: the status of the domain, what is still missing, the
 * DNS records to add (CNAME — or ALIAS / A records for a root domain — and the ownership TXT), the grace period when
 * the records stopped pointing at Northline, Check now, and in dev builds "Simulate DNS records →".
 */
export function CustomDomainField({ storefront: s, canEdit }: { storefront: Storefront; canEdit: boolean }) {
  const t = useStorefrontT();
  const f = useFormatters();
  const id = useId();
  const type: MerchantType = s.business.type;
  const update = useUpdateStorefront(s.merchantId);
  const verify = useVerifyDomain(s.merchantId);
  const simulate = useSimulateDns(s.merchantId);
  const [domain, setDomain] = useState(s.customDomain ?? '');
  useEffect(() => setDomain(s.customDomain ?? ''), [s.customDomain]);
  const error = update.error instanceof ValidationError ? update.error.errors.find(e => e.field === 'customDomain')?.message : undefined;
  const status = s.customDomain ? s.customDomainStatus : null;
  const setup = s.customDomain ? s.customDomainSetup : null;
  const target = setup?.target ?? s.customDomainTarget;
  const showRecords = setup != null && status !== 'live';

  return (
    <div className="nl-field">
      <label className="nl-label" htmlFor={id}>{t('customDomain')}</label>
      <div className="nl-domain-row">
        <TextInput
          id={id}
          placeholder={t(`domainPh_${type}`)}
          value={domain}
          disabled={!canEdit}
          aria-invalid={error ? true : undefined}
          aria-describedby={`${id}-hint`}
          onChange={e => setDomain(e.target.value)}
          onBlur={() => { if (domain.trim() !== (s.customDomain ?? '')) update.mutate({ customDomain: domain.trim() }); }}
          onKeyDown={e => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }}
        />
        {status && status !== 'live' && canEdit
          ? <button type="button" className="btn btn-secondary" disabled={verify.isPending} onClick={() => verify.mutate()}>{t('checkNow')}</button>
          : null}
      </div>
      {error ? <div role="alert" className="nl-error">{error}</div> : null}
      {status
        ? (
          <div className="nl-hint" data-status={status} role="status">
            {t(`domain_${status}`)}
            {setup?.problem && !setup.graceEndsAt ? <> {t(`problem_${setup.problem}`, { target })}</> : null}
          </div>
        )
        : null}
      {setup?.graceEndsAt
        ? <div className="nl-domain-grace" role="alert">{t('domainGrace', { date: f.date(setup.graceEndsAt, 'dateTime') })}{setup.problem ? <> {t(`problem_${setup.problem}`, { target })}</> : null}</div>
        : null}
      <div className="nl-hint" id={`${id}-hint`}>{t('domainHint', { target })}</div>
      {showRecords ? <DnsRecords setup={setup} domain={s.customDomain!} /> : null}
      {setup?.checkedAt ? <div className="nl-hint">{t('lastChecked', { date: f.date(setup.checkedAt, 'dateTime') })}</div> : null}
      {import.meta.env.DEV && showRecords && canEdit
        ? <div><button type="button" className="btn btn-ghost" disabled={simulate.isPending} onClick={() => simulate.mutate()}>{t('simulateDns')}</button></div>
        : null}
    </div>
  );
}

function DnsRecords({ setup, domain }: { setup: DomainSetup; domain: string }) {
  const t = useStorefrontT();
  const [copied, setCopied] = useState<number | null>(null);
  const addresses = setup.records.some(r => r.type === 'A' || r.type === 'AAAA');
  return (
    <div className="nl-dns">
      {setup.apex
        ? <p className="nl-hint">{t(addresses ? 'apexAddresses' : 'apexGuidance', { domain, target: setup.target })}</p>
        : null}
      <p className="nl-hint">{t('domainRecords')}</p>
      <ul className="nl-dns-records">
        {setup.records.map((r, i) => (
          <li key={`${r.type}-${r.value}`} className="nl-dns-record">
            <span className="nl-dns-type" aria-label={t('recordType')}>{r.type === 'ALIAS' ? 'ALIAS / ANAME' : r.type}</span>
            <dl>
              <dt>{t('recordName')}</dt><dd><code>{r.name}</code></dd>
              <dt>{t('recordValue')}</dt><dd><code>{r.value}</code></dd>
            </dl>
            <button
              type="button"
              className="btn btn-ghost"
              aria-label={t('copyRecord', { type: r.type })}
              onClick={() => { void navigator.clipboard?.writeText(r.value).then(() => setCopied(i)); }}
            >
              {copied === i ? t('copiedRecord') : t('copy')}
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
