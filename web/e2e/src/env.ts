import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Where the suite runs and as whom (docs/runbooks/e2e.md).
 *
 * - `local` (`make e2e`): the stack `ci/e2e.sh` starts — profile `local`, disposable database with the dev seed. The
 *   seeded personas sign in, new accounts read their codes from the local fakes' outboxes, and the local-only routes
 *   (outbox, payout run) are used.
 * - `target` (`make e2e-target ENV=staging`): a deployed environment. Base URLs and credentials come from the
 *   environment (CI secrets); nothing local-only exists there, so the steps that need it are skipped with a reason.
 */
export type Mode = 'local' | 'target';

export interface Persona {
  /** Email or mobile, as typed on the sign-in page. */
  identifier: string;
  firstName?: string;
  /** Authenticator-app key (base32). */
  totpSecret?: string;
  /** Printed backup codes, tried in order (each is single use). */
  backupCodes?: string[];
  userId?: string;
}

/** A passkey exported from Chrome's virtual authenticator (WebAuthn.getCredentials), re-imported to sign in. */
export interface PasskeyCredential {
  credentialId: string;
  privateKey: string;
  rpId: string;
  userHandle: string;
  signCount: number;
  isResidentCredential: boolean;
}

export interface TestData {
  market: {
    /** The market as the region model names it (dispatch plans per market). */
    name: string;
    timeZone: string;
    geolocation: { latitude: number; longitude: number };
    address: { street: string; postalCode: string };
  };
  provider: { merchantId: string; slug: string; name: string; serviceCategory: string };
  seller: { merchantId: string; name: string; departmentLevel1: string; departmentLevel2: string };
  onboarding: { type: 'seller' | 'provider'; department: string; categoryLevel1: string; categoryLevel2: string; gstNumber: string; address: string };
  personas: { owner: Persona; staff: Persona; courier: Persona; consumer?: Persona & { passkey?: PasskeyCredential } };
}

const root = new URL('..', import.meta.url).pathname;

function required(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name];
  if (!value) throw new Error(`${name} is not set — target mode needs it (docs/runbooks/e2e.md § Target an environment)`);
  return value;
}

/** Environment variables override the data file's personas, so CI secrets never sit in a file. */
function personaFromEnv(env: NodeJS.ProcessEnv, prefix: string, base: Persona | undefined): Persona | undefined {
  const identifier = env[`${prefix}_IDENTIFIER`];
  const totpSecret = env[`${prefix}_TOTP_SECRET`];
  const userId = env[`${prefix}_USER_ID`];
  const backupCodes = env[`${prefix}_BACKUP_CODES`]?.split(',').map(s => s.trim()).filter(Boolean);
  if (!identifier && !base) return undefined;
  return { ...base, ...(identifier ? { identifier } : {}), ...(totpSecret ? { totpSecret } : {}), ...(userId ? { userId } : {}), ...(backupCodes ? { backupCodes } : {}) } as Persona;
}

export function loadEnv(env: NodeJS.ProcessEnv = process.env) {
  const mode: Mode = env.E2E_MODE === 'target' ? 'target' : 'local';
  const dataFile = env.E2E_DATA ?? join(root, 'data', mode === 'local' ? 'local.json' : 'target.example.json');
  const data = JSON.parse(readFileSync(dataFile, 'utf8')) as TestData;
  const out = env.E2E_OUT ?? join(root, '..', '..', 'e2e-out');
  const url = (name: string, local: string) => (mode === 'local' ? env[name] ?? local : env[name] ?? required(env, name));
  const personas = {
    owner: personaFromEnv(env, 'E2E_OWNER', data.personas.owner)!,
    staff: personaFromEnv(env, 'E2E_STAFF', data.personas.staff)!,
    courier: personaFromEnv(env, 'E2E_COURIER', data.personas.courier)!,
    consumer: personaFromEnv(env, 'E2E_CONSUMER', data.personas.consumer) as TestData['personas']['consumer'],
  };
  if (env.E2E_CONSUMER_PASSKEY && personas.consumer) personas.consumer.passkey = JSON.parse(env.E2E_CONSUMER_PASSKEY) as PasskeyCredential;
  return {
    mode,
    name: env.E2E_ENV ?? mode,
    data,
    personas,
    urls: {
      studio: url('E2E_STUDIO_URL', 'http://localhost:3100'),
      consumer: url('E2E_CONSUMER_URL', 'http://localhost:3000'),
      console: url('E2E_CONSOLE_URL', 'http://localhost:3200'),
      auth: url('E2E_AUTH_URL', 'http://localhost:9000'),
      /** The api itself — local only (its outbox); in target mode every call goes through a BFF. */
      api: mode === 'local' ? env.E2E_API_URL ?? 'http://localhost:8080' : undefined,
      /** Where the courier app calls the api (api.<zone> in the cloud; DPoP-bound tokens, no BFF). */
      courierApi: url('E2E_COURIER_API_URL', 'http://localhost:8080'),
    },
    out,
    /** Signed-in browser states and exported passkeys, written by the setup project. */
    stateDir: join(out, 'state'),
    /** Unique per run: names and descriptions carry it, so reruns against the same database never collide. */
    runId: env.E2E_RUN_ID ?? new Date().toISOString().replace(/\D/g, '').slice(2, 14),
  };
}

export type E2eEnv = ReturnType<typeof loadEnv>;
export const env = loadEnv();
export const isLocal = env.mode === 'local';
