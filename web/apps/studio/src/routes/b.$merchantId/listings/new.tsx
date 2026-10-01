import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { NewListingScreen } from '../../../features/catalogue/EditorScreen';

/** `?type=service|product|bundle` preselects the listing type (bundle: S-65). */
export const Route = createFileRoute('/b/$merchantId/listings/new')({
  validateSearch: z.object({ type: z.enum(['service', 'product', 'bundle']).optional() }),
  component: function NewListingRoute() {
    const { type } = Route.useSearch();
    return <NewListingScreen kind={type} />;
  },
});
