// The mock api over HTTP (S-109), for the consumer's server-side rendering (NL_BFF_URL points here):
//   node scripts/mock-api.mjs <app> <port>
// GET /__mock/health answers "ok"; POST /__mock/state?signedIn=true|false switches the session, ?down=503 makes every
// answer that status (the BFF or the api down: the error states; ?down=0 back to normal). Everything else is answered
// by responder.mjs.
import { createServer } from 'node:http';
import { responder } from './responder.mjs';
import { setSignedIn } from './overrides.mjs';

const [app = 'consumer', port = '4603'] = process.argv.slice(2);
const answer = responder(app);
let down = 0;
createServer((req, res) => {
  if (req.url === '/__mock/health') { res.end('ok'); return; }
  if (req.method === 'POST' && req.url?.startsWith('/__mock/state')) {
    const params = new URL(req.url, 'http://x').searchParams;
    if (params.has('signedIn')) setSignedIn(app, params.get('signedIn') === 'true');
    if (params.has('down')) down = Number(params.get('down')) || 0;
    res.statusCode = 204; res.end(); return;
  }
  if (down) { res.writeHead(down, { 'content-type': 'application/json' }); res.end(JSON.stringify({ status: down, error: 'down' })); return; }
  const { status, body } = answer(req.method ?? 'GET', req.url ?? '/');
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(body === undefined || body === null ? '' : JSON.stringify(body));
}).listen(Number(port), '127.0.0.1', () => console.log(`mock api (${app}) on :${port}`));
