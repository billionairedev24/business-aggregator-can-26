import { createFileRoute } from '@tanstack/react-router';
import { SellerDetail } from '../../features/sellers/SellerDetail';

/** Seller detail (S-82). */
export const Route = createFileRoute('/_console/sellers/$sellerId')({ component: SellerDetail });
