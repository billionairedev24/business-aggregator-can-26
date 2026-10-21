import { useQuery } from '@tanstack/react-query';
import { Button, ErrorState, PageSkeleton, Tag, useFormatters, type TagTone } from '@northline/ui';
import { useMerchantId, useRole } from '../shell/api';
import { consentsQuery, useDecideConsent, type Consent } from './integrationsApi';
import { useIntegrationsT } from './integrationsMessages';

const TONE: Record<Consent['state'], TagTone> = { requested: 'highlight', approved: 'accent', declined: 'neutral', revoked: 'neutral' };

/**
 * S-158 partner access (Settings › API & integrations): the partners {brand} registered that asked to act for this
 * business through the API, what they may do, and the owner's answer — approve or decline a request, revoke an
 * approval. Everyone on the team sees it; only the owner answers.
 */
export function PartnerAccess() {
  const t = useIntegrationsT();
  const f = useFormatters();
  const merchantId = useMerchantId();
  const owner = useRole() === 'owner';
  const q = useQuery(consentsQuery(merchantId));
  const decide = useDecideConsent(merchantId);
  const scopes = (c: Consent) => c.scopes.map(s => t(s === 'api.write' ? 'scope_api_write' : 'scope_api_read')).join(', ');
  return (
    <section aria-labelledby="set-partners">
      <h2 id="set-partners" className="nl-set-h3 nl-set-gap">{t('partnersTitle')}</h2>
      <p className="nl-small nl-muted">{t('partnersLede')}</p>
      {q.isPending ? <PageSkeleton kpis={0} rows={2} /> : q.isError ? <ErrorState message={t('partnersError')} onRetry={() => void q.refetch()} />
        : q.data.length === 0 ? <p className="nl-small nl-muted">{t('partnersEmpty')}</p> : (
          <ul className="nl-set-hooks">
            {q.data.map(c => {
              const busy = decide.isPending && decide.variables?.partnerId === c.partnerId;
              return (
                <li key={c.partnerId}>
                  <span className="nl-set-row">
                    <strong>{c.displayName}</strong>
                    <Tag tone={TONE[c.state]}>{t(`st_${c.state}`)}</Tag>
                  </span>
                  <span className="nl-set-sub"><code>{c.clientId}</code> · {t('scopes', { scopes: scopes(c) })}</span>
                  <span className="nl-set-sub">
                    {c.decidedAt ? t('decidedOn', { state: t(`st_${c.state}`), date: f.date(c.decidedAt, 'full') }) : t('requestedOn', { date: f.date(c.requestedAt, 'full') })}
                    {c.partnerState === 'disabled' ? ` · ${t('partnerDisabled')}` : ''}
                  </span>
                  {owner ? (
                    <span className="nl-set-hookactions">
                      {c.state === 'requested' && <>
                        <Button variant="secondary" aria-label={t('approveLabel', { name: c.displayName })} disabled={busy} onClick={() => decide.mutate({ partnerId: c.partnerId, decision: 'approve' })}>{t('approve')}</Button>
                        <Button variant="ghost" aria-label={t('declineLabel', { name: c.displayName })} disabled={busy} onClick={() => decide.mutate({ partnerId: c.partnerId, decision: 'decline' })}>{t('decline')}</Button>
                      </>}
                      {c.state === 'approved' && <Button variant="ghost" aria-label={t('revokeLabel', { name: c.displayName })} disabled={busy} onClick={() => decide.mutate({ partnerId: c.partnerId, decision: 'revoke' })}>{t('revoke')}</Button>}
                    </span>
                  ) : c.state === 'requested' ? <span className="nl-set-sub">{t('ownerAnswers')}</span> : null}
                </li>
              );
            })}
          </ul>
        )}
      {decide.isError && <p role="alert" className="nl-error">{t('actionError')}</p>}
    </section>
  );
}
