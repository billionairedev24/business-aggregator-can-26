import { test } from '@playwright/test';
import { sweep } from './support';

// S-109: the console journeys — the queues, privacy requests, retention and audit.
test('sign-in (signed out)', async ({ page }) => sweep(page, 'console', 'sign-in', '/sign-in', { signedIn: false }));
test('overview (charts)', async ({ page }) => sweep(page, 'console', 'overview', '/', { signedIn: true }));
test('queue · verification', async ({ page }) => sweep(page, 'console', 'verification', '/verification', { signedIn: true }));
test('queue · vetting', async ({ page }) => sweep(page, 'console', 'vetting', '/vetting', { signedIn: true }));
test('queue · disputes', async ({ page }) => sweep(page, 'console', 'disputes', '/disputes', { signedIn: true }));
test('queue · support', async ({ page }) => sweep(page, 'console', 'support', '/support', { signedIn: true }));
test('privacy requests', async ({ page }) => sweep(page, 'console', 'privacy', '/privacy', { signedIn: true }));
test('retention', async ({ page }) => sweep(page, 'console', 'retention', '/privacy?view=retention', { signedIn: true }));
test('team & audit log', async ({ page }) => sweep(page, 'console', 'audit', '/team', { signedIn: true }));
test('delivery ops (map)', async ({ page }) => sweep(page, 'console', 'delivery', '/delivery', { signedIn: true }));
