import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Button, ErrorState, Field, PageSkeleton, Select, Tag, useFormatters } from '@northline/ui';
import { integrationsQuery, type CommerceProvider } from '../catalogue/api';
import { useMerchantId, useRole } from '../shell/api';
import { locationsQuery, stockQuery, useChooseLocation, usePushAction, type PushView } from './integrationsApi';
import { useIntegrationsT, type IntegrationsT } from './integrationsMessages';

const NAMES: Record<CommerceProvider, string> = { shopify: 'Shopify', square: 'Square', lightspeed: 'Lightspeed' };

/** What a change that needs the merchant says (the server's conflict code, or a failure after the retries). */
export function conflictText(p: PushView, platform: string, t: IntegrationsT): string {
  if (p.state === 'failed') return t('c_failed', { platform, attempts: p.attempts });
  switch (p.conflict) {
    case 'oversold': return t('c_oversold', { platform, after: p.platformAfter ?? 0 });
    case 'scope_missing': case 'choose_location': case 'not_found': case 'reconnect': case 'disconnected': case 'rejected': case 'ambiguous':
      return t(`c_${p.conflict}`, { platform });
    default: return t('c_rejected', { platform });
  }
}

/**
 * S-158 two-way stock, per connected store (Settings › API & integrations): the location {brand} sells from (owner),
 * how many changes went out, and the changes the store refused with Retry / Dismiss (who edits listings).
 */
export function StockSync() {
  const merchantId = useMerchantId();
  const commerce = useQuery(integrationsQuery(merchantId));
  const connected = (commerce.data ?? []).filter(c => c.connected);
  if (connected.length === 0) return null;
  return <>{connected.map(c => <StoreStock key={c.provider} provider={c.provider} />)}</>;
}

function StoreStock({ provider }: { provider: CommerceProvider }) {
  const t = useIntegrationsT();
  const merchantId = useMerchantId();
  const role = useRole();
  const owner = role === 'owner';
  const canEdit = role === 'owner' || role === 'technician' || role === 'cook';
  const platform = NAMES[provider];
  const q = useQuery(stockQuery(merchantId, provider));
  const action = usePushAction(merchantId, provider);
  const f = useFormatters();
  const heading = `stock-${provider}`;
  return (
    <section aria-labelledby={heading} className="nl-set-stock">
      <h2 id={heading} className="nl-set-h3 nl-set-gap">{t('stockTitle')} · {platform}</h2>
      <p className="nl-small nl-muted">{t('stockLede', { platform })}</p>
      {q.isPending ? <PageSkeleton kpis={0} rows={2} /> : q.isError ? <ErrorState message={t('stockError')} onRetry={() => void q.refetch()} /> : (
        <>
          {!q.data.canWrite ? (
            <p className="nl-small nl-error" role="status">
              {t('scopeMissing', { platform })}{' '}
              {owner ? <Link to="/b/$merchantId/listings/bulk" params={{ merchantId }}>{t('reconnect')}</Link> : null}
            </p>
          ) : owner ? <LocationPicker provider={provider} current={q.data.locationId ?? null} /> : (
            <p className="nl-small">{q.data.locationName ? `${t('location')}: ${q.data.locationName}` : t('ownerChooses')}</p>
          )}
          <p className="nl-small nl-muted">{t('counts', { done: q.data.done, pending: q.data.pending })}</p>
          {q.data.attention.length === 0 ? <p className="nl-small">{t('attentionNone', { platform })}</p> : (
            <>
              <h3 className="nl-set-h4">{t('attentionTitle', { n: q.data.attention.length })}</h3>
              <ul className="nl-set-hooks">
                {q.data.attention.map(p => {
                  const change = t('change', { reason: t(`reason_${p.reason}`), delta: p.delta > 0 ? `+${p.delta}` : String(p.delta), sku: p.sku });
                  return (
                    <li key={p.id}>
                      <span><strong>{p.listingName ?? t('unknownListing')}</strong> · {change}</span>
                      <span className="nl-set-sub">{f.date(p.createdAt, 'dateTime')} · {conflictText(p, platform, t)}</span>
                      {p.applied ? <span className="nl-set-sub">{t('fixedByHand', { platform })}</span> : null}
                      <span className="nl-set-hookactions">
                        {p.conflict === 'oversold' ? <Tag tone="warning">{platform}</Tag> : null}
                        {canEdit && !p.applied && <Button variant="secondary" aria-label={t('retryLabel', { change })} disabled={action.isPending} onClick={() => action.mutate({ id: p.id, action: 'retry' })}>{t('retry')}</Button>}
                        {canEdit && <Button variant="ghost" aria-label={t('dismissLabel', { change })} disabled={action.isPending} onClick={() => action.mutate({ id: p.id, action: 'dismiss' })}>{t('dismiss')}</Button>}
                      </span>
                    </li>
                  );
                })}
              </ul>
            </>
          )}
          {action.isError && <p role="alert" className="nl-error">{t('actionError')}</p>}
        </>
      )}
    </section>
  );
}

function LocationPicker({ provider, current }: { provider: CommerceProvider; current: string | null }) {
  const t = useIntegrationsT();
  const merchantId = useMerchantId();
  const locations = useQuery(locationsQuery(merchantId, provider));
  const choose = useChooseLocation(merchantId, provider);
  const [picked, setPicked] = useState(current ?? '');
  useEffect(() => setPicked(current ?? ''), [current]);
  if (locations.isPending) return <PageSkeleton kpis={0} rows={1} />;
  if (locations.isError) return <p className="nl-small nl-error">{t('locationsError')}</p>;
  if (locations.data.length === 1 && current === locations.data[0]!.id) {
    return <p className="nl-small">{t('locationOne', { name: locations.data[0]!.name })}</p>;
  }
  return (
    <form className="nl-set-dialogform" onSubmit={e => { e.preventDefault(); if (picked) choose.mutate(picked); }}>
      <Field label={t('location')} hint={t('locationHint')}>
        <Select value={picked} placeholder={t('locationNone')} onChange={e => setPicked(e.target.value)}
          options={locations.data.map(l => ({ value: l.id, label: l.name }))} />
      </Field>
      <div className="nl-set-hookactions">
        <Button type="submit" variant="secondary" disabled={!picked || picked === current || choose.isPending}>{choose.isPending ? t('locationSaving') : t('locationSave')}</Button>
        {choose.isSuccess && <span role="status" className="nl-small">{t('locationSaved')}</span>}
      </div>
      {choose.isError && <p role="alert" className="nl-error">{t('actionError')}</p>}
    </form>
  );
}
