import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Alert, Button, Dialog, ErrorState, Field, Skeleton, TextInput, useFormatters } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useMerchantId } from '../shell/api';
import { integrationsQuery, useConnectCommerce, useIntegrationAction, type CommerceProvider, type Connection } from './api';
import { useCatalogueT } from './messages';

const PROVIDERS: CommerceProvider[] = ['shopify', 'square', 'lightspeed'];
const NAMES: Record<CommerceProvider, string> = { shopify: 'Shopify', square: 'Square', lightspeed: 'Lightspeed' };
const SHOP = /^[a-z0-9][a-z0-9-]*\.myshopify\.com$/;

/** Where the platforms' OAuth callback (S-35, on the api host) sends the browser: `?commerce=shopify&result=connected`. */
export interface CommerceReturn { commerce?: CommerceProvider; result?: 'connected' | 'denied' | 'failed' | 'expired' }

/** `my-store`, `my-store.myshopify.com` or an admin URL → `my-store.myshopify.com` (same rule as the server). */
export function normalizeShop(input: string): string | null {
  let s = input.trim().toLowerCase().replace(/^https?:\/\//, '').replace(/\/.*$/, '');
  if (!s) return null;
  if (!s.includes('.')) s += '.myshopify.com';
  return SHOP.test(s) ? s : null;
}

/**
 * "Or connect" (design bulk): Shopify / Square / Lightspeed. Connect is OAuth (owner only); the catalogue is then
 * imported as drafts, and price & stock follow the platform (webhooks, or hourly). The API key row points to Settings.
 */
export function CommerceIntegrations({ canManage, canSync, returned, onReturnSeen }: { canManage: boolean; canSync: boolean; returned?: CommerceReturn; onReturnSeen?: () => void }) {
  const t = useCatalogueT();
  const merchantId = useMerchantId();
  const { date } = useFormatters();
  const q = useQuery({ ...integrationsQuery(merchantId), refetchInterval: query => (query.state.data?.some(c => c.syncStatus === 'importing') ? 3000 : false) });
  const action = useIntegrationAction(merchantId);
  const connect = useConnectCommerce(merchantId);
  const [failed, setFailed] = useState<CommerceProvider | null>(null);
  const [shopOpen, setShopOpen] = useState(false);
  const [openErrors, setOpenErrors] = useState<CommerceProvider | null>(null);
  const [banner] = useState(returned?.commerce && returned.result ? returned : undefined);
  useEffect(() => { if (returned?.result) onReturnSeen?.(); }, [returned, onReturnSeen]);

  const run = (provider: CommerceProvider, a: 'disconnect' | 'sync') => {
    setFailed(null);
    action.mutate({ provider, action: a }, { onError: () => setFailed(provider) });
  };
  const start = (provider: CommerceProvider) => {
    setFailed(null);
    if (provider === 'shopify') { setShopOpen(true); return; }
    connect.mutate({ provider }, { onError: () => setFailed(provider) });
  };
  const busy = (p: CommerceProvider) => (action.isPending && action.variables?.provider === p) || (connect.isPending && connect.variables?.provider === p);

  return (
    <div className="nl-field" role="group" aria-labelledby="connect-label">
      <span className="nl-label" id="connect-label">{t('orConnect')}</span>
      {banner?.commerce && banner.result && (
        <Alert tone={banner.result === 'connected' ? 'info' : 'error'} role="status">{t(`ret_${banner.result}`, { platform: NAMES[banner.commerce] })}</Alert>
      )}
      {q.isPending ? <Skeleton height={120} /> : q.isError ? <ErrorState message={t('integrationsError')} onRetry={() => void q.refetch()} /> : (
        <ul className="nl-cat-integrations">
          {PROVIDERS.map(p => {
            const c: Connection = q.data.find(x => x.provider === p) ?? { provider: p, connected: false };
            const available = c.available !== false;
            const reconnect = c.connected && c.state === 'reconnect';
            const errors = c.errors ?? [];
            return (
              <li key={p} className="nl-cat-integration">
                <div className="nl-cat-integration-body">
                  <strong>{t(`int_${p}`)}</strong>
                  <div className="nl-small nl-muted">{!available && !c.connected ? t('unavailable') : c.connected ? t('connectedAs', { account: c.accountLabel ?? '' }) : t('notConnected')}</div>
                  {reconnect && <div className="nl-small nl-error">{t('needsReconnect')}</div>}
                  {c.connected && !reconnect && (c.syncStatus === 'importing'
                    ? <div className="nl-small nl-muted" role="status">{t('importingCatalogue')}</div>
                    : <>
                      <div className="nl-small nl-muted">{c.lastSyncAt ? t('lastSync', { when: date(c.lastSyncAt, 'dateTime'), count: c.lastSyncCount ?? 0 }) : t('neverSynced')}</div>
                      {c.lastSyncAt && <div className="nl-small nl-muted">{t('syncSummary', { created: c.createdCount ?? 0, updated: c.lastSyncCount ?? 0, hidden: c.hiddenCount ?? 0, platform: NAMES[p] })}</div>}
                      <div className="nl-small nl-muted">{t(c.updates === 'webhooks' ? 'updatesWebhooks' : 'updatesHourly', { platform: NAMES[p] })}</div>
                      {c.syncStatus === 'failed' && <div className="nl-small nl-error">{t('syncFailed')}</div>}
                    </>)}
                  {c.connected && errors.length > 0 && (
                    <div className="nl-small">
                      <button type="button" className="nl-cat-linkish" aria-expanded={openErrors === p} onClick={() => setOpenErrors(openErrors === p ? null : p)}>{t('notImported', { count: errors.length })}</button>
                      {openErrors === p && <ul className="nl-cat-sync-errors">{errors.map(e => <li key={e.externalId}><strong>{e.title}</strong> — {e.error}</li>)}</ul>}
                    </div>
                  )}
                  {failed === p && <div role="alert" className="nl-error">{t('actionError')}</div>}
                </div>
                <div className="nl-cat-row">
                  {reconnect ? <Button variant="secondary" disabled={!canManage || busy(p)} onClick={() => start(p)}>{t('reconnect')}</Button>
                    : c.connected ? <Button variant="secondary" disabled={!canSync || busy(p) || c.syncStatus === 'importing'} onClick={() => run(p, 'sync')}>{action.isPending && action.variables?.provider === p && action.variables.action === 'sync' ? t('syncing') : t('syncNow')}</Button>
                    : <Button variant="secondary" disabled={!canManage || !available || busy(p)} title={canManage ? undefined : t('ownerConnects')} onClick={() => start(p)}>{t('connect')}</Button>}
                  {c.connected && canManage && <Button variant="ghost" disabled={busy(p)} onClick={() => run(p, 'disconnect')}>{t('disconnect')}</Button>}
                </div>
              </li>
            );
          })}
          <li className="nl-cat-integration">
            <div><strong>{t('intApi')}</strong><div className="nl-small nl-muted">{t('apiHint')}</div></div>
            <Link className="btn btn-secondary" to="/b/$merchantId/settings" params={{ merchantId }} search={{ tab: 'api' } as never}>{t('intApi')}</Link>
          </li>
        </ul>
      )}
      {q.data?.some(c => c.connected) && <span className="nl-hint">{t('draftsNote')}</span>}
      {shopOpen && <ShopDialog onClose={() => setShopOpen(false)} />}
    </div>
  );
}

function ShopDialog({ onClose }: { onClose: () => void }) {
  const t = useCatalogueT();
  const merchantId = useMerchantId();
  const connect = useConnectCommerce(merchantId);
  const [shop, setShop] = useState('');
  const [error, setError] = useState<string | null>(null);
  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const normalized = normalizeShop(shop);
    if (!normalized) { setError(t('shopRequired')); return; }
    setError(null);
    connect.mutate({ provider: 'shopify', shop: normalized }, {
      onError: err => setError(err instanceof ValidationError ? t('shopRequired') : t('actionError')),
    });
  };
  return (
    <Dialog open onClose={onClose} title={t('shopTitle')} actions={<>
      <Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
      <Button type="submit" form="nl-shop-form" disabled={connect.isPending}>{t('continueTo', { platform: 'Shopify' })}</Button>
    </>}>
      <form id="nl-shop-form" onSubmit={submit} noValidate>
        <Field label={t('shopLabel')} error={error}>
          <TextInput value={shop} placeholder={t('shopPlaceholder')} autoComplete="off" autoCapitalize="none" spellCheck={false} onChange={e => setShop(e.target.value)} />
        </Field>
      </form>
    </Dialog>
  );
}
