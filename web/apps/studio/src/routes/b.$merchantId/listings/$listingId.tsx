import { createFileRoute } from '@tanstack/react-router';
import { EditListingScreen } from '../../../features/catalogue/EditorScreen';

export const Route = createFileRoute('/b/$merchantId/listings/$listingId')({
  component: function EditListingRoute() {
    const { listingId } = Route.useParams();
    return <EditListingScreen listingId={listingId} />;
  },
});
