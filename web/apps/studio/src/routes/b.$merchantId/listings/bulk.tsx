import { useCallback, useState } from 'react';
import { createFileRoute } from '@tanstack/react-router';
import { BulkUploadScreen } from '../../../features/catalogue/BulkUploadScreen';
import type { CommerceReturn } from '../../../features/catalogue/CommerceIntegrations';

const PLATFORMS = ['shopify', 'square', 'lightspeed'] as const;
const RESULTS = ['connected', 'denied', 'failed', 'expired'] as const;

/** `?commerce=shopify|square|lightspeed&result=…`: where the platforms' OAuth callback (S-35) lands. */
export const Route = createFileRoute('/b/$merchantId/listings/bulk')({
  validateSearch: (search: Record<string, unknown>): CommerceReturn => {
    const commerce = (PLATFORMS as readonly unknown[]).includes(search.commerce) ? (search.commerce as CommerceReturn['commerce']) : undefined;
    const result = (RESULTS as readonly unknown[]).includes(search.result) ? (search.result as CommerceReturn['result']) : undefined;
    return commerce && result ? { commerce, result } : {};
  },
  component: BulkRoute,
});

function BulkRoute() {
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  // keep the outcome for this visit, but drop it from the address so a reload doesn't show it again
  const [returned] = useState(search);
  const seen = useCallback(() => void navigate({ search: {}, replace: true }), [navigate]);
  return <BulkUploadScreen returned={returned} onReturnSeen={seen} />;
}
