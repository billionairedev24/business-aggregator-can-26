import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient } from '@tanstack/react-query';
import { Platform } from 'react-native';

import {
  ApiClient,
  ApiError,
  DpopSession,
  deviceSecureStorage,
  memorySecureStorage,
  randomId,
  type Locale,
  type SecureStorage,
} from '@northline/mobile-kit';

import { CourierApi, type Run } from './api/courier';
import { config } from './config';
import { createFixtureServer, type FixtureServer } from './fixtures/server';
import { Outbox, type KeyValueStore, type OutboxAction } from './offline/outbox';
import { releaseProofFile } from './proof/files';

export const RUN_KEY = ['run'] as const;
export const ME_KEY = ['me'] as const;
export const SHIFTS_KEY = ['shifts'] as const;

export interface Services {
  session: DpopSession;
  api: ApiClient;
  courier: CourierApi;
  outbox: Outbox;
  queryClient: QueryClient;
  fixtures: FixtureServer | null;
  setLanguage(locale: Locale): void;
}

export interface ServiceOptions {
  fetchImpl?: typeof fetch;
  secureStorage?: SecureStorage;
  store?: KeyValueStore;
  fixtures?: FixtureServer | null;
}

/**
 * Everything the screens and the background location task share. One instance per app process
 * ({@link services}); tests build their own with fakes.
 */
export function createServices(options: ServiceOptions = {}): Services {
  const fixtures = options.fixtures !== undefined ? options.fixtures : config.fixtures ? createFixtureServer() : null;
  const fetchImpl = options.fetchImpl ?? fixtures?.fetch ?? ((...a: Parameters<typeof fetch>) => fetch(...a));
  const secure = options.secureStorage ?? (Platform.OS === 'web' || fixtures ? memorySecureStorage() : deviceSecureStorage);
  let language: Locale = 'en';
  const session = new DpopSession(
    { issuer: config.authIssuer, clientId: config.clientId, redirectUri: config.redirectUri, scopes: config.scopes },
    secure,
    fetchImpl,
  );
  const api = new ApiClient(config.apiUrl, session, () => language, fetchImpl);
  const courier = new CourierApi(api, fetchImpl);
  const queryClient = new QueryClient({
    defaultOptions: {
      // a refusal (403 not_a_courier, 404) is final; no answer or a 5xx is worth one more try
      queries: { retry: (count, e) => count < 1 && !(e instanceof ApiError && !e.transient), staleTime: 10_000, refetchOnWindowFocus: true },
      mutations: { retry: 0 },
    },
  });
  const send = (a: OutboxAction): Promise<Run | null> => {
    switch (a.kind) {
      case 'arrive':
        return courier.arrive(a.stopId, a.id);
      case 'pickup':
        return courier.pickup(a.stopId, a.scanOk, a.id);
      case 'proof':
        return courier.uploadProof(a.stopId, a.proofKind, a.file, a.id);
      case 'dropoff':
        return courier.dropoff(a.stopId, a.proof, a.pin, a.id);
    }
  };
  const outbox = new Outbox({
    store: options.store ?? AsyncStorage,
    send,
    newId: randomId,
    release: (a) => {
      if (a.kind === 'proof') releaseProofFile(a.file);
    },
    onRun: (run) => {
      if (!run) return;
      if (run.state === 'done') {
        queryClient.setQueryData(RUN_KEY, null);
        void queryClient.invalidateQueries({ queryKey: ME_KEY });
      } else {
        queryClient.setQueryData(RUN_KEY, run);
      }
    },
  });
  session.onChange((signedIn) => {
    if (signedIn) outbox.wake();
    else queryClient.clear();
  });
  return {
    session,
    api,
    courier,
    outbox,
    queryClient,
    fixtures,
    setLanguage: (l) => {
      language = l;
    },
  };
}

let instance: Services | null = null;

/** The app's services (created on first use: the root layout, or the background task after a cold start). */
export function services(): Services {
  instance ??= createServices();
  return instance;
}

/** Tests: use these services instead. */
export function setServices(s: Services | null) {
  instance = s;
}
