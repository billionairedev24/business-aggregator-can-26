// HTTP for the demo-data driver: a cookie jar per person and app, sign-in at northline-auth, the app's BFF session, and
// the app's own `/api` calls with the CSRF double-submit header — what the Studio, the consumer site and the console do
// in a browser (web/e2e/src/bff.ts), without a browser. Node 22, no dependencies.

/** Cookies by host name (browsers share cookies across the ports of one host, so `localhost:3100` and `:9000` do too). */
export class CookieJar {
  #cookies = new Map(); // `${domain}|${name}` → {domain, name, value, hostOnly}

  store(url, headers) {
    const host = new URL(url).hostname;
    for (const line of headers.getSetCookie?.() ?? []) {
      const [pair, ...attrs] = line.split(';');
      const eq = pair.indexOf('=');
      if (eq < 1) continue;
      const name = pair.slice(0, eq).trim();
      const value = pair.slice(eq + 1).trim();
      let domain = host;
      let hostOnly = true;
      let expired = false;
      for (const a of attrs) {
        const [k, v = ''] = a.split('=').map(s => s.trim());
        const key = k.toLowerCase();
        if (key === 'domain' && v) { domain = v.replace(/^\./, '').toLowerCase(); hostOnly = false; }
        if (key === 'max-age' && Number(v) <= 0) expired = true;
        if (key === 'expires' && Date.parse(v) < Date.now()) expired = true;
      }
      const key = `${domain}|${name}`;
      if (expired || value === '') this.#cookies.delete(key);
      else this.#cookies.set(key, { domain, name, value, hostOnly });
    }
  }

  header(url) {
    const host = new URL(url).hostname;
    return [...this.#cookies.values()]
      .filter(c => (c.hostOnly ? host === c.domain : host === c.domain || host.endsWith(`.${c.domain}`)))
      .map(c => `${c.name}=${c.value}`)
      .join('; ');
  }

  get(url, name) {
    const host = new URL(url).hostname;
    return [...this.#cookies.values()].find(c => c.name === name && (host === c.domain || host.endsWith(`.${c.domain}`)))?.value;
  }
}

export class HttpError extends Error {
  constructor(method, url, status, body) {
    const detail = typeof body === 'string' ? body.slice(0, 400) : JSON.stringify(body ?? '').slice(0, 400);
    super(status === 429
      ? `${method} ${new URL(url).pathname} → 429: rate limited (sign-ins and codes are limited per address and account) — wait ${body?.retryAfterSeconds ?? 600} s and run again`
      : `${method} ${new URL(url).pathname} → ${status}: ${detail}`);
    this.status = status;
    this.body = body;
  }
  get code() { return this.body?.code; }
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

/** One request with the jar; follows redirects itself (so every hop's cookies are kept). */
export async function request(jar, method, url, { body, form, multipart, headers = {}, follow = true, expect, anyStatus = false } = {}) {
  let target = url;
  let verb = method;
  let payload;
  const h = { accept: 'application/json', ...headers };
  if (multipart) payload = multipart;
  else if (form) { h['content-type'] = 'application/x-www-form-urlencoded'; payload = new URLSearchParams(form).toString(); }
  else if (body !== undefined) { h['content-type'] = 'application/json'; payload = JSON.stringify(body); }
  for (let hop = 0; hop < 15; hop++) {
    const cookie = jar.header(target);
    let res;
    for (let attempt = 0; ; attempt++) {
      try {
        res = await fetch(target, { method: verb, headers: { ...h, ...(cookie ? { cookie } : {}) }, body: payload, redirect: 'manual' });
        break;
      } catch (e) {
        if (attempt >= 3) throw new Error(`${verb} ${target}: ${e.cause?.code ?? e.message}`);
        await sleep(1000 * (attempt + 1));
      }
    }
    jar.store(target, res.headers);
    if (process.env.DEMO_DEBUG) console.error(`  ${verb} ${target} → ${res.status}${res.headers.get('location') ? ` → ${res.headers.get('location')}` : ''}`);
    const location = res.headers.get('location');
    if (follow && location && res.status >= 300 && res.status < 400) {
      target = new URL(location, target).href;
      if (res.status !== 307 && res.status !== 308) { verb = 'GET'; payload = undefined; delete h['content-type']; }
      await res.arrayBuffer();
      continue;
    }
    const text = await res.text();
    let json;
    try { json = text ? JSON.parse(text) : undefined; } catch { json = text; }
    const ok = anyStatus || (expect ? expect.includes(res.status) : res.status < 400);
    if (!ok) throw new HttpError(verb, target, res.status, json);
    return { status: res.status, body: json, url: target, headers: res.headers };
  }
  throw new Error(`${method} ${url}: too many redirects`);
}

/**
 * A person signed in to one app (Studio, consumer site or console): northline-auth's session cookie and the app BFF's
 * session cookie in one jar. `call()` goes through the app's origin, as the app's own fetches do.
 */
export class AppSession {
  constructor(appUrl, authUrl, { lang = 'en' } = {}) {
    this.appUrl = appUrl.replace(/\/$/, '');
    this.authUrl = authUrl.replace(/\/$/, '');
    this.jar = new CookieJar();
    this.lang = lang;
  }

  auth(method, path, body) {
    return request(this.jar, method, this.authUrl + path, { body, headers: { 'accept-language': this.lang === 'fr' ? 'fr-CA' : 'en-CA', origin: this.appUrl } }).then(r => r.body);
  }

  /** `GET /bff/login?next=/`: northline-auth already knows the person, so the authorization completes without a page. */
  async openApp() {
    await request(this.jar, 'GET', `${this.appUrl}/bff/login?next=%2F`, { headers: { accept: 'text/html' }, anyStatus: true });
    const { body } = await request(this.jar, 'GET', `${this.appUrl}/bff/session`);
    if (!body?.user) throw new Error(`${this.appUrl}: no BFF session after sign-in`);
    this.user = body.user;
    return body.user;
  }

  async call(method, path, opts = {}) {
    const xsrf = this.jar.get(this.appUrl, 'XSRF-TOKEN') ?? this.jar.get(this.appUrl, '__Host-XSRF-TOKEN');
    const headers = { 'accept-language': this.lang === 'fr' ? 'fr-CA' : 'en-CA', ...(xsrf && method !== 'GET' ? { 'X-XSRF-TOKEN': xsrf } : {}), ...(opts.headers ?? {}) };
    return (await request(this.jar, method, this.appUrl + path, { ...opts, headers })).body;
  }

  get(path, opts) { return this.call('GET', path, opts); }
  post(path, body, opts = {}) { return this.call('POST', path, { body: body ?? {}, ...opts }); }
  put(path, body, opts = {}) { return this.call('PUT', path, { body, ...opts }); }
  patch(path, body, opts = {}) { return this.call('PATCH', path, { body, ...opts }); }
  del(path, opts) { return this.call('DELETE', path, opts); }
}
