import * as WebBrowser from 'expo-web-browser';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Platform } from 'react-native';

import { OAuthError } from '@northline/mobile-kit';

import { config } from './config';
import { stopTracking } from './location/tracker';
import { services } from './services';

export type AuthStatus = 'loading' | 'signedIn' | 'signedOut';
export type SignInResult = { ok: true } | { ok: false; reason: 'cancelled' | 'webOnly' | 'failed'; detail?: string };

interface AuthContextValue {
  status: AuthStatus;
  /** The sign-in ended on the server (refresh refused) rather than by the courier. */
  ended: boolean;
  signIn(): Promise<SignInResult>;
  signOut(): Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Sign-in with the `courier-app` client (S-29): PKCE in the system browser (ASWebAuthenticationSession / Custom Tabs,
 * never a WebView — RFC 8252), northline-auth's consumer sign-in page, back to `ca.northline.courier:/oauth2redirect`.
 * In fixture mode the redirect is made up and the fixture server answers the token request.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [ended, setEnded] = useState(false);

  useEffect(() => {
    const { session, outbox } = services();
    let alive = true;
    void (async () => {
      const signedIn = await session.restore();
      await outbox.load();
      if (alive) setStatus(signedIn ? 'signedIn' : 'signedOut');
    })();
    const off = session.onChange((signedIn) => {
      setStatus(signedIn ? 'signedIn' : 'signedOut');
      if (!signedIn) void stopTracking();
    });
    return () => {
      alive = false;
      off();
    };
  }, []);

  const signIn = useCallback(async (): Promise<SignInResult> => {
    const { session } = services();
    const pending = session.beginSignIn();
    try {
      if (config.fixtures) {
        await session.completeSignIn(`${config.redirectUri}?code=fixture&state=${pending.state}`, pending);
      } else if (Platform.OS === 'web') {
        return { ok: false, reason: 'webOnly' };
      } else {
        const result = await WebBrowser.openAuthSessionAsync(pending.url, config.redirectUri, { preferEphemeralSession: false });
        if (result.type !== 'success') return { ok: false, reason: 'cancelled' };
        await session.completeSignIn(result.url, pending);
      }
      setEnded(false);
      return { ok: true };
    } catch (e) {
      return { ok: false, reason: 'failed', detail: e instanceof OAuthError ? (e.description ?? e.error) : e instanceof Error ? e.message : undefined };
    }
  }, []);

  const signOut = useCallback(async () => {
    const { session, outbox } = services();
    await stopTracking();
    await outbox.clear();
    await session.signOut();
    setEnded(false);
  }, []);

  // The server ended the sign-in (the outbox saw it): show "sign in again" rather than a plain sign-in.
  useEffect(
    () =>
      services().outbox.subscribe((s) => {
        if (s.needsSignIn) setEnded(true);
      }),
    [],
  );

  const value = useMemo(() => ({ status, ended, signIn, signOut }), [status, ended, signIn, signOut]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
