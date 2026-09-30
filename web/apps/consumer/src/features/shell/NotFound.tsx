import { EmptyState, PageHeader, SiteLink } from '@northline/ui';
import { useShellT } from './messages';

export function NotFound() {
  const t = useShellT();
  return (
    <div className="nl-page">
      <PageHeader title={t('notFoundTitle')} lede={t('notFoundBody')} />
      <EmptyState action={<SiteLink href="/" className="btn btn-primary">{t('backHome')}</SiteLink>}>{t('notFoundBody')}</EmptyState>
    </div>
  );
}
