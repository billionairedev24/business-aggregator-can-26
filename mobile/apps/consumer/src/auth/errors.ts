/**
 * The account side is done (signed in to northline-auth) but the tokens didn't arrive: no answer, or no code came
 * back. `retryFinish()` asks again without repeating the sign-in.
 */
export class HandoffError extends Error {
  constructor(readonly reason: unknown) {
    super(reason instanceof Error ? reason.message : 'hand-off failed');
    this.name = 'HandoffError';
  }
}
