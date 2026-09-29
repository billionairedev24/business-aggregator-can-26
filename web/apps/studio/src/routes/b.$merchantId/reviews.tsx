import { createFileRoute } from '@tanstack/react-router';
import { ReviewsScreen } from '../../features/reviews/ReviewsScreen';

export const Route = createFileRoute('/b/$merchantId/reviews')({ component: ReviewsScreen });
