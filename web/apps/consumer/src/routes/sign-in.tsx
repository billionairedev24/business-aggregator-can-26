import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { pending } from '../features/shell/pending';

/** Sign in (S-62): design 06 `auth`. `next` = where to land (a local path; the BFF checks it again). */
export const AuthParams = z.object({ next: z.string().optional().catch(undefined) });
export const Route = createFileRoute('/sign-in')({ validateSearch: AuthParams, ...pending('signIn') });
