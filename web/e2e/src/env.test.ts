import { describe, expect, it } from 'vitest';
import { loadEnv } from './env';

describe('suite configuration', () => {
  it('local mode: the stack ci/e2e.sh starts and the seeded personas', () => {
    const env = loadEnv({ E2E_RUN_ID: '261003120000' });
    expect(env.mode).toBe('local');
    expect(env.urls).toMatchObject({ studio: 'http://localhost:3100', consumer: 'http://localhost:3000', console: 'http://localhost:3200', api: 'http://localhost:8080' });
    expect(env.personas.owner.totpSecret).toBeTruthy();
    expect(env.runId).toBe('261003120000');
  });

  it('target mode: every URL is required, there is no api to reach directly, secrets override the data file', () => {
    expect(() => loadEnv({ E2E_MODE: 'target' })).toThrow(/E2E_STUDIO_URL/);
    const env = loadEnv({
      E2E_MODE: 'target', E2E_ENV: 'staging',
      E2E_STUDIO_URL: 'https://studio.example', E2E_CONSUMER_URL: 'https://www.example', E2E_CONSOLE_URL: 'https://console.example',
      E2E_AUTH_URL: 'https://auth.example', E2E_COURIER_API_URL: 'https://api.example',
      E2E_OWNER_IDENTIFIER: 'owner@example.test', E2E_OWNER_TOTP_SECRET: 'GEZDGNBVGY3TQOJQ',
      E2E_CONSUMER_PASSKEY: JSON.stringify({ credentialId: 'c', privateKey: 'k', rpId: 'example', userHandle: 'u', signCount: 0, isResidentCredential: true }),
    });
    expect(env.name).toBe('staging');
    expect(env.urls.api).toBeUndefined();
    expect(env.personas.owner).toMatchObject({ identifier: 'owner@example.test', totpSecret: 'GEZDGNBVGY3TQOJQ' });
    expect(env.personas.consumer?.passkey?.rpId).toBe('example');
  });
});
