// The consumer's server entry (TanStack Start picks up src/server.ts): its default handler, wrapped so no error leaves
// as h3's raw `{"status":500,"unhandled":true,"message":"HTTPError"}` (lib/serverErrors.ts). The same in `vite dev`
// and in the built app node-server.mjs serves; only development shows the error itself (import.meta.env.DEV).
import { createStartHandler, defaultStreamHandler } from '@tanstack/react-start/server';
import { upstreamStatus, withErrorPages } from './lib/serverErrors';

const start = createStartHandler(async ctx => upstreamStatus(ctx.router, await defaultStreamHandler(ctx), ctx.request));

export default {
  fetch: withErrorPages(start, {
    dev: import.meta.env.DEV,
    supportEmail: process.env.NL_SUPPORT_EMAIL?.trim() || null,
    preview: process.env.NL_ERROR_PREVIEW === '1',
  }),
};
