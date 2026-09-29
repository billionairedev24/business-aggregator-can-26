import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { NewListingScreen } from '../../../features/catalogue/EditorScreen';

/** `?type=service|product` preselects the listing type for businesses that sell both. */
export const Route = createFileRoute('/b/$merchantId/listings/new')({
  validateSearch: z.object({ type: z.enum(['service', 'product']).optional() }),
  component: function NewListingRoute() {
    const { type } = Route.useSearch();
    return <NewListingScreen kind={type} />;
  },
});
