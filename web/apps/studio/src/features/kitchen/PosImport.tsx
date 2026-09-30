import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, ErrorState, Field, Skeleton, TextInput, useFormatters } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { posConnectionsQuery, useApplyPosImport, useConnectPos, useDisconnectPos, useDiscardPosImport, usePosPreview, POS, type ItemChange, type Pos, type PosConnection, type PosPreview } from './api';
import { useKitchenT } from './messages';

const NAMES: Record<Pos, string> = { square: 'Square', clover: 'Clover', toast: 'Toast' };
const GUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Where the POS's OAuth callback (S-36) sends the browser: `/b/$id/kitchen/menu?pos=square&result=connected&menu=…`. */
export interface PosReturn { pos?: Pos; result?: 'connected' | 'denied' | 'failed' | 'expired'; menu?: string }

/**
 * "Import from POS" (design: Kitchen › Menu › Import from POS / CSV): connect Square / Clover (OAuth) or Toast
 * (restaurant GUID), then review what an import would change — new, changed, hidden, can't import — and apply it.
 */
export function PosImportPanel({ merchantId, menuId, canManage, returned }: { merchantId: string; menuId: string; canManage: boolean; returned?: PosReturn }) {
  const t = useKitchenT();
  const f = useFormatters();
  const q = useQuery(posConnectionsQuery(merchantId));
  const connect = useConnectPos(merchantId);
  const disconnect = useDisconnectPos(merchantId);
  const preview = usePosPreview(merchantId);
  const [failed, setFailed] = useState<Pos | null>(null);
  const [toastOpen, setToastOpen] = useState(false);
  const [review, setReview] = useState<PosPreview | null>(null);
  const reading = (p: Pos) => preview.isPending && preview.variables?.pos === p;

  if (review) return <PosReview merchantId={merchantId} preview={review} onClose={() => setReview(null)} />;

  const start = (p: Pos) => {
    setFailed(null);
    if (p === 'toast') { setToastOpen(true); return; }
    connect.mutate({ pos: p, menuId }, { onError: () => setFailed(p) });
  };
  const load = (p: Pos) => {
    setFailed(null);
    preview.mutate({ menuId, pos: p }, { onSuccess: setReview, onError: () => setFailed(p) });
  };
  return (
    <div className="nl-k-stack">
      {returned?.pos && returned.result ? <Alert tone={returned.result === 'connected' ? 'info' : 'error'} role="status">{t(`ret_${returned.result}`, { pos: NAMES[returned.pos] })}</Alert> : null}
      {q.isPending ? <Skeleton height={140} /> : q.isError ? <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /> : (
        <ul className="nl-k-pos-list">
          {POS.map(p => {
            const c: PosConnection = q.data.find(x => x.provider === p) ?? { provider: p, kind: p === 'toast' ? 'restaurant_id' : 'oauth', available: false, state: 'disconnected' };
            const connected = c.state === 'connected';
            return (
              <li key={p} className="nl-k-pos">
                <div className="nl-k-pos-body">
                  <strong>{t(`pos_${p}`)}</strong>
                  <span className="nl-k-muted">{!c.available && c.state === 'disconnected' ? t('posUnavailable') : c.state === 'disconnected' ? t('posNotConnected') : t('posConnectedAs', { account: c.accountLabel ?? '' })}</span>
                  {c.state === 'reconnect' ? <span className="nl-error">{t('posReconnectNote')}</span> : null}
                  {connected && c.lastImportAt ? <span className="nl-k-muted">{t('posLastImport', { when: f.date(c.lastImportAt, 'dateTime') })}</span> : null}
                  {failed === p ? <span role="alert" className="nl-error">{t('posError')}</span> : null}
                </div>
                <div className="nl-k-actions">
                  {connected ? <button type="button" className="btn btn-primary" disabled={preview.isPending} onClick={() => load(p)}>{reading(p) ? t('posReading') : t('posImport')}</button>
                    : <button type="button" className="btn btn-secondary" disabled={!canManage || !c.available || connect.isPending} title={canManage ? undefined : t('posOwnerConnects')} onClick={() => start(p)}>{c.state === 'reconnect' ? t('posReconnect') : t('posConnect')}</button>}
                  {c.state !== 'disconnected' && canManage ? <button type="button" className="btn btn-ghost" disabled={disconnect.isPending} onClick={() => disconnect.mutate(p)}>{t('posDisconnect')}</button> : null}
                </div>
              </li>
            );
          })}
        </ul>
      )}
      {toastOpen ? <ToastLink merchantId={merchantId} onDone={() => setToastOpen(false)} /> : null}
      <p className="nl-k-muted">{t('allergenNote')}</p>
    </div>
  );
}

function ToastLink({ merchantId, onDone }: { merchantId: string; onDone: () => void }) {
  const t = useKitchenT();
  const connect = useConnectPos(merchantId);
  const [guid, setGuid] = useState('');
  const [error, setError] = useState<string | null>(null);
  const submit = () => {
    if (!GUID.test(guid.trim())) { setError(t('v_toastGuid')); return; }
    setError(null);
    connect.mutate({ pos: 'toast', restaurantId: guid.trim() }, {
      onSuccess: onDone,
      onError: e => setError(e instanceof ValidationError ? (e.errors[0]?.message ?? t('posError')) : t('posError')),
    });
  };
  return (
    <div className="nl-k-panel">
      <Field label={t('toastGuid')} hint={t('toastGuidHint')} error={error}>
        <TextInput value={guid} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" autoComplete="off" spellCheck={false} onChange={e => setGuid(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') submit(); }} />
      </Field>
      <div className="nl-k-actions"><button type="button" className="btn btn-ghost" onClick={onDone}>{t('cancel')}</button><button type="button" className="btn btn-primary" disabled={connect.isPending} onClick={submit}>{t('toastLink')}</button></div>
    </div>
  );
}

function PosReview({ merchantId, preview, onClose }: { merchantId: string; preview: PosPreview; onClose: () => void }) {
  const t = useKitchenT();
  const f = useFormatters();
  const apply = useApplyPosImport(merchantId);
  const discard = useDiscardPosImport(merchantId);
  const c = preview.diff.counts;
  const pos = NAMES[preview.provider];
  const changes = c.newItems + c.changedItems + c.removedItems + c.newGroups + c.changedGroups + c.newSections;
  const shown = preview.diff.items.filter(i => i.change !== 'unchanged');
  const newSections = preview.diff.sections.filter(s => s.change === 'new').map(s => s.name);
  if (apply.isSuccess) {
    return (
      <div className="nl-k-stack">
        <Alert tone="info" role="status">{t('applied', { created: apply.data.itemsCreated, updated: apply.data.itemsUpdated, hidden: apply.data.itemsHidden })}</Alert>
        <p className="nl-k-muted">{t('allergenNote')}</p>
      </div>
    );
  }
  const detail = (i: ItemChange) => {
    if (i.change === 'problem') return i.problem ?? '';
    if (i.change === 'changed') {
      const fields = i.fields.map(x => t(`df_${x}` as 'df_name')).join(', ');
      return i.fields.includes('price') && i.priceCents != null && i.previousPriceCents != null ? `${fields} · ${t('priceChange', { from: f.money(i.previousPriceCents), to: f.money(i.priceCents) })}` : fields;
    }
    return i.priceCents != null ? f.money(i.priceCents) : '';
  };
  return (
    <section className="nl-k-stack" aria-label={t('previewTitle', { pos })}>
      <h3 className="nl-k-h3">{t('previewTitle', { pos })}</h3>
      <p>{t('previewSummary', { new: c.newItems, changed: c.changedItems, removed: c.removedItems, unchanged: c.unchangedItems, pos })}</p>
      {c.newGroups + c.changedGroups > 0 ? <p className="nl-k-muted">{t('previewGroups', { new: c.newGroups, changed: c.changedGroups })}</p> : null}
      {newSections.length ? <p className="nl-k-muted">{t('previewSections', { list: newSections.join(', ') })}</p> : null}
      {shown.length ? (
        <ul className="nl-k-diff">
          {shown.map(i => (
            <li key={i.externalId} data-change={i.change}>
              <span className={`tag ${i.change === 'problem' || i.change === 'removed' ? 'tag-accent-2' : i.change === 'new' ? 'tag-accent' : 'tag-neutral'} nl-k-small-tag`}>{t(`ch_${i.change}`)}</span>
              <strong>{i.name}</strong>{i.section ? <span className="nl-k-muted"> · {i.section}</span> : null}
              <span className="nl-k-diff-detail">{detail(i)}</span>
            </li>
          ))}
        </ul>
      ) : null}
      <p className="nl-k-muted">{t('allergenNote')}</p>
      {apply.isError || discard.isError ? <div role="alert" className="nl-error">{apply.error?.message || t('posError')}</div> : null}
      <div className="nl-k-actions">
        <button type="button" className="btn btn-ghost" disabled={discard.isPending} onClick={() => discard.mutate(preview.id, { onSuccess: onClose })}>{t('discard')}</button>
        <button type="button" className="btn btn-primary" disabled={changes === 0 || apply.isPending} onClick={() => apply.mutate(preview.id)}>{changes === 0 ? t('applyNothing') : t('applyGo', { n: changes })}</button>
      </div>
    </section>
  );
}
