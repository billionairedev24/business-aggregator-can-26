import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';

import { ApiError, colors, radius, semantic, space } from '@northline/mobile-kit';

import { hireApi, type Message, type ThreadPage, type ThreadRef, type ThreadRow } from '../api/hire';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n';
import { services } from '../services';
import { Body, Button, Field, Notice, Tag, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, errorMessage, LoadingList, QueryView, SignInPrompt } from '../ui/states';

export const hire = () => hireApi(services().api);

export const THREADS_KEY = ['hire', 'threads'] as const;
export const threadKey = (id: string) => ['hire', 'threads', id] as const;
/** The push says when the business writes (S-153, row `quotes_messages`); meanwhile the open thread is read every 15 s. */
const THREAD_POLL_MS = 15_000;
const BODY_MAX = 2000;

/**
 * Opens (or finds) the thread of a quote request or a booking and goes to it — the quote's "Ask a question" and the
 * booking's "Message …". The api checks the request or the booking is the person's.
 */
export function useOpenThread() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (ref: ThreadRef) => hire().openThread(ref),
    onSuccess: (page) => {
      qc.setQueryData(threadKey(page.thread.id), page);
      void qc.invalidateQueries({ queryKey: THREADS_KEY, exact: true });
      router.push(`/messages/${encodeURIComponent(page.thread.id)}` as never);
    },
  });
}

/**
 * `/messages` (S-153): the person's conversations with providers, about a quote request or a booking, most recent
 * first; unread ones marked. Signed in only.
 */
export function Messages() {
  const { t } = useI18n();
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const list = useQuery({ queryKey: THREADS_KEY, queryFn: () => hire().threads(), enabled: signedIn, staleTime: 15_000 });
  return (
    <Screen title={t('title.messages')} testID="messages" refreshControl={signedIn ? <RefreshControl refreshing={list.isRefetching} onRefresh={() => void list.refetch()} /> : undefined}>
      {!signedIn ? (
        <SignInPrompt message={t('hire.messages.signIn')} />
      ) : (
        <QueryView
          query={list}
          skeleton={<LoadingList rows={4} height={64} />}
          isEmpty={(items) => items.length === 0}
          empty={<EmptyState draw={{ main: 'Handshake', side: 'CalendarCheck' }} message={t('hire.messages.empty')} />}
        >
          {(items) => (
            <View accessibilityRole="list" style={styles.list}>
              {items.map((r) => (
                <ThreadItem key={r.id} row={r} />
              ))}
            </View>
          )}
        </QueryView>
      )}
    </Screen>
  );
}

function ThreadItem({ row: r }: { row: ThreadRow }) {
  const { t, day, time } = useI18n();
  const about = [r.refType === 'quote' ? t('hire.messages.aboutQuote') : t('hire.messages.aboutBooking'), r.refCode].filter(Boolean).join(' ');
  const when = r.lastMessageAt ? `${day(r.lastMessageAt)} ${time(r.lastMessageAt)}` : '';
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={[r.businessName, about, r.unread ? t('hire.messages.unread') : null, r.lastMessage, when].filter(Boolean).join(', ')}
      onPress={() => router.push(`/messages/${encodeURIComponent(r.id)}` as never)}
      style={({ pressed }) => [styles.row, pressed && styles.pressed]}
      testID={`thread-${r.id}`}
    >
      <View style={styles.flex}>
        <Text style={[type.body, type.strong]}>{r.businessName}</Text>
        <Text style={type.small}>{[about, r.subject].filter(Boolean).join(' · ')}</Text>
        {r.lastMessage ? (
          <Text style={type.small} numberOfLines={1}>
            {r.lastMessage}
          </Text>
        ) : null}
      </View>
      <View style={styles.side}>
        {when ? <Text style={type.small}>{when}</Text> : null}
        {r.unread ? <Tag label={t('hire.messages.new')} tone="accent" small /> : null}
      </View>
    </Pressable>
  );
}

/**
 * `/messages/<id>` (S-153; the push's `/app/messages/<id>` lands here): one conversation — the business's messages and
 * the person's, oldest first; what reads like contact details or paying off the platform is masked by the api and
 * marked here. Read once open; asked again every 15 s while open.
 */
export function Thread({ id }: { id: string }) {
  const { t } = useI18n();
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const qc = useQueryClient();
  const page = useQuery({ queryKey: threadKey(id), queryFn: () => hire().thread(id), enabled: signedIn && !!id, refetchInterval: THREAD_POLL_MS });
  const unread = page.data?.thread.unread;
  useEffect(() => {
    if (!unread) return;
    void hire()
      .markRead(id)
      .then(() => qc.invalidateQueries({ queryKey: THREADS_KEY, exact: true }))
      .catch(() => undefined);
  }, [id, unread, qc]);
  const title = page.data ? page.data.thread.businessName : t('title.messages');
  return (
    <Screen title={title} testID="thread" footer={signedIn && page.data ? <Composer id={id} /> : undefined}>
      {!signedIn ? (
        <SignInPrompt message={t('hire.messages.signIn')} />
      ) : (
        <QueryView query={page} skeleton={<LoadingList rows={4} height={48} />}>
          {(p) => <Conversation page={p} />}
        </QueryView>
      )}
    </Screen>
  );
}

function Conversation({ page: { thread: th, messages } }: { page: ThreadPage }) {
  const { t } = useI18n();
  const about = th.refType === 'quote' ? t('hire.messages.aboutQuote') : t('hire.messages.aboutBooking');
  const open = () => router.push((th.refType === 'booking' ? `/bookings/${encodeURIComponent(th.refId)}/eta` : '/orders') as never);
  return (
    <View style={styles.stack}>
      <Pressable accessibilityRole="link" onPress={open} testID="thread-about">
        <Body tone="small">{[about, th.refCode, th.subject].filter(Boolean).join(' · ')}</Body>
      </Pressable>
      <Body tone="small">{t('hire.messages.onPlatform')}</Body>
      {messages.length === 0 ? <Body tone="muted">{t('hire.messages.first', { name: th.businessName })}</Body> : null}
      <View accessibilityRole="list" style={styles.messages}>
        {messages.map((m) => (
          <Bubble key={m.id} m={m} business={th.businessName} />
        ))}
      </View>
    </View>
  );
}

function Bubble({ m, business }: { m: Message; business: string }) {
  const { t, day, time } = useI18n();
  const mine = m.from === 'me';
  const who = mine ? t('hire.messages.you') : m.from === 'business' ? business : t('hire.messages.northline');
  return (
    <View accessible accessibilityLabel={`${who}, ${day(m.at)} ${time(m.at)}: ${m.body}`} style={[styles.bubble, mine ? styles.mine : styles.theirs]} testID={`message-${m.id}`}>
      <Text style={type.small}>{`${who} · ${time(m.at)}`}</Text>
      <Text style={type.body}>{m.body}</Text>
      {m.flagged ? <Text style={type.small}>{t('hire.messages.flagged')}</Text> : null}
    </View>
  );
}

function Composer({ id }: { id: string }) {
  const { t } = useI18n();
  const qc = useQueryClient();
  const [body, setBody] = useState('');
  const [error, setError] = useState<string | null>(null);
  const send = useMutation({
    mutationFn: (text: string) => hire().send(id, text),
    onSuccess: (m) => {
      setBody('');
      qc.setQueryData(threadKey(id), (old: ThreadPage | undefined) => (old ? { ...old, messages: [...old.messages, m] } : old));
      void qc.invalidateQueries({ queryKey: THREADS_KEY, exact: true });
    },
    onError: (e) => setError(e instanceof ApiError && e.status === 422 ? (e.fieldMessage('body') ?? errorMessage(e, t)) : errorMessage(e, t)),
  });
  const submit = () => {
    const text = body.trim();
    if (!text) return setError(t('hire.messages.v.required'));
    if (text.length > BODY_MAX) return setError(t('hire.messages.v.long', { max: BODY_MAX }));
    setError(null);
    send.mutate(text);
  };
  return (
    <View style={styles.composer}>
      <Field label={t('hire.messages.write')} value={body} onChangeText={setBody} error={error} multiline maxLength={BODY_MAX + 100} testID="message-body" />
      {send.isError && !(send.error instanceof ApiError && send.error.status === 422) ? <Notice message={errorMessage(send.error, t)} /> : null}
      <Button label={t('hire.messages.send')} busy={send.isPending} onPress={submit} testID="message-send" />
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, minWidth: 0, gap: 2 },
  list: { gap: space[3] },
  stack: { gap: space[3], paddingTop: space[2] },
  row: { flexDirection: 'row', gap: space[3], padding: 14, minHeight: 56, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.surface },
  pressed: { backgroundColor: semantic['surface-hover'] },
  side: { alignItems: 'flex-end', gap: 4 },
  messages: { gap: space[2] },
  bubble: { maxWidth: '85%', gap: 2, padding: space[3], borderRadius: radius.lg },
  mine: { alignSelf: 'flex-end', backgroundColor: colors.accent100 },
  theirs: { alignSelf: 'flex-start', backgroundColor: semantic['surface-sunken'] },
  composer: { gap: space[2] },
});
