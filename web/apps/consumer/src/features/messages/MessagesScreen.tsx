import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { EmptyState, ErrorState, SiteLink, Skeleton, useFormatters } from '@northline/ui';
import { Crumbs } from '../pages/Crumbs';
import { signInHref, useViewer } from '../session/api';
import { threadsQuery, useCustomerLive, type ThreadRow } from './api';
import { useMessagesT } from './messages';
import { ThreadPanel } from './ThreadPanel';

/**
 * Account › Messages (S-153): the customer's conversations with providers, newest first, and the open one beside the
 * list (`?thread=`). Reached from the account menu and from a quote or booking. Personal: browser only.
 */
export function MessagesScreen() {
  const t = useMessagesT();
  const { user, loading } = useViewer();
  const search = useSearch({ strict: false }) as { thread?: unknown };
  const open = typeof search.thread === 'string' ? search.thread : undefined;
  const q = useQuery({ ...threadsQuery, enabled: !!user });
  useCustomerLive(!!user);
  if (loading || (user && q.isPending)) return <div className="nl-page nl-msg" aria-busy="true"><Skeleton width="40%" height={40} /><Skeleton height={200} style={{ marginTop: 16 }} /></div>;
  if (!user) {
    return (
      <div className="nl-page nl-msg">
        <h1 className="nl-acct-h1">{t('title')}</h1>
        <EmptyState action={<SiteLink href={signInHref('/account/messages')} className="btn btn-primary">{t('signInAction')}</SiteLink>}>{t('signIn')}</EmptyState>
      </div>
    );
  }
  if (q.isError) return <div className="nl-page nl-msg"><h1 className="nl-acct-h1">{t('title')}</h1><ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /></div>;
  const threads = q.data ?? [];
  const current = threads.find(th => th.id === open);
  return (
    <div className="nl-page nl-msg">
      <Crumbs label={t('crumbs')} items={[{ label: t('crumbAccount'), href: '/account' }, { label: t('title') }]} />
      <h1 className="nl-acct-h1">{t('title')}</h1>
      <p className="nl-msg-muted">{t('lede')}</p>
      {threads.length === 0 ? <EmptyState action={<SiteLink href="/services" className="btn btn-primary">{t('open')}</SiteLink>}>{t('empty')}</EmptyState> : (
        <div className="nl-msg-grid">
          <ul className="nl-msg-list" aria-label={t('title')}>
            {threads.map(th => <ThreadItem key={th.id} thread={th} current={th.id === open} />)}
          </ul>
          {current ? (
            <section className="nl-msg-open" aria-labelledby="msg-open">
              <h2 id="msg-open" className="nl-msg-h2">{t('threadTitle', { name: current.businessName })}</h2>
              <SiteLink href={current.href} className="nl-msg-ref">{about(current, t)}</SiteLink>
              <ThreadPanel thread={{ threadId: current.id }} name={current.businessName} />
            </section>
          ) : null}
        </div>
      )}
    </div>
  );
}

const about = (th: ThreadRow, t: ReturnType<typeof useMessagesT>) =>
  th.refType === 'quote' ? t('aboutQuote', { ref: th.refCode ?? '' }) : t('aboutBooking', { ref: th.refCode ?? '' });

function ThreadItem({ thread: th, current }: { thread: ThreadRow; current: boolean }) {
  const t = useMessagesT();
  const { date } = useFormatters();
  const navigate = useNavigate();
  return (
    <li>
      <button type="button" className="nl-msg-item" aria-current={current || undefined}
        onClick={() => void navigate({ to: '/account/messages', search: { thread: th.id } as never, replace: true })}>
        <span className="nl-msg-item-head">
          <strong>{th.businessName}</strong>
          {th.unread ? <span className="tag tag-brand">{t('unread')}</span> : null}
        </span>
        <span className="nl-msg-muted">{about(th, t)}{th.subject ? ` · ${th.subject}` : ''}</span>
        {th.lastMessage ? <span className="nl-msg-last">{th.lastMessage}</span> : null}
        {th.lastMessageAt ? <span className="nl-msg-muted nl-small">{date(th.lastMessageAt, 'dateTime')}</span> : null}
      </button>
    </li>
  );
}
