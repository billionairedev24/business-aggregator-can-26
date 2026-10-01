import { Link } from '@tanstack/react-router';
import { EmptyState } from '@northline/ui';
import { useShellT } from './messages';
import { SCREEN_PATH } from './screens';

export function NotFound() {
  const t = useShellT();
  return <div><EmptyState action={<Link to={SCREEN_PATH.overview} className="btn btn-secondary">{t('backToOverview')}</Link>}>{t('notFound')}</EmptyState></div>;
}
