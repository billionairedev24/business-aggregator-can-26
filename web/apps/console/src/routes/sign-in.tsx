import { createFileRoute, redirect } from '@tanstack/react-router';
import { z } from 'zod';
import { safeNext } from '../lib/auth-server';
import { sessionQuery } from '../lib/session';
import { SignInPage } from '../features/auth/SignInPage';

const Search = z.object({ next: z.string().optional(), error: z.string().optional() });

export const Route = createFileRoute('/sign-in')({
  validateSearch: Search,
  beforeLoad: async ({ context, search }) => {
    if (await context.queryClient.ensureQueryData(sessionQuery)) throw redirect({ to: safeNext(search.next) ?? '/' });
  },
  component: function SignIn() {
    const { next, error } = Route.useSearch();
    return <SignInPage next={next} error={error} />;
  },
});
