import { renderRouter } from 'expo-router/testing-library';

import { memorySecureStorage } from '@northline/mobile-kit';

import { WELCOMED_KEY } from '../src/auth/AuthProvider';
import { createFixtureServer, type FixtureOptions } from '../src/fixtures/server';
import { createServices, setServices, type KeyValueStore, type Services } from '../src/services';

export function memoryStore(initial: Record<string, string> = {}): KeyValueStore & { data: Map<string, string> } {
  const data = new Map(Object.entries(initial));
  return {
    data,
    getItem: async (k) => data.get(k) ?? null,
    setItem: async (k, v) => void data.set(k, v),
    removeItem: async (k) => void data.delete(k),
  };
}

export interface StartOptions {
  /** Signed in already (a previous run's tokens). */
  signedIn?: boolean;
  /** Welcome already seen (default: true when signed in). */
  welcomed?: boolean;
  url?: string;
  fixture?: FixtureOptions;
  /** Wrap the fixture backend's fetch (to fail or slow requests). */
  wrap?: (f: typeof fetch) => typeof fetch;
  store?: Record<string, string>;
}

/** The app on the fixture backend (the same in-memory servers the web smoke test uses). */
export async function start(options: StartOptions = {}) {
  const server = createFixtureServer(options.fixture);
  const store = memoryStore({ ...(options.welcomed ?? options.signedIn ? { [WELCOMED_KEY]: '1' } : {}), ...(options.store ?? {}) });
  const services: Services = createServices({
    fixtures: server,
    fetchImpl: options.wrap ? options.wrap(server.fetch) : server.fetch,
    secureStorage: memorySecureStorage(),
    store,
  });
  setServices(services);
  if (options.signedIn) {
    const p = services.session.beginSignIn();
    await services.session.completeSignIn(`ca.northline.app:/oauth2redirect?code=c&state=${p.state}`, p);
  }
  const view = renderRouter('./app', { initialUrl: options.url ?? '/' });
  return { server, services, store, view };
}
