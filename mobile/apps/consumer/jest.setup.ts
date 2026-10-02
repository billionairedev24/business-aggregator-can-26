/* eslint-disable @typescript-eslint/no-require-imports */
// Native modules stand in for Jest: Node's crypto, Maps for the Keychain and AsyncStorage, a scripted location.
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
    WHEN_UNLOCKED_THIS_DEVICE_ONLY: 'WHEN_UNLOCKED_THIS_DEVICE_ONLY',
    getItemAsync: jest.fn(async (k: string) => m.get(k) ?? null),
    setItemAsync: jest.fn(async (k: string, v: string) => void m.set(k, v)),
    deleteItemAsync: jest.fn(async (k: string) => void m.delete(k)),
  };
});
jest.mock('@react-native-async-storage/async-storage', () => require('@react-native-async-storage/async-storage/jest/async-storage-mock'));
type MockNetState = { isConnected: boolean; isInternetReachable: boolean };
type MockNetListener = (state: MockNetState) => void;
jest.mock('@react-native-community/netinfo', () => {
  const listeners = new Set<MockNetListener>();
  return {
    __esModule: true,
    default: {
      addEventListener: jest.fn((l: MockNetListener) => {
        listeners.add(l);
        return () => listeners.delete(l);
      }),
      fetch: jest.fn(async () => ({ isConnected: true, isInternetReachable: true })),
    },
    __emit: (state: MockNetState) => listeners.forEach((l) => l(state)),
  };
});
jest.mock('expo-localization', () => ({ getLocales: jest.fn(() => [{ languageTag: 'en-CA' }]) }));
jest.mock('expo-web-browser', () => ({
  openAuthSessionAsync: jest.fn(async (url: string, redirect: string) => {
    const state = new URL(url).searchParams.get('state');
    return { type: 'success', url: `${redirect}?code=from-browser&state=${state}` };
  }),
  openBrowserAsync: jest.fn(async () => ({ type: 'opened' })),
  maybeCompleteAuthSession: jest.fn(() => ({ type: 'failed' })),
}));
jest.mock('expo-location', () => ({
  Accuracy: { Balanced: 3 },
  getForegroundPermissionsAsync: jest.fn(async () => ({ status: 'undetermined', granted: false, canAskAgain: true })),
  requestForegroundPermissionsAsync: jest.fn(async () => ({ status: 'granted', granted: true, canAskAgain: true })),
  getCurrentPositionAsync: jest.fn(async () => ({ coords: { latitude: 45.1, longitude: -75.2, accuracy: 30 } })),
  getLastKnownPositionAsync: jest.fn(async () => null),
}));
jest.mock('expo-linking', () => ({
  ...jest.requireActual('expo-linking'),
  openURL: jest.fn(async () => true),
  openSettings: jest.fn(async () => undefined),
}));
jest.mock('expo-constants', () => ({ __esModule: true, default: { expoConfig: { version: '0.1.0' } } }));
