#!/usr/bin/env node
// make photos-push (docs/runbooks/photos.md): the candidates make photos-fetch downloaded
// (web/packages/media/candidates/, git-ignored) committed on a new branch photos/candidates-<date> from the latest
// develop and pushed, so whoever picks can see them from any machine. Refuses to run with other changes in the
// working tree (they would ride along or be lost). Leaves you on the new branch; `git switch develop` goes back.
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const CANDIDATES = 'web/packages/media/candidates';
const git = (...args) => execFileSync('git', args, { cwd: REPO, encoding: 'utf8' }).trim();
const sh = (...args) => execFileSync('git', args, { cwd: REPO, stdio: 'inherit' });

function fail(message) {
  console.error(`photos-push: ${message}`);
  process.exit(1);
}

const state = join(REPO, CANDIDATES, 'candidates.json');
if (!existsSync(state)) fail(`nothing to push: ${CANDIDATES}/candidates.json is missing — run make photos-fetch first`);
const changes = git('status', '--porcelain', '--untracked-files=all');
if (changes) fail(`the working tree has other changes; commit or stash them first:\n${changes}`);

const slots = Object.values(JSON.parse(readFileSync(state, 'utf8')).slots ?? {});
const filled = slots.filter(s => s.status === 'done').length;
const day = new Date().toISOString().slice(0, 10);
let branch = `photos/candidates-${day}`;
const exists = b => { try { git('rev-parse', '--verify', '--quiet', `refs/heads/${b}`); return true; } catch { return false; } };
if (exists(branch)) branch = `${branch}-${new Date().toISOString().slice(11, 16).replace(':', '')}`;

sh('fetch', '--quiet', 'origin', 'develop');
sh('switch', '--quiet', '-c', branch, 'origin/develop');
sh('add', '--force', '--', CANDIDATES);
sh('commit', '--quiet', '-m', `Photo candidates from Openverse: ${filled} slot(s) (make photos-fetch)\n\nPick with make photos-pick (docs/runbooks/photos.md); delete this branch once the picks are merged.`);
for (let attempt = 1; ; attempt++) {
  try { sh('push', '-u', 'origin', branch); break; } catch (e) {
    if (attempt >= 4) fail(`push failed (${e.message}); retry with: git push -u origin ${branch}`);
    console.error(`push failed, retrying (${attempt}/4)…`);
    execFileSync('sleep', [String(2 ** attempt)]);
  }
}
console.log(`\npushed ${filled} slot(s) of candidates on branch: ${branch}`);
