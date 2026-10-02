import * as WebBrowser from 'expo-web-browser';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Platform } from 'react-native';

import { OAuthError, PushHooks } from '@northline/mobile-kit';

import { config } from '../config';
import { services } from '../services';

export type AuthStatus = 'loading' | 'signedIn' | 'guest';
export type SignInResult = { ok: true } | { ok: false; reason: 'cancelled' | 'webOnly' | 'failed'; detail?: string };

/** "Welcome" was seen (Create account, Sign in or Browse first): the app opens on Home from then on. */
export const WELCOMED_KEY = 'nl.app.welcomed';

interface AuthContextValue {
  status: AuthStatus;
  /** The sign-in ended on the server (refresh refused, signed out elsewhere) rather than by the person. */
  ended: boolean;
  welcomed: boolean;
  markWelcomed(): Promise<void>;
  /**
   * The system-browser sign-in (RFC 8252): the authorization request opens in ASWebAuthenticationSession / Custom
   * Tabs, northline-auth sends the `mobile-consumer` client to the consumer site's sign-in page (passkeys, Google,
   * Apple), and the code comes back to `ca.northline.app:/oauth2redirect`.
   */
  signInWithBrowser(): Promise<SignInResult>;
  /** Tokens are in: tell the app (and S-102's push registrar). */
  signedIn(): void;
  signOut(): Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [ended, setEnded] = useState(false);
  const [welcomed, setWelcomed] = useState(false);
  const signingOut = useRef(false);

  useEffect(() => {
    const { session, store, loadGuestId } = services();
    let alive = true;
    void (async () => {
      const [signedIn, seen] = await Promise.all([session.restore(), store.getItem(WELCOMED_KEY).catch(() => null), loadGuestId()]);
      if (!alive) return;
      setWelcomed(seen === '1' || signedIn);
      setStatus(signedIn ? 'signedIn' : 'guest');
    })();
    const off = session.onChange((signedIn) => {
      setStatus(signedIn ? 'signedIn' : 'guest');
      if (!signedIn && !signingOut.current) setEnded(true);
    });
    return () => {
      alive = false;
      off();
    };
  }, []);

  const markWelcomed = useCallback(async () => {
    setWelcomed(true);
    await services().store.setItem(WELCOMED_KEY, '1').catch(() => undefined);
  }, []);

  const signedIn = useCallback(() => {
    setEnded(false);
    setStatus('signedIn');
    void markWelcomed();
    void PushHooks.signedIn(services().api);
  }, [markWelcomed]);

  const signInWithBrowser = useCallback(async (): Promise<SignInResult> => {
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
      signedIn();
      return { ok: true };
    } catch (e) {
      return { ok: false, reason: 'failed', detail: e instanceof OAuthError ? (e.description ?? e.error) : e instanceof Error ? e.message : undefined };
    }
  }, [signedIn]);

  const signOut = useCallback(async () => {
    const { session, api } = services();
    signingOut.current = true;
    try {
      await PushHooks.signingOut(api);
      await session.signOut();
    } finally {
      signingOut.current = false;
    }
    setEnded(false);
  }, []);

  const value = useMemo(
    () => ({ status, ended, welcomed, markWelcomed, signInWithBrowser, signedIn, signOut }),
    [status, ended, welcomed, markWelcomed, signInWithBrowser, signedIn, signOut],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
