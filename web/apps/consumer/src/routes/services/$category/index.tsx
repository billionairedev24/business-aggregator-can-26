import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../../../features/shell/pending';

/** Service category (S-53): design 06 `svcCategory` (visit / home / event / appointment / consult). */
export const Route = createFileRoute('/services/$category/')({ ...pending('svcCategory') });
