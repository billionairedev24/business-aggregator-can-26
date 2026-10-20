import { createCsrfMiddleware, createStart } from '@tanstack/react-start';
import { createSerializationAdapter } from '@tanstack/react-router';
import { ApiError, failureKind } from '@northline/client';

/*
 * Errors in the state the browser hydrates from: an ApiError keeps its status (the error screens tell a 404 from a
 * 503), and any other failure to reach the BFF or the api — no connection, a timeout, an answer that is not what the
 * schema expects — arrives as a 503 ApiError, so the server's render and the browser's agree on what failed. Without
 * these, every error crossed as a bare `Error(message)` and the screen showed the raw message.
 */
export const apiErrorAdapter = createSerializationAdapter({
  key: 'nl-api-error',
  test: (v): v is ApiError => v instanceof ApiError,
  toSerializable: (e: ApiError) => ({ status: e.status, message: e.message }),
  fromSerializable: ({ status, message }: { status: number; message: string }) => new ApiError(status, message),
});
export const upstreamErrorAdapter = createSerializationAdapter({
  key: 'nl-upstream-error',
  test: (v): v is Error => v instanceof Error && !(v instanceof ApiError) && failureKind(v) === 'unreachable',
  toSerializable: () => ({ status: 503 }),
  fromSerializable: ({ status }: { status: number }) => new ApiError(status, 'Service unavailable'),
});

/**
 * Any other error (a bug in a loader or a component): production pages carry no internals — the message stays in the
 * server's log line (lib/serverErrors.ts) — development keeps it for the error screen's "Developer details".
 */
export const internalErrorBody = (e: Error, dev: boolean = import.meta.env.DEV) => ({ name: e.name, message: dev ? e.message : '' });
export const internalErrorAdapter = createSerializationAdapter({
  key: 'nl-internal-error',
  test: (v): v is Error => v instanceof Error && !(v instanceof ApiError) && failureKind(v) === 'error',
  toSerializable: (e: Error) => internalErrorBody(e),
  fromSerializable: ({ name, message }: { name: string; message: string }) => Object.assign(new Error(message), { name }),
});

/**
 * TanStack Start's options for this app (src/start.ts is picked up like src/server.ts). Declaring them replaces Start's
 * defaults, so the CSRF check its server functions get by default is declared here again.
 */
export const startInstance = createStart(() => ({
  serializationAdapters: [apiErrorAdapter, upstreamErrorAdapter, internalErrorAdapter],
  requestMiddleware: [createCsrfMiddleware({ filter: ctx => ctx.handlerType === 'serverFn' })],
}));
