import { useState } from 'react';
import { Link } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Checkbox, EmptyState, ErrorState, Skeleton, SiteLink, useFormatters } from '@northline/ui';
import { ValidationError } from '@northline/client';
import { signInHref, useViewer } from '../session/api';
import { erasurePreviewQuery, privacyRequestsQuery, problemCode, useOpenRequest, type Consequence, type PrivacyRequest } from './privacyApi';
import { usePrivacyT, type PrivacyT } from './privacyMessages';
import { tabHref } from './tabs';
import { RequestRow, VerifyDialog } from './YourData';

const OPEN = ['awaiting_verification', 'verified', 'in_progress'];

/**
 * Account › Delete account (S-159; App Store 5.1.1(v), Google Play's account deletion): what deleting the account
 * means and what it affects right now (open orders, upcoming bookings, money held, points, Plus, businesses only this
 * person owns — `erasure-preview`), an "I understand" box, then S-105's erasure request confirmed with the code sent to
 * the verified mobile or email, or the passkey / authenticator. A request already made shows its state and can be
 * cancelled until the erasure starts. Confirmation emails go out when it's scheduled and when it's done.
 */
export function DeleteAccountScreen() {
  const t = usePrivacyT();
  const { user, loading } = useViewer();
  const requests = useQuery({ ...privacyRequestsQuery, enabled: !!user });
  const preview = useQuery({ ...erasurePreviewQuery, enabled: !!user });
  const open = useOpenRequest();
  const [agreed, setAgreed] = useState(false);
  const [tried, setTried] = useState(false);
  const [verifying, setVerifying] = useState<PrivacyRequest>();
  const [error, setError] = useState<string>();

  if (loading || (user && (requests.isPending || preview.isPending))) {
    return <div className="nl-page nl-skel-stack"><Skeleton width={320} height={36} /><Skeleton height={220} /></div>;
  }
  if (!user) {
    return (
      <div className="nl-page nl-delete">
        <h1 className="nl-page-title">{t('dTitle')}</h1>
        <EmptyState action={<SiteLink href={signInHref('/account/delete')} className="btn btn-primary">{t('dSignIn')}</SiteLink>}>{t('dLede')}</EmptyState>
      </div>
    );
  }
  if (requests.isError || preview.isError || !requests.data || !preview.data) {
    return <div className="nl-page"><ErrorState message={t('loadError')} onRetry={() => { void requests.refetch(); void preview.refetch(); }} /></div>;
  }
  const pending = requests.data.find(r => r.type === 'erasure' && OPEN.includes(r.state));

  const confirm = () => {
    setTried(true);
    if (!agreed) return;
    setError(undefined);
    open.mutate({ type: 'erasure' }, {
      onSuccess: r => { if (r.state === 'awaiting_verification') setVerifying(r); },
      onError: e => setError(problemCode(e) === 'request_open' ? t('alreadyOpen') : e instanceof ValidationError ? e.message : t('error')),
    });
  };

  return (
    <div className="nl-page nl-delete">
      <h1 className="nl-page-title">{t('dTitle')}</h1>
      {pending ? (
        <>
          <Alert tone="highlight" role="status">{t('dPending', { ref: pending.reference })}</Alert>
          <ul className="nl-acct-list"><RequestRow request={pending} t={t} onVerify={() => setVerifying(pending)} /></ul>
        </>
      ) : (
        <>
          <p className="nl-acct-lede">{t('dLede')}</p>
          <section aria-labelledby="d-what">
            <h2 id="d-what" className="nl-acct-h2">{t('dWhat')}</h2>
            <ul className="nl-delete-list">
              <li>{t('dWhat1')}</li><li>{t('dWhat2')}</li><li>{t('dWhat3')}</li><li>{t('dWhat4')}</li>
            </ul>
          </section>
          <section aria-labelledby="d-now">
            <h2 id="d-now" className="nl-acct-h2">{t('dNow')}</h2>
            {preview.data.length === 0
              ? <p>{t('dNothing')}</p>
              : <ul className="nl-delete-list" data-testid="consequences">{preview.data.map(c => <li key={c.code}><ConsequenceText t={t} c={c} /></li>)}</ul>}
          </section>
          <p>{t('dData')} <Link to="/account" search={{ tab: 'profile' }} hash="your-data">{t('dDataLink')}</Link></p>
          <Checkbox checked={agreed} onChange={setAgreed} label={t('dAgree')} />
          {tried && !agreed && <p className="nl-error" role="alert">{t('dAgreeError')}</p>}
          {error && <p className="nl-error" role="alert">{error}</p>}
          <div className="nl-acct-actions">
            <Button type="button" variant="danger" disabled={open.isPending} aria-busy={open.isPending} onClick={confirm}>{t('dButton')}</Button>
            <a className="btn btn-ghost" href={tabHref('profile')}>{t('cancel')}</a>
          </div>
          <p className="nl-small nl-muted">{t('dEmail')}</p>
        </>
      )}
      {verifying ? <VerifyDialog request={verifying} t={t} onClose={() => setVerifying(undefined)} /> : null}
    </div>
  );
}

function ConsequenceText({ t, c }: { t: PrivacyT; c: Consequence }) {
  const { money, date } = useFormatters();
  switch (c.code) {
    case 'open_orders': return <>{t('c_open_orders', { count: c.count })}</>;
    case 'upcoming_bookings': return <>{t('c_upcoming_bookings', { count: c.count })}</>;
    case 'held_payments': return <>{t('c_held_payments', { count: c.count, amount: money(c.amountCents) })}</>;
    case 'open_refunds': return <>{t('c_open_refunds', { count: c.count })}</>;
    case 'open_disputes': return <>{t('c_open_disputes', { count: c.count })}</>;
    case 'points': return <>{t('c_points', { count: c.count })}</>;
    case 'plus': return <>{c.until ? t('c_plusUntil', { until: date(c.until, 'long') }) : t('c_plus')}</>;
    case 'sole_owner': return <>{t('c_sole_owner', { count: c.count })}</>;
    default: return null;
  }
}

/**
 * The public page on deleting an account (S-159: Google Play's "delete account" link, also linked from the app's
 * store listing): how to do it in the app and on the site, what to do without a sign-in, what we keep.
 */
export function AccountDeletionPage() {
  const t = usePrivacyT();
  return (
    <div className="nl-page nl-delete">
      <h1 className="nl-page-title">{t('pTitle')}</h1>
      <p className="nl-acct-lede">{t('pLede')}</p>
      <section aria-labelledby="p-app"><h2 id="p-app" className="nl-acct-h2">{t('pApp')}</h2><p>{t('pAppSteps')}</p></section>
      <section aria-labelledby="p-web"><h2 id="p-web" className="nl-acct-h2">{t('pWeb')}</h2><p>{t('pWebSteps')}</p></section>
      <section aria-labelledby="p-cant"><h2 id="p-cant" className="nl-acct-h2">{t('pCant')}</h2><p>{t('pCantSteps')}</p></section>
      <section aria-labelledby="p-kept"><h2 id="p-kept" className="nl-acct-h2">{t('pKept')}</h2><p>{t('pKeptBody')}</p></section>
      <Link to="/account/delete" className="btn btn-primary">{t('pStart')}</Link>
    </div>
  );
}
