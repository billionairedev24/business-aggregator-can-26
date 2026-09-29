// Studio smoke sweep: opens every Studio screen for the three seeded businesses at desktop/phone widths in en and fr,
// and reports console errors, failed /api calls, error states, missing headings and horizontal scroll.
// Needs: api on :8090 (`local` profile, dev seed), studio dev server on :3100 with NL_DEV_USER=01J9ZD3V00000000000000RAV1.
// Run: `npx -y -p playwright-core@1.56 node scripts/studio-smoke.mjs` (CHROMIUM=/path/to/chrome to override).
import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright-core';
const base = process.env.STUDIO_URL ?? 'http://localhost:3100';
mkdirSync('shots', { recursive: true });
const biz = { provider: '01J9ZD3V00000000000000PWM1', seller: '01J9ZD3V00000000000000PWP1', kitchen: '01J9ZD3V00000000000000PDB1' };
const screens = {
  provider: ['', 'appointments', 'messages', 'listings', 'listings/new', 'listings/bulk', 'availability', 'page', 'earnings', 'reports', 'payouts', 'refunds', 'compliance', 'reviews', 'settings', 'settings?tab=team', 'settings?tab=security', 'settings?tab=notifications', 'settings?tab=api', 'help'],
  seller: ['', 'orders', 'messages', 'listings', 'listings/new', 'page', 'earnings', 'payouts', 'refunds', 'compliance', 'reviews', 'settings'],
  kitchen: ['kitchen/live', 'kitchen/menu', 'kitchen/combos', 'kitchen/hours', 'messages', 'earnings', 'reports', 'payouts', 'compliance', 'reviews', 'page', 'settings', 'help'],
};
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM ?? '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
const results = [];
for (const [width, lang] of [[1280, 'en'], [375, 'en'], [1280, 'fr']]) {
  const ctx = await browser.newContext({ viewport: { width, height: 900 } });
  await ctx.addInitScript(l => { try { localStorage.setItem('nl.locale', l); } catch {} }, lang);
  const page = await ctx.newPage();
  let errs = [];
  page.on('console', m => { if (m.type() === 'error' && !m.text().includes('ERR_CERT')) errs.push(m.text().slice(0, 200)); });
  page.on('pageerror', e => errs.push('PAGEERROR ' + String(e).slice(0, 200)));
  page.on('response', r => { if (r.url().includes('/api/') && r.status() >= 400) errs.push(`HTTP ${r.status()} ${r.url().replace(base, '')}`); });
  for (const [kind, list] of Object.entries(screens)) {
    for (const s of list) {
      errs = [];
      const url = `${base}/b/${biz[kind]}${s ? '/' + s : ''}`;
      await page.goto(url, { waitUntil: 'networkidle', timeout: 30000 }).catch(e => errs.push('NAV ' + e.message.slice(0, 100)));
      await page.waitForSelector('h1', { timeout: 8000 }).catch(() => {}); await page.waitForTimeout(300);
      const info = await page.evaluate(() => ({
        h1: document.querySelector('h1')?.textContent?.trim().slice(0, 60) ?? null,
        hscroll: document.documentElement.scrollWidth > window.innerWidth + 1,
        errorState: !!document.querySelector('.nl-errorstate'),
        path: location.pathname + location.search,
      }));
      if (width === 1280 && lang === 'en') await page.screenshot({ path: `shots/${kind}-${(s || 'home').replace(/[/?=]/g, '_')}.png`, fullPage: false });
      results.push({ width, lang, kind, s, ...info, errs: [...new Set(errs)] });
    }
  }
  await ctx.close();
}
await browser.close();
const bad = results.filter(r => r.errs.length || r.hscroll || r.errorState || !r.h1);
console.log(`screens checked: ${results.length}, problems: ${bad.length}`);
for (const r of bad) console.log(JSON.stringify(r));
process.exitCode = bad.length ? 1 : 0;
