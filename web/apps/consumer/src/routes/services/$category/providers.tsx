import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../../features/shell/pending';

/** Provider list of a category (S-53): design 06 `providers`. */
export const Route = createFileRoute('/services/$category/providers')({ ...pending('providers') });
