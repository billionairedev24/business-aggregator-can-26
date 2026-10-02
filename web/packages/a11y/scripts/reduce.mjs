// Shared by the recorder and the mock api (S-109): how a request is keyed.

/** "/api/v1/merchants/01J9…/menus/mn1?x=1" → "/api/v1/merchants/:id/menus/:id" — ids are segments with a digit (not v1). */
export function pattern(path) {
  return path.split('?')[0].split('/').map(s => (/\d/.test(s) && !/^v\d+$/.test(s) ? ':id' : s)).join('/');
}

/** The path and query of a url, whatever its origin ("http://bff:8081/api/…" and "/api/…" are the same request). */
export function pathOf(url) {
  try { const u = new URL(url, 'http://x'); return u.pathname + u.search; } catch { return url; }
}

/**
 * Recorded lines → { exact: { "GET /path?q": answer }, pattern: { "GET /path/:id": answer } }. Among the 2xx JSON answers
 * to a request, the one from a test of the feature the path names wins (a catch-all stub in another feature's test may
 * have answered it with that feature's data); else the first.
 */
export function reduce(lines) {
  const exact = {}, byPattern = {};
  const better = (cur, next, path) => {
    if (!cur) return next;
    const words = path.split('?')[0].split('/').filter(w => w.length > 2 && !/\d/.test(w));
    const feature = a => (a.from ?? '').split('/')[1] ?? '';
    const fits = a => !!feature(a) && words.some(w => feature(a).startsWith(w.replace(/s$/, '')) || w.startsWith(feature(a)));
    return !fits(cur) && fits(next) ? next : cur;
  };
  for (const l of lines) {
    if (l.status < 200 || l.status >= 300) continue;
    const path = pathOf(l.url);
    if (!path.startsWith('/api/') && !path.startsWith('/bff/')) continue;
    let body = l.body;
    try { body = l.body ? JSON.parse(l.body) : null; } catch { continue; } // JSON answers only
    const answer = { status: l.status, body, from: l.test };
    const e = `${l.method} ${path}`, p = `${l.method} ${pattern(path)}`;
    exact[e] = better(exact[e], answer, path);
    byPattern[p] = better(byPattern[p], answer, path);
  }
  return { exact, pattern: byPattern };
}
