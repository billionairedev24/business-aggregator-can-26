import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Alert, Button, Checkbox, DataTable, Dialog, ErrorState, Field, PageSkeleton, Tag, TextInput, type DataTableColumn } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { syncQuery } from '../availability/api';
import { integrationsQuery } from '../catalogue/api';
import { useMerchant, useMerchantId, useRole } from '../shell/api';
import { screenHref } from '../shell/nav';
import { apiKeysQuery, businessQuery, developerOptionsQuery, useAddWebhook, useIssueKey, useRemoveWebhook, useRevokeKey, useRotateWebhook, webhooksQuery, type ApiKey, type Webhook } from './api';
import { useSettingsT, type SettingsT } from './messages';
import { keySchema, localizeServerErrors, webhookErrors } from './validation';

export function ago(iso: string | null | undefined, t: SettingsT, now = Date.now()): string {
  if (!iso) return t('never');
  const min = Math.max(0, Math.round((now - new Date(iso).getTime()) / 60_000));
  if (min < 1) return t('justNow');
  if (min < 60) return t('minAgo', { n: min });
  if (min < 60 * 24) return t('hAgo', { n: Math.round(min / 60) });
  return t('dAgo', { n: Math.round(min / 1440) });
}

interface KeyRow { id: string; name: string; prefix: string; scopes: string; used: string }
type Secret = { title: string; note: string; value: string };

/** API & integrations (design st.api): API keys DataTable, webhooks, embed snippet, integrations. */
export function ApiTab() {
  const t = useSettingsT();
  const merchantId = useMerchantId();
  const owner = useRole() === 'owner';
  const keys = useQuery(apiKeysQuery(merchantId));
  const revoke = useRevokeKey(merchantId);
  const [issuing, setIssuing] = useState<{ name: string; scopes: string[] } | null>(null);
  const [secret, setSecret] = useState<Secret | null>(null);
  const rows: KeyRow[] = (keys.data ?? []).map((k: ApiKey) => ({ id: k.id, name: k.name, prefix: `${k.prefix}…`, scopes: k.scopes.join(' '), used: ago(k.lastUsedAt, t) }));
  const columns: DataTableColumn<KeyRow>[] = [
    { key: 'name', label: t('colName'), sub: 'prefix', primary: true },
    { key: 'scopes', label: t('colScopes') },
    { key: 'used', label: t('colLastUsed') },
  ];
  const role = useRole();
  return (
    <div className="nl-set-api">
      <div>
        <h3 className="nl-set-h3">{t('apiKeys')}</h3>
        <DataTable<KeyRow> entity={t('keyEntity')} plural={t('keyPlural')} columns={columns} rows={rows} loading={keys.isPending}
          error={keys.isError ? t('loadError') : null} onRetry={() => void keys.refetch()} emptyText={t('keysEmpty')}
          roleName={t(`role_${role}` as Parameters<SettingsT>[0])} can={{ create: owner, update: false, delete: owner, export: true }} createLabel={t('issueKey')}
          onCreateClick={() => setIssuing({ name: '', scopes: [] })}
          onDelete={async list => { for (const r of list) await revoke.mutateAsync(r.id); }} />
        {owner && <Button variant="secondary" className="nl-set-below" onClick={() => setIssuing({ name: '', scopes: [] })}>{t('issueKey')}</Button>}
        <Webhooks owner={owner} onSecret={setSecret} />
      </div>
      <div>
        <Embed />
        <Integrations keys={keys.data ?? []} owner={owner} onQuickBooks={() => setIssuing({ name: 'QuickBooks sync', scopes: ['payouts:read', 'orders:read'] })} />
      </div>
      {issuing && <IssueKeyDialog initial={issuing} onClose={() => setIssuing(null)} onIssued={value => { setIssuing(null); setSecret({ title: t('secretTitle'), note: t('secretNote'), value }); }} />}
      {secret && <SecretDialog secret={secret} onClose={() => setSecret(null)} />}
    </div>
  );
}

function Webhooks({ owner, onSecret }: { owner: boolean; onSecret: (s: Secret) => void }) {
  const t = useSettingsT();
  const merchantId = useMerchantId();
  const q = useQuery(webhooksQuery(merchantId));
  const rotate = useRotateWebhook(merchantId);
  const remove = useRemoveWebhook(merchantId);
  const [adding, setAdding] = useState(false);
  const delivery = (w: Webhook) => (w.lastStatus == null ? t('noDelivery') : t('lastDelivery', { status: `${w.lastStatus} ${w.lastStatus < 300 ? t('ok') : t('failed')}` }));
  return (
    <section aria-labelledby="set-hooks">
      <h3 id="set-hooks" className="nl-set-h3 nl-set-gap">{t('webhooks')}</h3>
      {q.isPending ? <PageSkeleton kpis={0} rows={2} /> : q.isError ? <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /> : q.data.length === 0 ? <p className="nl-small nl-muted">{t('webhooksEmpty')}</p> : (
        <ul className="nl-set-hooks">
          {q.data.map(w => (
            <li key={w.id}>
              <span className="nl-set-hookurl">{w.url}</span>
              <span className="nl-set-sub">{t('webhookMeta', { events: w.events.join(' · '), signature: w.signature, delivery: delivery(w) })}</span>
              {owner && (
                <span className="nl-set-hookactions">
                  <Button variant="ghost" aria-label={t('rotateLabel', { url: w.url })} disabled={rotate.isPending}
                    onClick={() => rotate.mutate(w.id, { onSuccess: r => onSecret({ title: t('signingTitle'), note: t('signingNote'), value: r.secret }) })}>{t('rotate')}</Button>
                  <Button variant="ghost" aria-label={t('removeLabel', { url: w.url })} disabled={remove.isPending} onClick={() => remove.mutate(w.id)}>{t('remove')}</Button>
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {(rotate.isError || remove.isError) && <p role="alert" className="nl-error">{t('actionFailed')}</p>}
      {owner && <Button variant="secondary" className="nl-set-below" onClick={() => setAdding(true)}>{t('addWebhook')}</Button>}
      {adding && <WebhookDialog onClose={() => setAdding(false)} onAdded={value => { setAdding(false); onSecret({ title: t('signingTitle'), note: t('signingNote'), value }); }} />}
    </section>
  );
}

function Embed() {
  const t = useSettingsT();
  const merchantId = useMerchantId();
  const business = useQuery(businessQuery(merchantId));
  const slug = business.data?.storeSlug ?? 'your-store';
  return (
    <section aria-labelledby="set-embed">
      <h3 id="set-embed" className="nl-set-h3">{t('embedTitle')}</h3>
      <pre className="nl-set-code">{`<script src="https://cdn.northline.ca/embed.js"\n  data-store="${slug}"\n  data-key="pk_live_…"></script>`}</pre>
    </section>
  );
}

function Integrations({ keys, owner, onQuickBooks }: { keys: ApiKey[]; owner: boolean; onQuickBooks: () => void }) {
  const t = useSettingsT();
  const merchantId = useMerchantId();
  const merchant = useMerchant();
  const navigate = useNavigate();
  const provider = merchant.type === 'provider' || merchant.type === 'both';
  const kitchen = merchant.type === 'kitchen';
  const sync = useQuery({ ...syncQuery(merchantId), enabled: provider });
  const commerce = useQuery({ ...integrationsQuery(merchantId), enabled: !kitchen });
  const googleCal = sync.data?.calendars.find(c => c.provider === 'google');
  const google = googleCal?.connected && googleCal.state !== 'reconnect';
  const toSync = () => void navigate({ to: screenHref(merchantId, 'availability') });
  const shop = commerce.data?.some(c => (c.provider === 'shopify' || c.provider === 'square') && c.connected);
  const quickbooks = keys.some(k => /quickbooks/i.test(k.name));
  const row = (label: string, connected: boolean | undefined, onConnect: () => void) => (
    <li className="nl-set-row"><span>{label}</span>{connected ? <Tag tone="accent">{t('connected')}</Tag> : owner ? <Button variant="ghost" onClick={onConnect}>{t('connect')}</Button> : <Tag tone="neutral">{t('notConnected')}</Tag>}</li>
  );
  return (
    <section aria-labelledby="set-int">
      <h3 id="set-int" className="nl-set-h3 nl-set-gap">{t('integrations')}</h3>
      <ul className="nl-set-rows">
        {provider && (googleCal?.state === 'reconnect'
          ? <li className="nl-set-row"><span>{t('int_google')}</span><Button variant="ghost" onClick={toSync}>{t('int_reconnect')}</Button></li>
          : row(t('int_google'), google, toSync))}
        {row(t('int_quickbooks'), quickbooks, onQuickBooks)}
        {!kitchen && row(t('int_commerce'), shop, () => void navigate({ to: `${screenHref(merchantId, 'products')}/bulk` }))}
      </ul>
    </section>
  );
}

function IssueKeyDialog({ initial, onClose, onIssued }: { initial: { name: string; scopes: string[] }; onClose: () => void; onIssued: (secret: string) => void }) {
  const t = useSettingsT();
  const merchantId = useMerchantId();
  const options = useQuery(developerOptionsQuery(merchantId));
  const issue = useIssueKey(merchantId);
  const [name, setName] = useState(initial.name);
  const [scopes, setScopes] = useState<string[]>(initial.scopes);
  const [submitted, setSubmitted] = useState(false);
  const [touched, setTouched] = useState(false);
  const [server, setServer] = useState<Record<string, string>>({});
  const parsed = keySchema(t).safeParse({ name, scopes });
  const errors: Record<string, string | undefined> = {};
  if (!parsed.success) for (const i of parsed.error.issues) errors[String(i.path[0])] ??= i.message;
  const shown = (k: 'name' | 'scopes') => server[k] ?? ((submitted || (k === 'name' && touched)) ? errors[k] : undefined);
  const count = Object.values(errors).filter(Boolean).length;
  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    if (count) return;
    issue.mutate({ name: name.trim(), scopes }, {
      onSuccess: r => onIssued(r.secret),
      onError: err => { if (err instanceof ValidationError) setServer(localizeServerErrors(err.errors, t)); },
    });
  };
  return (
    <Dialog open onClose={onClose} title={t('issueTitle')}>
      <form onSubmit={submit} noValidate className="nl-set-dialogform">
        {submitted && count > 0 && <Alert tone="error" role="alert">{t('attention', { n: count })}</Alert>}
        <Field label={t('keyName')} error={shown('name')}>
          <TextInput value={name} placeholder={t('keyNamePh')} onChange={e => { setName(e.target.value); setServer({}); }} onBlur={() => setTouched(true)} />
        </Field>
        <fieldset className="nl-set-checks" aria-describedby={shown('scopes') ? 'key-scopes-err' : undefined}>
          <legend className="nl-label">{t('scopes')}</legend>
          {(options.data?.scopes ?? []).map(s => (
            <Checkbox key={s} label={<code>{s}</code>} checked={scopes.includes(s)} onChange={on => { setScopes(x => (on ? [...x, s] : x.filter(y => y !== s))); setServer({}); }} />
          ))}
          {shown('scopes') ? <div id="key-scopes-err" role="alert" className="nl-error">{shown('scopes')}</div> : null}
        </fieldset>
        {issue.isError && !(issue.error instanceof ValidationError) && <p role="alert" className="nl-error">{t('actionFailed')}</p>}
        <div className="nl-set-dialogactions">
          <Button type="button" variant="ghost" onClick={onClose}>{t('cancel')}</Button>
          <Button type="submit" disabled={issue.isPending}>{issue.isPending ? t('issuing') : t('issue')}</Button>
        </div>
      </form>
    </Dialog>
  );
}

function WebhookDialog({ onClose, onAdded }: { onClose: () => void; onAdded: (secret: string) => void }) {
  const t = useSettingsT();
  const merchantId = useMerchantId();
  const options = useQuery(developerOptionsQuery(merchantId));
  const add = useAddWebhook(merchantId);
  const [url, setUrl] = useState('');
  const [events, setEvents] = useState<string[]>([]);
  const [submitted, setSubmitted] = useState(false);
  const [touched, setTouched] = useState(false);
  const [server, setServer] = useState<Record<string, string>>({});
  const errors = webhookErrors({ url, events }, t);
  const shown = (k: 'url' | 'events') => server[k] ?? ((submitted || (k === 'url' && touched)) ? errors[k] : undefined);
  const count = Object.values(errors).filter(Boolean).length;
  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    if (count) return;
    add.mutate({ url: url.trim(), events }, {
      onSuccess: r => onAdded(r.secret),
      onError: err => { if (err instanceof ValidationError) setServer(localizeServerErrors(err.errors, t)); },
    });
  };
  return (
    <Dialog open onClose={onClose} title={t('webhookTitle')}>
      <form onSubmit={submit} noValidate className="nl-set-dialogform">
        {submitted && count > 0 && <Alert tone="error" role="alert">{t('attention', { n: count })}</Alert>}
        <Field label={t('url')} error={shown('url')}>
          <TextInput type="url" value={url} placeholder={t('urlPh')} onChange={e => { setUrl(e.target.value); setServer({}); }} onBlur={() => setTouched(true)} />
        </Field>
        <fieldset className="nl-set-checks">
          <legend className="nl-label">{t('events')}</legend>
          {(options.data?.events ?? []).map(ev => (
            <Checkbox key={ev} label={<code>{ev}</code>} checked={events.includes(ev)} onChange={on => { setEvents(x => (on ? [...x, ev] : x.filter(y => y !== ev))); setServer({}); }} />
          ))}
          {shown('events') ? <div role="alert" className="nl-error">{shown('events')}</div> : null}
        </fieldset>
        {add.isError && !(add.error instanceof ValidationError) && <p role="alert" className="nl-error">{t('actionFailed')}</p>}
        <div className="nl-set-dialogactions">
          <Button type="button" variant="ghost" onClick={onClose}>{t('cancel')}</Button>
          <Button type="submit" disabled={add.isPending}>{t('add2')}</Button>
        </div>
      </form>
    </Dialog>
  );
}

function SecretDialog({ secret, onClose }: { secret: Secret; onClose: () => void }) {
  const t = useSettingsT();
  const [copied, setCopied] = useState(false);
  return (
    <Dialog open onClose={onClose} title={secret.title} actions={<Button onClick={onClose}>{t('done')}</Button>}>
      <p className="nl-small">{secret.note}</p>
      <div className="nl-set-secret">
        <code>{secret.value}</code>
        <Button variant="secondary" onClick={() => { void navigator.clipboard?.writeText(secret.value); setCopied(true); }}>{copied ? t('copied') : t('copy')}</Button>
      </div>
    </Dialog>
  );
}
