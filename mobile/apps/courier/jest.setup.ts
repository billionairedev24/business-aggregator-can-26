/* eslint-disable @typescript-eslint/no-require-imports */
// Native modules stand in for Jest: Node's crypto, Maps for the Keychain and AsyncStorage, no device sensors.
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
  maybeCompleteAuthSession: jest.fn(() => ({ type: 'failed' })),
}));
jest.mock('expo-task-manager', () => ({
  isTaskDefined: jest.fn(() => false),
  defineTask: jest.fn(),
}));
jest.mock('expo-location', () => ({
  Accuracy: { High: 4, Balanced: 3 },
  ActivityType: { OtherNavigation: 4 },
  getForegroundPermissionsAsync: jest.fn(async () => ({ status: 'undetermined', canAskAgain: true })),
  getBackgroundPermissionsAsync: jest.fn(async () => ({ status: 'undetermined', canAskAgain: true })),
  requestForegroundPermissionsAsync: jest.fn(async () => ({ status: 'granted' })),
  requestBackgroundPermissionsAsync: jest.fn(async () => ({ status: 'granted' })),
  hasStartedLocationUpdatesAsync: jest.fn(async () => false),
  startLocationUpdatesAsync: jest.fn(async () => undefined),
  stopLocationUpdatesAsync: jest.fn(async () => undefined),
  watchPositionAsync: jest.fn(async () => ({ remove: jest.fn() })),
}));
jest.mock('expo-camera', () => {
  const { View } = require('react-native');
  return {
    CameraView: View,
    useCameraPermissions: jest.fn(() => [{ granted: false, canAskAgain: true }, jest.fn()]),
  };
});
jest.mock('expo-image-manipulator', () => ({ ImageManipulator: { manipulate: jest.fn() }, SaveFormat: { JPEG: 'jpeg' } }));
jest.mock('expo-file-system', () => {
  class Directory {
    exists = true;
    uri: string;
    constructor(...parts: Array<string | { uri: string }>) {
      this.uri = parts.map((p) => (typeof p === 'string' ? p : p.uri)).join('/');
    }
    create() {}
  }
  class File extends Directory {
    write = jest.fn();
    delete = jest.fn();
    move = jest.fn(async () => undefined);
  }
  return { Directory, File, Paths: { document: { uri: 'file:///doc' }, cache: { uri: 'file:///cache' } } };
});
jest.mock('expo-constants', () => ({ __esModule: true, default: { expoConfig: { version: '0.1.0' } } }));
// Push (mobile gaps part 1): expo-notifications as a scripted phone — permission, token, taps (`__phone` drives it).
type MockTokenListener = (token: { data: unknown }) => void;
type MockTapListener = (response: unknown) => void;
const mockPhone = {
  permission: 'undetermined' as string,
  token: 'apns-device-token-0001' as string | null,
  asked: 0,
  tokenListeners: new Set<MockTokenListener>(),
  tapListeners: new Set<MockTapListener>(),
  launch: null as unknown,
};
const mockResponse = (data: Record<string, unknown>) => ({ notification: { request: { content: { data } } } });
jest.mock('expo-notifications', () => ({
  __phone: {
    state: mockPhone,
    tap: (data: Record<string, unknown>) => mockPhone.tapListeners.forEach((l) => l(mockResponse(data))),
    launchWith: (data: Record<string, unknown> | null) => (mockPhone.launch = data ? mockResponse(data) : null),
    reset: () => {
      mockPhone.permission = 'undetermined';
      mockPhone.token = 'apns-device-token-0001';
      mockPhone.asked = 0;
      mockPhone.launch = null;
      mockPhone.tokenListeners.clear();
      mockPhone.tapListeners.clear();
    },
  },
  AndroidImportance: { DEFAULT: 3 },
  setNotificationHandler: jest.fn(),
  setNotificationChannelAsync: jest.fn(async () => null),
  getPermissionsAsync: jest.fn(async () => ({ status: mockPhone.permission })),
  requestPermissionsAsync: jest.fn(async () => {
    mockPhone.asked++;
    mockPhone.permission = 'granted';
    return { status: mockPhone.permission };
  }),
  getDevicePushTokenAsync: jest.fn(async () => ({ type: 'ios', data: mockPhone.token })),
  addPushTokenListener: jest.fn((l: MockTokenListener) => (mockPhone.tokenListeners.add(l), { remove: () => mockPhone.tokenListeners.delete(l) })),
  addNotificationResponseReceivedListener: jest.fn((l: MockTapListener) => (mockPhone.tapListeners.add(l), { remove: () => mockPhone.tapListeners.delete(l) })),
  getLastNotificationResponseAsync: jest.fn(async () => mockPhone.launch),
}));
// Photos (mobile gaps part 1): expo-image-picker returns what the test queued (`__queue`), else "cancelled".
const mockPicked: unknown[] = [];
const mockCamera = { granted: true };
const mockPick = async () => {
  const asset = mockPicked.shift();
  return asset ? { canceled: false, assets: [asset] } : { canceled: true, assets: null };
};
jest.mock('expo-image-picker', () => ({
  __queue: mockPicked,
  __camera: mockCamera,
  UIImagePickerPreferredAssetRepresentationMode: { Automatic: 'automatic', Compatible: 'compatible', Current: 'current' },
  requestCameraPermissionsAsync: jest.fn(async () => ({ granted: mockCamera.granted, status: mockCamera.granted ? 'granted' : 'denied' })),
  launchCameraAsync: jest.fn(mockPick),
  launchImageLibraryAsync: jest.fn(mockPick),
}));
// The screen tests run without push (the app installs it at start on a phone); __tests__/push.test.tsx turns it on.
require('./src/push/install').setPushAvailable(false);
