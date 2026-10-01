import * as SecureStore from 'expo-secure-store';

/**
 * Small secrets kept on the device: the DPoP key and the refresh token. Native: the iOS Keychain / Android Keystore-
 * encrypted storage (expo-secure-store), readable after the first unlock so the background location task can refresh
 * while the phone is locked, and never migrated to another device or backup (`…_THIS_DEVICE_ONLY`).
 */
export interface SecureStorage {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  remove(key: string): Promise<void>;
}

const OPTIONS: SecureStore.SecureStoreOptions = {
  keychainAccessible: SecureStore.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
};

export const deviceSecureStorage: SecureStorage = {
  get: (key) => SecureStore.getItemAsync(key, OPTIONS),
  set: (key, value) => SecureStore.setItemAsync(key, value, OPTIONS),
  remove: (key) => SecureStore.deleteItemAsync(key, OPTIONS),
};

/** In memory: tests, the web preview and fixture mode (nothing survives a reload). */
export function memorySecureStorage(): SecureStorage & { readonly values: Map<string, string> } {
  const values = new Map<string, string>();
  return {
    values,
    get: async (key) => values.get(key) ?? null,
    set: async (key, value) => void values.set(key, value),
    remove: async (key) => void values.delete(key),
  };
}
