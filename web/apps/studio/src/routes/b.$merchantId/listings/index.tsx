import { createFileRoute } from '@tanstack/react-router';
import { ListingsScreen } from '../../../features/catalogue/ListingsScreen';

export const Route = createFileRoute('/b/$merchantId/listings/')({ component: ListingsScreen });
