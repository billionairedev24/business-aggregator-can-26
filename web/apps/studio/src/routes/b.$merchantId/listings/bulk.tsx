import { createFileRoute } from '@tanstack/react-router';
import { BulkUploadScreen } from '../../../features/catalogue/BulkUploadScreen';

export const Route = createFileRoute('/b/$merchantId/listings/bulk')({ component: BulkUploadScreen });
