import NetInfo from '@react-native-community/netinfo';
import { router } from 'expo-router';
import { useEffect, useState, type ReactNode } from 'react';
import { ActivityIndicator, StyleSheet, Text, View, type DimensionValue } from 'react-native';

import { ApiError, NetworkError, SignedOutError, colors, radius, space } from '@northline/mobile-kit';

import { useI18n, type MessageKey } from '../i18n';
import { Body, Button, type } from './primitives';

/**
 * The states every screen implements (docs/SCREENS.md § Every screen must implement): loading (a skeleton shaped like
 * the screen), empty (one line + the primary action), error (rosehip inline, with Retry), offline. `QueryView` wires
 * them to a react-query result so a screen only writes its happy path.
 */

/** Connectivity as NetInfo reports it (true until it says otherwise). */
export function useOnline(): boolean {
  const [online, setOnline] = useState(true);
  useEffect(() => NetInfo.addEventListener((s) => setOnline(s.isConnected !== false && s.isInternetReachable !== false)), []);
  return online;
}

export function OfflineBanner() {
  const online = useOnline();
  const { t } = useI18n();
  if (online) return null;
  return (
    <View accessibilityRole="alert" accessibilityLiveRegion="polite" style={styles.offline} testID="offline-banner">
      <Text style={type.small}>{t('common.offline')}</Text>
    </View>
  );
}

/** A grey block standing in for content while it loads. */
export function Skeleton({ height = 16, width = '100%', round }: { height?: number; width?: DimensionValue; round?: boolean }) {
  return <View style={[styles.skeleton, { height, width }, round && { borderRadius: height / 2 }]} />;
}

/** Rows of skeletons (a list), announced once as "Loading…". */
export function LoadingList({ rows = 4, height = 64 }: { rows?: number; height?: number }) {
  const { t } = useI18n();
  return (
    <View accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.list} testID="loading">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} height={height} />
      ))}
    </View>
  );
}

/** A whole screen waiting (before anything is known). */
export function Loading() {
  const { t } = useI18n();
  return (
    <View style={styles.center} accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} testID="loading">
      <ActivityIndicator color={colors.accent} size="large" />
    </View>
  );
}

export function EmptyState({ message, action, onAction }: { message: string; action?: string; onAction?: () => void }) {
  return (
    <View style={styles.empty} testID="empty">
      <Body tone="muted">{message}</Body>
      {action && onAction ? <Button label={action} onPress={onAction} /> : null}
    </View>
  );
}

/** What went wrong, in words for the person: no connection, the api's own message, or a generic line. */
export function errorMessage(error: unknown, t: (k: MessageKey, p?: Record<string, string | number>) => string): string {
  if (error instanceof NetworkError) return t('common.network');
  if (error instanceof SignedOutError) return t('common.signedOut');
  if (error instanceof ApiError) {
    if (error.status === 429) return t('common.tooMany');
    if (error.status >= 500) return t('common.serverError');
    return error.message;
  }
  return t('common.error');
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const { t } = useI18n();
  const signedOut = error instanceof SignedOutError;
  return (
    <View accessibilityRole="alert" style={styles.error} testID="error">
      <Text style={styles.errorText}>{errorMessage(error, t)}</Text>
      {signedOut ? (
        <Button label={t('common.signIn')} tone="secondary" onPress={() => router.push('/sign-in')} />
      ) : onRetry ? (
        <Button label={t('common.retry')} tone="secondary" onPress={onRetry} />
      ) : null}
    </View>
  );
}

/** For screens only a signed-in person can use (orders, account…): one line and the way in. */
export function SignInPrompt({ message }: { message: string }) {
  const { t } = useI18n();
  return (
    <View style={styles.empty} testID="sign-in-prompt">
      <Body tone="muted">{message}</Body>
      <Button label={t('common.signIn')} onPress={() => router.push('/sign-in')} />
      <Button label={t('common.createAccount')} tone="secondary" onPress={() => router.push('/sign-up')} />
    </View>
  );
}

/**
 * A query's three states around the screen's content: `skeleton` while loading (default: a list), `ErrorState` with
 * Retry (data already shown stays, with the error above it — a flaky network shouldn't blank a screen), `empty` when
 * `isEmpty(data)`.
 */
export function QueryView<T>({
  query,
  skeleton,
  isEmpty,
  empty,
  children,
}: {
  /** A react-query result (or anything shaped like one). */
  query: { data: T | undefined; error: unknown; isPending: boolean; isError: boolean; refetch: () => unknown };
  skeleton?: ReactNode;
  isEmpty?: (data: T) => boolean;
  empty?: ReactNode;
  children: (data: T) => ReactNode;
}) {
  const retry = () => void query.refetch();
  if (query.isPending) return <>{skeleton ?? <LoadingList />}</>;
  if (query.data === undefined) return <ErrorState error={query.error} onRetry={retry} />;
  return (
    <>
      {query.isError ? <ErrorState error={query.error} onRetry={retry} /> : null}
      {isEmpty?.(query.data) ? empty : children(query.data)}
    </>
  );
}

const styles = StyleSheet.create({
  offline: { marginHorizontal: 24, marginTop: space[2], padding: space[3], borderRadius: radius.md, backgroundColor: colors.highlight100 },
  skeleton: { backgroundColor: colors.neutral200, borderRadius: radius.md },
  list: { gap: space[3] },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: space[8], backgroundColor: colors.bg },
  empty: { gap: space[3], paddingVertical: space[6] },
  error: { gap: space[2], padding: space[3], borderRadius: radius.md, borderWidth: 1, borderColor: colors.accent2, backgroundColor: colors.accent2_100 },
  errorText: { ...StyleSheet.flatten(type.body), color: colors.accent2_700 },
});
