import type { ReactNode } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import { ErrorState, PageSkeleton } from '@northline/ui';
import { isForbidden } from './format';
import { useFinanceT } from './messages';

/** Loading skeleton / error with Retry (403 → role message, no retry) / content. */
export function QueryState<T>({ query, skeleton, children }: { query: UseQueryResult<T>; skeleton?: ReactNode; children: (data: T) => ReactNode }) {
  const t = useFinanceT();
  if (query.isPending) return <>{skeleton ?? <PageSkeleton />}</>;
  if (query.isError) {
    return isForbidden(query.error)
      ? <ErrorState message={t('forbidden')} />
      : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />;
  }
  return <>{children(query.data)}</>;
}
