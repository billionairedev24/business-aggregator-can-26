import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Dialog, ErrorState, Field, Skeleton, TextInput } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { ownerIdentityQuery, useStartOwnerSession, type OwnerIdentity } from './api';
import { useOnboardingT, type OnboardingT } from './messages';

export interface IdentityDialogProps {
  merchantId: string;
  title: string;
  onClose: () => void;
  /** Where the browser goes for Stripe's hosted flow (tests replace it). */
  navigate?: (url: string) => void;
}

const REASONS = ['document_expired', 'document_unverified_other', 'document_type_not_supported', 'selfie_face_mismatch', 'selfie_unverified_other', 'consent_declined', 'under_supported_age'] as const;

/** "Identity (Stripe KYC)" (design 02 checkDefs: "Government ID + selfie for every owner ≥ 25%"): one row per owner. */
export function IdentityDialog({ merchantId, title, onClose, navigate = url => window.location.assign(url) }: IdentityDialogProps) {
  const t = useOnboardingT();
  const owners = useQuery(ownerIdentityQuery(merchantId));
  const start = useStartOwnerSession(merchantId);
  const [emailFor, setEmailFor] = useState<string | null>(null);
  const [email, setEmail] = useState('');
  const [sentTo, setSentTo] = useState<string | null>(null);
  const someoneIsYou = owners.data?.some(o => o.you) ?? false;
  const fieldError = start.error instanceof ValidationError ? start.error.byField().email ?? start.error.errors[0]?.message : undefined;
  const otherError = start.isError && !(start.error instanceof ValidationError) ? t('idStartError') : undefined;

  const self = (o: OwnerIdentity) => {
    setSentTo(null);
    start.mutate({ principalId: o.principalId, delivery: 'self' }, { onSuccess: r => { if (r.url) navigate(r.url); } });
  };
  const send = (o: OwnerIdentity) => {
    setSentTo(null);
    start.mutate({ principalId: o.principalId, delivery: 'email', email }, { onSuccess: r => { setSentTo(r.owner.emailMasked ?? email); setEmailFor(null); setEmail(''); } });
  };

  return (
    <Dialog open onClose={onClose} title={title} width={620} actions={<button type="button" className="btn btn-ghost" onClick={onClose}>{t('idClose')}</button>}>
      <p className="nl-ob-note" style={{ marginTop: 0 }}>{t('idIntro')}</p>
      {owners.isPending ? <Skeleton height={120} /> : owners.isError ? <ErrorState message={t('loadError')} onRetry={() => void owners.refetch()} /> : (
        owners.data.length === 0 ? <p className="nl-ob-note">{t('idNoOwners')}</p> : (
          <ul className="nl-ob-idlist">
            {owners.data.map(o => {
              const open = o.status !== 'verified' && o.status !== 'processing' && o.status !== 'review';
              const mayBeYou = o.you || !someoneIsYou;
              return (
                <li key={o.principalId} className="nl-ob-idrow">
                  <div className="nl-ob-idrow-head">
                    <div>
                      <div className="nl-ob-check-name">{o.legalName}{o.you ? ` · ${t('idYou')}` : ''}</div>
                      <div className="nl-ob-check-desc">{t(`role_${o.role}` as Parameters<OnboardingT>[0])}{o.ownershipPct != null ? ` · ${o.ownershipPct} %` : ''}</div>
                    </div>
                    <span className={`tag ${tone(o)}`}>{statusText(o, t)}</span>
                  </div>
                  {open && (
                    <div className="nl-ob-idrow-actions">
                      {mayBeYou && <button type="button" className="btn btn-primary" disabled={start.isPending} onClick={() => self(o)}>{t(o.status === 'not_started' ? 'idVerifyMe' : 'idVerifyMeAgain')}</button>}
                      {emailFor === o.principalId ? (
                        <div className="nl-ob-idemail">
                          <Field label={t('idEmailLabel', { name: o.legalName })} error={fieldError}>
                            <TextInput data-autofocus type="email" autoComplete="off" value={email} onChange={e => setEmail(e.target.value)} onKeyDown={e => e.key === 'Enter' && send(o)} />
                          </Field>
                          <button type="button" className="btn btn-secondary" disabled={start.isPending} onClick={() => send(o)}>{t('idSend')}</button>
                        </div>
                      ) : (
                        <button type="button" className="btn btn-secondary" onClick={() => { start.reset(); setEmailFor(o.principalId); setEmail(''); }}>{t(o.delivery === 'email' ? 'idResend' : 'idEmailLink')}</button>
                      )}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )
      )}
      {sentTo ? <div style={{ marginTop: 12 }}><Alert tone="info" role="status">{t('idSent', { email: sentTo })}</Alert></div> : null}
      {otherError ? <div style={{ marginTop: 12 }}><Alert tone="error">{otherError}</Alert></div> : null}
      <p className="nl-ob-note">{t('idPrivacy')}</p>
    </Dialog>
  );
}

function tone(o: OwnerIdentity): string {
  switch (o.status) {
    case 'verified': return 'tag-accent';
    case 'retry': case 'canceled': return 'tag-accent-2';
    case 'processing': case 'review': case 'pending': return 'tag-highlight';
    default: return 'tag-neutral';
  }
}

export function statusText(o: OwnerIdentity, t: OnboardingT): string {
  switch (o.status) {
    case 'not_started': return t('idNotStarted');
    case 'pending': return o.delivery === 'email' && o.emailMasked ? t('idLinkSent', { email: o.emailMasked }) : t('idWaiting');
    case 'processing': return t('idProcessing');
    case 'verified': return t('idVerified');
    case 'review': return t('idReview');
    case 'canceled': return t('idCanceled');
    case 'retry': {
      const code = REASONS.find(r => r === o.lastError);
      return t('idRetry', { reason: t((code ? `idr_${code}` : 'idr_other') as Parameters<OnboardingT>[0]) });
    }
  }
}
