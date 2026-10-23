import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ArrowsClockwise, Buildings, Power } from '@phosphor-icons/react';
import {
  Button, ChartCard, Checkbox, DataTable, Dialog, Drawer, ErrorState, Field, PageSkeleton, Tag, TextArea, TextInput, useFormatters,
  type DataTableAction, type DataTableColumn, type DataTableTone,
} from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import {
  PARTNER_SCOPES, parseIds, partnersQuery, useAskBusinesses, usePartnerState, useRegisterPartner, useRemoveBusiness, useRotatePartner,
  type Partner,
} from './partnersApi';
import { usePartnersT } from './partnersMessages';

interface Row { id: string; name: string; client: string; scopes: string; businesses: string; keys: string; state: string; src: Partner }

const codeOf = (e: unknown) => (e instanceof ApiError && e.body && typeof e.body === 'object' ? (e.body as { code?: string }).code : undefined);

/**
 * API & webhooks › Partners (S-158): the partner API clients registered in the console, their scopes, keys (JWK Set,
 * key version) and businesses — requested until the owner approves in the Studio. Admins register, rotate, disable /
 * enable and ask or remove businesses; every change is audit-logged by the api.
 */
export function Partners() {
  const t = usePartnersT();
  const f = useFormatters();
  const { can, roleName } = useGrant();
  const allowed = can('keys');
  const q = useQuery(partnersQuery);
  const state = usePartnerState();
  const [registering, setRegistering] = useState(false);
  const [rotating, setRotating] = useState<Partner | null>(null);
  const [disabling, setDisabling] = useState<Partner | null>(null);
  const [managing, setManaging] = useState<string | null>(null);

  const rows: Row[] = (q.data ?? []).map(p => ({
    id: p.id, name: p.displayName, client: p.clientId, scopes: p.scopes.join(' '),
    businesses: t('bizCount', { approved: p.businesses.filter(b => b.state === 'approved').length, requested: p.businesses.filter(b => b.state === 'requested').length }),
    keys: t('keysV', { v: p.keyVersion, rotated: p.rotatedAt ? f.date(p.rotatedAt, 'dateTime') : 'none' }),
    state: t(p.state === 'active' ? 'st_active' : 'st_disabled'), src: p,
  }));
  const columns: DataTableColumn<Row>[] = [
    { key: 'name', label: t('c_partner'), sub: 'client', subLabel: t('c_client'), primary: true },
    { key: 'scopes', label: t('c_scopes') },
    { key: 'businesses', label: t('c_businesses') },
    { key: 'keys', label: t('c_keys') },
    { key: 'state', label: t('c_state'), type: 'tag', editable: false },
  ];
  const actions: DataTableAction<Row>[] = [
    { id: 'businesses', label: t('manage'), icon: Buildings, inline: true, bulk: false },
    ...(allowed ? [
      { id: 'rotate', label: t('rotate'), icon: ArrowsClockwise, perm: 'update', inline: true, bulk: false, when: { key: 'state', in: [t('st_active')] } } as const,
      { id: 'disable', label: t('disable'), icon: Power, perm: 'update', inline: true, bulk: false, when: { key: 'state', in: [t('st_active')] } } as const,
      { id: 'enable', label: t('enable'), icon: Power, perm: 'update', inline: true, bulk: false, when: { key: 'state', in: [t('st_disabled')] } } as const,
    ] : []),
  ];
  const onAction = (action: DataTableAction<Row>, hit: Row[]) => {
    const p = hit[0]!.src;
    if (action.id === 'businesses') setManaging(p.id);
    if (action.id === 'rotate') setRotating(p);
    if (action.id === 'disable') setDisabling(p);
    if (action.id === 'enable') state.mutate({ id: p.id, action: 'enable' });
    return false;
  };
  return (
    <ChartCard id="in-partners" title={t('title')}>
      <p className="nl-tm-sub">{t('lede')}</p>
      <div className="nl-tm-tags">
        <Button disabled={!allowed} onClick={() => setRegistering(true)}>{t('register')}</Button>
        {!allowed ? <span className="bo-muted">{t('cannot')}</span> : null}
      </div>
      {q.isPending ? <PageSkeleton kpis={0} rows={3} /> : q.isError ? <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /> : (
        <DataTable<Row> dense entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} pageSize={10} roleName={roleName} emptyText={t('empty')}
          rowTones={r => ({ state: (r.src.state === 'active' ? 'status-success' : 'status-neutral') as DataTableTone })}
          can={{ create: false, update: allowed, delete: false }} actions={actions} onAction={onAction} />
      )}
      {state.isError ? <p role="alert" className="nl-tm-error">{t('failed')}</p> : null}
      {registering ? <RegisterDialog onClose={() => setRegistering(false)} /> : null}
      {rotating ? <RotateDialog partner={rotating} onClose={() => setRotating(null)} /> : null}
      {disabling ? (
        <Dialog open onClose={() => setDisabling(null)} title={t('disableTitle', { name: disabling.displayName })}
          actions={<><Button variant="tertiary" onClick={() => setDisabling(null)}>{t('cancel')}</Button>
            <Button variant="destructive" disabled={state.isPending} onClick={() => state.mutate({ id: disabling.id, action: 'disable' }, { onSuccess: () => setDisabling(null) })}>{t('confirmDisable')}</Button></>}>
          <p className="nl-tm-sub">{t('disableBody', { minutes: Math.round(disabling.tokenTtlSeconds / 60) })}</p>
        </Dialog>
      ) : null}
      {managing && q.data ? <BusinessesDrawer partner={q.data.find(p => p.id === managing)!} allowed={allowed} onClose={() => setManaging(null)} /> : null}
    </ChartCard>
  );
}

function RegisterDialog({ onClose }: { onClose: () => void }) {
  const t = usePartnersT();
  const register = useRegisterPartner();
  const [name, setName] = useState('');
  const [display, setDisplay] = useState('');
  const [jwks, setJwks] = useState('');
  const [scopes, setScopes] = useState<string[]>(['api.read']);
  const [ttl, setTtl] = useState('15');
  const [ids, setIds] = useState('');
  const errors = register.error instanceof ValidationError ? register.error.byField() : {};
  const other = register.error && !(register.error instanceof ValidationError) ? (codeOf(register.error) === 'partner_exists' ? t('exists') : t('failed')) : null;
  const submit = () => register.mutate({
    name: name.trim(), displayName: display.trim(), jwkSetUrl: jwks.trim(), scopes,
    tokenTtlSeconds: Number.isFinite(Number(ttl)) ? Math.round(Number(ttl) * 60) : undefined, merchantIds: parseIds(ids),
  }, { onSuccess: onClose });
  return (
    <Dialog open onClose={onClose} title={t('registerTitle')}
      actions={<><Button variant="tertiary" onClick={onClose}>{t('cancel')}</Button><Button disabled={register.isPending} onClick={submit}>{t('create')}</Button></>}>
      <Field label={t('f_name')} hint={t('f_nameHint', { name: name.trim() || '…' })} error={errors.name}><TextInput value={name} maxLength={50} autoCapitalize="none" spellCheck={false} onChange={e => setName(e.target.value)} /></Field>
      <Field label={t('f_display')} error={errors.displayName}><TextInput value={display} maxLength={80} onChange={e => setDisplay(e.target.value)} /></Field>
      <Field label={t('f_jwks')} hint={t('f_jwksHint')} error={errors.jwkSetUrl}><TextInput type="url" value={jwks} onChange={e => setJwks(e.target.value)} /></Field>
      <Field label={t('f_scopes')} error={errors.scopes}>
        <div className="nl-tm-tags">
          {PARTNER_SCOPES.map(s => <Checkbox key={s} label={t(s === 'api.read' ? 'scope_api_read' : 'scope_api_write')} checked={scopes.includes(s)}
            onChange={on => setScopes(v => (on ? [...v, s] : v.filter(x => x !== s)))} />)}
        </div>
      </Field>
      <Field label={t('f_ttl')} error={errors.tokenTtlSeconds}><TextInput type="number" inputMode="numeric" min={1} max={60} value={ttl} onChange={e => setTtl(e.target.value)} /></Field>
      <Field label={t('f_businesses')} hint={t('f_businessesHint')} error={errors.merchantIds}><TextArea rows={3} value={ids} spellCheck={false} onChange={e => setIds(e.target.value)} /></Field>
      {other ? <p role="alert" className="nl-tm-error">{other}</p> : null}
    </Dialog>
  );
}

function RotateDialog({ partner, onClose }: { partner: Partner; onClose: () => void }) {
  const t = usePartnersT();
  const rotate = useRotatePartner();
  const [url, setUrl] = useState('');
  const errors = rotate.error instanceof ValidationError ? rotate.error.byField() : {};
  return (
    <Dialog open onClose={onClose} title={t('rotateTitle', { name: partner.displayName })}
      actions={<><Button variant="tertiary" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={rotate.isPending} onClick={() => rotate.mutate({ id: partner.id, jwkSetUrl: url.trim() || undefined }, { onSuccess: onClose })}>{t('confirmRotate')}</Button></>}>
      <p className="nl-tm-sub">{t('rotateBody')}</p>
      <p className="nl-tm-sub"><code>{partner.jwkSetUrl}</code></p>
      <Field label={t('f_newJwks')} error={errors.jwkSetUrl}><TextInput type="url" value={url} onChange={e => setUrl(e.target.value)} /></Field>
      {rotate.error && !(rotate.error instanceof ValidationError) ? <p role="alert" className="nl-tm-error">{t('failed')}</p> : null}
    </Dialog>
  );
}

const B_TONE = { requested: 'warning', approved: 'success', declined: 'neutral', revoked: 'neutral' } as const;

function BusinessesDrawer({ partner, allowed, onClose }: { partner: Partner; allowed: boolean; onClose: () => void }) {
  const t = usePartnersT();
  const f = useFormatters();
  const ask = useAskBusinesses();
  const remove = useRemoveBusiness();
  const [ids, setIds] = useState('');
  const errors = ask.error instanceof ValidationError ? ask.error.byField() : {};
  return (
    <Drawer open onClose={onClose} title={t('bizTitle', { name: partner.displayName })} width={520}>
      <ul className="nl-tm-list">
        {partner.businesses.map(b => (
          <li key={b.merchantId} className="nl-tm-row">
            <span><strong>{b.businessName ?? b.merchantId}</strong><span className="bo-muted"> · {f.date(b.decidedAt ?? b.requestedAt, 'dateTime')}</span></span>
            <Tag tone={B_TONE[b.state]}>{t(`b_${b.state}`)}</Tag>
            {allowed ? <Button variant="tertiary" aria-label={t('removeLabel', { business: b.businessName ?? b.merchantId })} disabled={remove.isPending}
              onClick={() => remove.mutate({ id: partner.id, merchantId: b.merchantId })}>{t('remove')}</Button> : null}
          </li>
        ))}
      </ul>
      {allowed ? (
        <form onSubmit={e => { e.preventDefault(); ask.mutate({ id: partner.id, merchantIds: parseIds(ids) }, { onSuccess: () => setIds('') }); }}>
          <Field label={t('bizAsk')} hint={t('f_businessesHint')} error={errors.merchantIds}><TextArea rows={2} value={ids} spellCheck={false} onChange={e => setIds(e.target.value)} /></Field>
          <Button type="submit" variant="secondary" disabled={ask.isPending}>{t('ask')}</Button>
        </form>
      ) : null}
      {(ask.error && !(ask.error instanceof ValidationError)) || remove.isError ? <p role="alert" className="nl-tm-error">{t('failed')}</p> : null}
    </Drawer>
  );
}
