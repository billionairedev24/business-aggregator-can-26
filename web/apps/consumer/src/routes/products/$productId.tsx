import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../features/shell/pending';

/** Product detail (S-50): offers, variants, stock, delivery cut-off. Server-rendered for SEO. */
export const Route = createFileRoute('/products/$productId')({ ...pending('product') });
