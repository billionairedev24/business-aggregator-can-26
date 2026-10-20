import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, ErrorState, Field, TextInput, useFormatters } from '@northline/ui';
import { ValidationError } from '@northline/client';
import { FormSkeleton } from '../account/ProfileTab';
import { referralQuery, rememberedInvite, useClaimReferral } from './api';
import { useLoyaltyT } from './messages';

/**
 * S-152 "Invite a neighbour": the person's code and link to share, how it went, and — for a new account — the field to
 * use a neighbour's code (prefilled from an invite link). The rules (points each side, the smallest first order) are
 * the region's.
 */
export function InviteTab() {
  const t = useLoyaltyT();
  const { money } = useFormatters();
  const referral = useQuery(referralQuery);
  const claim = useClaimReferral();
  const [code, setCode] = useState(() => rememberedInvite());
  const [copied, setCopied] = useState(false);
  const fieldError = claim.error instanceof ValidationError ? claim.error.byField().code : claim.error ? t('claimError') : undefined;
  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t('inviteTitle')}</h1>
      {referral.isPending ? <FormSkeleton label={t('loading')} rows={2} />
        : referral.isError ? <ErrorState message={t('loadError')} onRetry={() => void referral.refetch()} />
          : (() => {
            const r = referral.data;
            const share = async () => {
              const text = t('shareText', { code: r.code });
              if (navigator.share) {
                await navigator.share({ text, url: r.shareUrl }).catch(() => undefined);
                return;
              }
              await navigator.clipboard?.writeText(`${text} ${r.shareUrl}`).catch(() => undefined);
              setCopied(true);
            };
            return (
              <>
                <p className="nl-acct-lede">{r.pointsEach > 0
                  ? t('inviteLede', { min: money(r.minOrderCents), points: r.pointsEach, value: money(r.pointsEach) })
                  : t('inviteNone')}</p>
                <div className="nl-acct-panel nl-loyal-code">
                  <span className="nl-small nl-muted">{t('yourCode')}</span>
                  <span className="nl-loyal-code-value" aria-label={`${t('yourCode')} ${r.code.split('').join(' ')}`}>{r.code}</span>
                  <span className="nl-small nl-muted nl-loyal-link">{r.shareUrl}</span>
                  <div className="nl-acct-actions">
                    <Button type="button" onClick={() => void share()}>{t('share')}</Button>
                    <Button type="button" variant="ghost" onClick={() => { void navigator.clipboard?.writeText(r.shareUrl).then(() => setCopied(true), () => undefined); }}>{t('copyLink')}</Button>
                    {copied ? <span role="status" className="nl-small">{t('copied')}</span> : null}
                  </div>
                </div>
                <p className="nl-muted">{t('inviteStats', { pending: r.pending, rewarded: r.rewarded, points: r.pointsEarned })}</p>
                {r.claimed ? (
                  <p className="nl-sec-banner" role="status">{t(`claimed_${r.claimed.state}`, { code: r.claimed.code, min: money(r.minOrderCents) })}</p>
                ) : r.canClaim ? (
                  <form className="nl-loyal-claim" onSubmit={e => { e.preventDefault(); claim.mutate(code.trim()); }}>
                    <h2 className="nl-acct-h2">{t('haveCode')}</h2>
                    <Field label={t('codeLabel')} error={fieldError}>
                      <TextInput value={code} onChange={e => setCode(e.target.value)} autoCapitalize="characters" autoComplete="off" aria-invalid={!!fieldError} />
                    </Field>
                    <Button type="submit" disabled={claim.isPending} aria-busy={claim.isPending}>{t('useCode')}</Button>
                  </form>
                ) : null}
              </>
            );
          })()}
    </>
  );
}
