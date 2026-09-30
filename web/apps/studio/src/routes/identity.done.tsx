import { createFileRoute } from '@tanstack/react-router';
import { IdentityDoneScreen } from '../features/onboarding/IdentityDoneScreen';

/** Stripe Identity return URL for owners who opened an emailed link (`StudioLinks.identityDone`). Public. */
export const Route = createFileRoute('/identity/done')({
  component: IdentityDoneScreen,
});
