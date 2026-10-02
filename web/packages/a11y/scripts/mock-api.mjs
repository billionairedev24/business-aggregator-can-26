// The mock api over HTTP (S-109), for the consumer's server-side rendering (NL_BFF_URL points here):
//   node scripts/mock-api.mjs <app> <port>
// GET /__mock/health answers "ok"; POST /__mock/state?signedIn=true|false switches the session. Everything else is
// answered by responder.mjs.
import { createServer } from 'node:http';
import { responder } from './responder.mjs';
import { setSignedIn } from './overrides.mjs';

const [app = 'consumer', port = '4603'] = process.argv.slice(2);
const answer = responder(app);
createServer((req, res) => {
  if (req.url === '/__mock/health') { res.end('ok'); return; }
  if (req.method === 'POST' && req.url?.startsWith('/__mock/state')) {
    setSignedIn(app, new URL(req.url, 'http://x').searchParams.get('signedIn') === 'true');
    res.statusCode = 204; res.end(); return;
  }
  const { status, body } = answer(req.method ?? 'GET', req.url ?? '/');
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(body === undefined || body === null ? '' : JSON.stringify(body));
}).listen(Number(port), '127.0.0.1', () => console.log(`mock api (${app}) on :${port}`));
