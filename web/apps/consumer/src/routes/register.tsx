import { createFileRoute } from '@tanstack/react-router';
import { AuthParams } from './sign-in';
import { pending } from '../features/shell/pending';

/** Create account (S-62): design 06 `auth` with authMode new. */
export const Route = createFileRoute('/register')({ validateSearch: AuthParams, ...pending('register') });
