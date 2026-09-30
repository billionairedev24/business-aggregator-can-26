import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../features/shell/pending';

/** Restaurant menu (S-57): modifiers, combos. Server-rendered for SEO. */
export const Route = createFileRoute('/food/$kitchen')({ ...pending('restaurant') });
