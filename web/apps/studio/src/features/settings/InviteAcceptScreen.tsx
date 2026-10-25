import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { UsersThree } from '@phosphor-icons/react';
import { Alert, Button, ErrorState, PageHeader, PageSkeleton } from '@northline/ui';
import { ApiError } from '../../lib/http';
import { useSession, useSignOut } from '../../lib/session';
import { homeScreen, screenHref } from '../shell/nav';
import { invitationQuery, useAcceptInvitation } from './api';
import { useInviteT } from './inviteMessages';
import { Standalone } from '../shell/Standalone';
import './Settings.css';

/** `/invite/$token`: the invitee (signed in with their own account) joins the team. */
export function InviteAcceptScreen({ token }: { token: string }) {
  const t = useInviteT();
  const navigate = useNavigate();
  const session = useSession();
  const signOut = useSignOut();
  const q = useQuery(invitationQuery(token));
  const accept = useAcceptInvitation(token);
  const who = session.data?.user.email ?? session.data?.user.phone ?? '';
  if (q.isPending) return <Standalone><PageSkeleton kpis={0} rows={3} /></Standalone>;
  if (q.isError) {
    const missing = q.error instanceof ApiError && q.error.status === 404;
    return <Standalone><PageHeader kicker={t('kicker')} title={t('invalidTitle')} /><ErrorState message={missing ? t('invalid') : t('loadError')} onRetry={missing ? undefined : () => void q.refetch()} /></Standalone>;
  }
  const inv = q.data;
  const roleName = t(`role_${inv.role}`);
  const code = accept.error instanceof ApiError ? (accept.error.body as { code?: string } | undefined)?.code : undefined;
  const join = () => accept.mutate(undefined, { onSuccess: r => void navigate({ to: screenHref(r.merchantId, homeScreen(inv.businessType as never)) }) });
  return (
    <Standalone icon={<UsersThree size={28} />}>
      <PageHeader kicker={t('kicker')} title={t('title', { business: inv.businessName })} lede={t('lede', { role: roleName })} />
      {inv.state !== 'pending' ? <Alert tone="error" role="alert">{t(`state_${inv.state}`)}</Alert> : (
        <>
          {!inv.forYou && <Alert tone="info">{t('notForYou', { who })}</Alert>}
          {code === 'mfa_required' && <Alert tone="error" role="alert">{t('needsMfa')}</Alert>}
          {accept.isError && code !== 'mfa_required' && <Alert tone="error" role="alert">{code === 'invitation_not_for_you' ? t('notForYou', { who }) : code === 'already_member' ? t('alreadyMember') : t('acceptError')}</Alert>}
          <div className="nl-solo-actions">
            <Button onClick={join} disabled={accept.isPending || !inv.forYou}>{accept.isPending ? t('joining') : t('join', { business: inv.businessName })}</Button>
            <Button variant="ghost" onClick={() => void signOut()}>{t('switchAccount')}</Button>
          </div>
        </>
      )}
    </Standalone>
  );
}
