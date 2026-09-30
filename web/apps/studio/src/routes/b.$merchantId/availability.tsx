import { useCallback, useState } from 'react';
import { createFileRoute } from '@tanstack/react-router';
import { AvailabilityScreen } from '../../features/availability/AvailabilityScreen';
import { hoursQuery, rulesQuery } from '../../features/availability/api';
import type { CalendarReturn } from '../../features/availability/SyncTab';

const RESULTS = ['connected', 'denied', 'failed', 'scopes', 'expired'] as const;

/** `?calendar=google|outlook&result=…[&choose=1]`: where the calendar OAuth callback (S-32) lands. */
export const Route = createFileRoute('/b/$merchantId/availability')({
  validateSearch: (search: Record<string, unknown>): CalendarReturn => {
    const calendar = search.calendar === 'google' || search.calendar === 'outlook' ? search.calendar : undefined;
    const result = (RESULTS as readonly unknown[]).includes(search.result) ? (search.result as CalendarReturn['result']) : undefined;
    return calendar && result ? { calendar, result, choose: search.choose === 1 || search.choose === '1' || search.choose === true } : {};
  },
  loader: ({ context, params }) => {
    void context.queryClient.prefetchQuery(hoursQuery(params.merchantId));
    void context.queryClient.prefetchQuery(rulesQuery(params.merchantId));
  },
  component: AvailabilityRoute,
});

function AvailabilityRoute() {
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  // keep the outcome for this visit, but drop it from the address so a reload doesn't show it again
  const [returned] = useState(search);
  const seen = useCallback(() => void navigate({ search: {}, replace: true }), [navigate]);
  return <AvailabilityScreen returned={returned} onReturnSeen={seen} />;
}
