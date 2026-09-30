import { useCallback, useState } from 'react';
import { createFileRoute } from '@tanstack/react-router';
import { MenuBuilderScreen } from '../../../features/kitchen/MenuBuilderScreen';
import { groupsQuery, menusQuery } from '../../../features/kitchen/api';
import type { PosReturn } from '../../../features/kitchen/PosImport';

const POSES = ['square', 'clover'] as const;
const RESULTS = ['connected', 'denied', 'failed', 'expired'] as const;

/** `?pos=square|clover&result=…&menu=…`: where the POS OAuth callback (S-36) lands. */
export const Route = createFileRoute('/b/$merchantId/kitchen/menu')({
  validateSearch: (search: Record<string, unknown>): PosReturn => {
    const pos = (POSES as readonly unknown[]).includes(search.pos) ? (search.pos as PosReturn['pos']) : undefined;
    const result = (RESULTS as readonly unknown[]).includes(search.result) ? (search.result as PosReturn['result']) : undefined;
    const menu = typeof search.menu === 'string' && /^[0-9A-Z]{26}$/.test(search.menu) ? search.menu : undefined;
    return pos && result ? { pos, result, ...(menu ? { menu } : {}) } : {};
  },
  loader: ({ context, params }) => {
    void context.queryClient.prefetchQuery(menusQuery(params.merchantId));
    void context.queryClient.prefetchQuery(groupsQuery(params.merchantId));
  },
  component: MenuRoute,
});

function MenuRoute() {
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  // keep the outcome for this visit, but drop it from the address so a reload doesn't show it again
  const [returned] = useState(search);
  const seen = useCallback(() => void navigate({ search: {}, replace: true }), [navigate]);
  return <MenuBuilderScreen returned={returned} onReturnSeen={seen} />;
}
