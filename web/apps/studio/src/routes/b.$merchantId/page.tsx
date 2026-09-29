import { createFileRoute } from '@tanstack/react-router';
import { StorefrontScreen } from '../../features/storefront/StorefrontScreen';

export const Route = createFileRoute('/b/$merchantId/page')({ component: StorefrontScreen });
