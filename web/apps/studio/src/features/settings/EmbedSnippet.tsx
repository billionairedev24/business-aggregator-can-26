import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Dialog, Field, Skeleton, TextArea } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useMerchantId, useRole } from '../shell/api';
import { embedSnippet, publishableKeyQuery, useEmbedOrigins, useRollPublishableKey } from './api';
import { useSettingsT } from './messages';
import { localizeServerErrors } from './validation';

/**
 * S-76: the website embed snippet with the business's real publishable key (Settings › API "Embed your store" and the
 * Business page's "Embed code" dialog). The owner creates or replaces the key and, with `manageSites`, limits the
 * websites it answers on; other roles copy the snippet.
 */
export function EmbedSnippet({ slug, manageSites = false }: { slug: string; manageSites?: boolean }) {
  const t = useSettingsT();
  const merchantId = useMerchantId();
  const owner = useRole() === 'owner';
  const key = useQuery(publishableKeyQuery(merchantId));
  const roll = useRollPublishableKey(merchantId);
  const [confirming, setConfirming] = useState(false);
  const [copied, setCopied] = useState(false);

  if (key.isPending) return <Skeleton height={72} />;
  if (key.isError) return <Alert tone="error">{t('loadError')}</Alert>;
  if (!key.data) {
    return (
      <div className="nl-set-embed">
        <p className="nl-muted">{owner ? t('embedNoKey') : t('embedOwnerOnly')}</p>
        {owner && <Button variant="secondary" disabled={roll.isPending} onClick={() => roll.mutate()}>{t('embedCreate')}</Button>}
        {roll.isError && <Alert tone="error">{t('actionFailed')}</Alert>}
      </div>
    );
  }
  const code = embedSnippet(key.data, slug);
  return (
    <div className="nl-set-embed">
      <p className="nl-muted">{t('embedHelp')}</p>
      <pre className="nl-set-code"><code>{code}</code></pre>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <Button variant="secondary" onClick={() => { void navigator.clipboard?.writeText(code).then(() => setCopied(true)); }}>{copied ? t('copied') : t('copy')}</Button>
        {owner && <Button variant="ghost" onClick={() => setConfirming(true)}>{t('embedRoll')}</Button>}
      </div>
      {manageSites && <AllowedSites origins={key.data.allowedOrigins} owner={owner} />}
      <Dialog open={confirming} onClose={() => setConfirming(false)} title={t('embedRollTitle')} actions={<>
        <Button variant="ghost" onClick={() => setConfirming(false)}>{t('cancel')}</Button>
        <Button variant="primary" disabled={roll.isPending} onClick={() => roll.mutate(undefined, { onSuccess: () => { setConfirming(false); setCopied(false); } })}>{t('embedRollConfirm')}</Button>
      </>}>
        <p>{t('embedRollBody')}</p>
        {roll.isError && <Alert tone="error">{t('actionFailed')}</Alert>}
      </Dialog>
    </div>
  );
}

function AllowedSites({ origins, owner }: { origins: string[]; owner: boolean }) {
  const t = useSettingsT();
  const merchantId = useMerchantId();
  const save = useEmbedOrigins(merchantId);
  const [text, setText] = useState(origins.join('\n'));
  useEffect(() => setText(origins.join('\n')), [origins]);
  const error = save.error instanceof ValidationError ? localizeServerErrors(save.error.errors, t).allowedOrigins : undefined;
  return (
    <>
      <Field label={t('embedSites')} hint={t('embedSitesHint')} error={error}>
        <TextArea rows={3} value={text} disabled={!owner} onChange={e => setText(e.target.value)} />
      </Field>
      {owner && (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <Button variant="secondary" disabled={save.isPending} onClick={() => save.mutate(text.split(/[\s,]+/).filter(Boolean))}>{t('embedSitesSave')}</Button>
          {save.isSuccess && <span role="status" className="nl-small nl-muted">{t('embedSitesSaved')}</span>}
        </div>
      )}
      {save.isError && !error && <Alert tone="error">{t('saveError')}</Alert>}
    </>
  );
}
