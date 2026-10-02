import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Checkbox, DataTable, Dialog, ErrorState, Field, PageSkeleton, TextInput, useFormatters, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { keysQuery, SCOPES, useIssueKey, useRevokeKey, type Key } from './api';
import { useIntegrationsT } from './messages';
import '../team/team.css';

interface Row { id: string; owner: string; scopes: string; used: string; status: string; src: Key }

/**
 * API & webhooks (S-96, design 03 `api`; admin): every business's API keys ("Partner keys"). Staff issue a key for a
 * business (shown once) and revoke one; both land in that business's audit log with the staff member as actor.
 */
export function IntegrationsScreen() {
  const t = useIntegrationsT();
  const query = useQuery(keysQuery);
  if (query.isPending) return <PageSkeleton kpis={0} rows={6} />;
  if (query.isError) return <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />;
  return <KeysView keys={query.data} />;
}

function KeysView({ keys }: { keys: Key[] }) {
  const t = useIntegrationsT();
  const fmt = useFormatters();
  const { can, roleName } = useGrant();
  const allowed = can('keys');
  const [issuing, setIssuing] = useState(false);
  const [revoking, setRevoking] = useState<Key | null>(null);
  const rows: Row[] = keys.map(k => ({
    id: k.id, owner: t('owner', { business: k.businessName ?? k.merchantId, name: k.name }), scopes: k.scopes.join(' '),
    used: k.lastUsedAt ? fmt.date(k.lastUsedAt, 'dateTime') : t('never'), status: k.revokedAt ? t('revoked') : t('active'), src: k,
  }));
  const columns: DataTableColumn<Row>[] = [
    { key: 'owner', label: t('c_owner'), primary: true }, { key: 'scopes', label: t('c_scopes') }, { key: 'used', label: t('c_used') }, { key: 'status', label: t('c_status'), type: 'tag' },
  ];
  return (
    <div>
      <span className="nl-tm-kicker">{t('kicker')}</span>
      <h1 className="nl-tm-title">{t('title')}</h1>
      <p className="nl-tm-sub">{t('lede')}</p>
      <h2 className="nl-tm-h2 nl-tm-gap">{t('keysTitle')}</h2>
      <DataTable<Row> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} pageSize={10} roleName={roleName}
        rowTones={r => ({ status: (r.src.revokedAt ? 'tag-neutral' : 'tag-accent') as DataTableTone })} can={{ create: false, update: allowed, delete: false }}
        actions={[{ id: 'revoke', label: t('revoke'), perm: 'update', inline: true, bulk: false, when: { key: 'status', in: [t('active')] } }]}
        onAction={(action, picked) => { if (action.id === 'revoke' && picked[0]) setRevoking(picked[0].src); return false; }} />
      <div className="nl-tm-actions">
        <Button variant="secondary" disabled={!allowed} onClick={() => setIssuing(true)}>{t('issue')}</Button>
        {!allowed ? <span className="nl-tm-sub">{t('cannot')}</span> : null}
      </div>
      {issuing ? <IssueDialog onClose={() => setIssuing(false)} /> : null}
      {revoking ? <RevokeDialog keyRow={revoking} onClose={() => setRevoking(null)} /> : null}
    </div>
  );
}

function IssueDialog({ onClose }: { onClose: () => void }) {
  const t = useIntegrationsT();
  const issue = useIssueKey();
  const [merchantId, setMerchantId] = useState('');
  const [name, setName] = useState('');
  const [scopes, setScopes] = useState<string[]>([]);
  const errors = issue.error instanceof ValidationError ? issue.error.byField() : {};
  if (issue.data) {
    return (
      <Dialog open onClose={onClose} title={t('secretTitle')} actions={<Button onClick={onClose}>{t('done')}</Button>}>
        <p className="nl-tm-sub">{t('secretNote')}</p>
        <TextInput readOnly aria-label={t('secretTitle')} value={issue.data.secret} onFocus={e => e.currentTarget.select()} />
      </Dialog>
    );
  }
  return (
    <Dialog open onClose={onClose} title={t('issueTitle')}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button><Button disabled={issue.isPending} onClick={() => issue.mutate({ merchantId: merchantId.trim(), name, scopes })}>{t('create')}</Button></>}>
      <Field label={t('f_business')} error={errors.merchantId}><TextInput value={merchantId} onChange={e => setMerchantId(e.target.value)} /></Field>
      <Field label={t('f_name')} error={errors.name}><TextInput value={name} maxLength={60} onChange={e => setName(e.target.value)} /></Field>
      <Field label={t('f_scopes')} error={errors.scopes}>
        <div className="nl-tm-tags">
          {SCOPES.map(s => <Checkbox key={s} label={s} checked={scopes.includes(s)} onChange={on => setScopes(v => (on ? [...v, s] : v.filter(x => x !== s)))} />)}
        </div>
      </Field>
      {issue.error && !(issue.error instanceof ValidationError) ? <p role="alert" className="nl-tm-error">{issue.error instanceof ApiError ? issue.error.message : String(issue.error)}</p> : null}
    </Dialog>
  );
}

function RevokeDialog({ keyRow, onClose }: { keyRow: Key; onClose: () => void }) {
  const t = useIntegrationsT();
  const revoke = useRevokeKey();
  return (
    <Dialog open onClose={onClose} title={t('revokeTitle', { name: keyRow.name })}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button><Button disabled={revoke.isPending} onClick={() => revoke.mutate(keyRow.id, { onSuccess: onClose })}>{t('confirmRevoke')}</Button></>}>
      <p className="nl-tm-sub">{t('revokeNote')}</p>
      {revoke.error ? <p role="alert" className="nl-tm-error">{revoke.error instanceof ApiError ? revoke.error.message : String(revoke.error)}</p> : null}
    </Dialog>
  );
}
