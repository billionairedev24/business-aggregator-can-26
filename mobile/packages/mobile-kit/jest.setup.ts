/* eslint-disable @typescript-eslint/no-require-imports */
// Native modules stand in with Node's: secure random numbers and UUIDs (expo-crypto), a Map for the Keychain.
jest.mock('expo-crypto', () => {
  const nodeCrypto = require('crypto') as typeof import('crypto');
  return {
    getRandomBytes: (n: number) => new Uint8Array(nodeCrypto.randomBytes(n)),
    getRandomValues: (a: Uint8Array) => nodeCrypto.randomFillSync(a),
    randomUUID: () => nodeCrypto.randomUUID(),
  };
});
jest.mock('expo-secure-store', () => {
  const m = new Map<string, string>();
  return {
    AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY: 'AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY',
    getItemAsync: jest.fn(async (k: string) => m.get(k) ?? null),
    setItemAsync: jest.fn(async (k: string, v: string) => void m.set(k, v)),
    deleteItemAsync: jest.fn(async (k: string) => void m.delete(k)),
  };
});
jest.mock('expo-localization', () => ({ getLocales: jest.fn(() => [{ languageTag: 'en-CA' }]) }));
