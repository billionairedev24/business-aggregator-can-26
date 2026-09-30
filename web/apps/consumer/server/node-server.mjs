// Node HTTP server for the consumer web app (S-14). TanStack Start's `vite build` emits a fetch handler
// (dist/server/server.js, `export default { fetch(request) }`) and the browser assets (dist/client); this file serves
// both with Node's built-in http module — no extra dependency, no framework adapter.
//
//   node server/node-server.mjs           # PORT (3000), HOST (0.0.0.0)
//
// GET /healthz answers "ok" for Kubernetes probes. Static files come from dist/client (hashed /assets/* are cached for
// a year, everything else revalidates); every other request is rendered by the app. SIGTERM drains open connections.
import { createServer } from 'node:http';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { extname, join, normalize, sep } from 'node:path';
import { Readable } from 'node:stream';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const clientDir = join(root, 'dist', 'client');
const { default: app } = await import(join(root, 'dist', 'server', 'server.js'));

const port = Number(process.env.PORT ?? 3000);
const host = process.env.HOST ?? '0.0.0.0';
// Behind the ingress the public scheme/host arrive as X-Forwarded-*; believe them only when told to.
const trustProxy = process.env.TRUST_PROXY === 'true';

const types = {
  '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.avif': 'image/avif', '.ico': 'image/x-icon',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.txt': 'text/plain; charset=utf-8', '.map': 'application/json',
  '.webmanifest': 'application/manifest+json',
};

const securityHeaders = {
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'strict-origin-when-cross-origin',
  'x-frame-options': 'DENY',
  'permissions-policy': 'camera=(), microphone=(), payment=(self), geolocation=(self)',
};

async function staticFile(pathname) {
  if (pathname === '/' || pathname.endsWith('/')) return null;
  let decoded;
  try { decoded = decodeURIComponent(pathname); } catch { return null; }
  const file = normalize(join(clientDir, decoded));
  if (!file.startsWith(clientDir + sep)) return null; // path traversal
  try {
    const info = await stat(file);
    return info.isFile() ? { file, size: info.size } : null;
  } catch {
    return null;
  }
}

function toRequest(req) {
  const proto = (trustProxy && req.headers['x-forwarded-proto']?.split(',')[0].trim()) || 'http';
  const authority = (trustProxy && req.headers['x-forwarded-host']?.split(',')[0].trim()) || req.headers.host || 'localhost';
  const headers = new Headers();
  for (let i = 0; i < req.rawHeaders.length; i += 2) headers.append(req.rawHeaders[i], req.rawHeaders[i + 1]);
  const hasBody = req.method !== 'GET' && req.method !== 'HEAD';
  const controller = new AbortController();
  req.on('close', () => { if (!req.complete) controller.abort(); });
  return new Request(new URL(req.url ?? '/', `${proto}://${authority}`), {
    method: req.method,
    headers,
    body: hasBody ? Readable.toWeb(req) : undefined,
    duplex: hasBody ? 'half' : undefined,
    signal: controller.signal,
  });
}

async function send(res, response, head) {
  const headers = {};
  response.headers.forEach((value, key) => { if (key !== 'set-cookie') headers[key] = value; });
  const cookies = response.headers.getSetCookie();
  if (cookies.length) headers['set-cookie'] = cookies;
  res.writeHead(response.status, { ...securityHeaders, ...headers });
  if (head || !response.body) { res.end(); return; }
  Readable.fromWeb(response.body).on('error', () => res.destroy()).pipe(res);
}

const server = createServer(async (req, res) => {
  try {
    const pathname = new URL(req.url ?? '/', 'http://x').pathname;
    if (pathname === '/healthz') {
      res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' });
      res.end('ok');
      return;
    }
    if (req.method === 'GET' || req.method === 'HEAD') {
      const found = await staticFile(pathname);
      if (found) {
        res.writeHead(200, {
          ...securityHeaders,
          'content-type': types[extname(found.file)] ?? 'application/octet-stream',
          'content-length': found.size,
          'cache-control': pathname.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : 'public, max-age=0, must-revalidate',
        });
        if (req.method === 'HEAD') res.end();
        else createReadStream(found.file).pipe(res);
        return;
      }
    }
    await send(res, await app.fetch(toRequest(req)), req.method === 'HEAD');
  } catch (error) {
    console.error(`${req.method} ${req.url} failed`, error);
    if (!res.headersSent) res.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('Internal Server Error');
  }
});

server.keepAliveTimeout = 65_000; // longer than common load-balancer idle timeouts (60 s)
server.listen(port, host, () => console.log(`consumer web listening on http://${host}:${port}`));

for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => {
    console.log(`${signal}: closing`);
    server.close(() => process.exit(0));
    server.closeIdleConnections();
    setTimeout(() => process.exit(0), 10_000).unref();
  });
}
