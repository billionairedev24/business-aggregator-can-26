import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { AccessibilityInfo, Modal, Pressable, ScrollView, StyleSheet, Text, View, type DimensionValue } from 'react-native';

import { MIN_TARGET, colors, radius, semantic, space } from '../../theme';
import { Icon, type IconName } from '../Icon';
import { Button, IconButton } from './Button';
import { useKit } from './KitProvider';
import { LINE, SIZE, TYPE, textStyles } from './tokens';

/**
 * Feedback (Plainsight): the bottom sheet (the phone's dialog: a grabber, the title, Close, content that scrolls, the
 * home indicator's room), the ink toast, the empty state in the well grey with its icon, and skeletons in the well.
 */

export function BottomSheet({
  visible,
  onClose,
  title,
  children,
  footer,
  bottomInset = 0,
  testID,
}: {
  visible: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  /** A sticky action under the content (Apply, Save). */
  footer?: ReactNode;
  /** The home indicator's height (the app's safe-area inset). */
  bottomInset?: number;
  testID?: string;
}) {
  const { t } = useKit();
  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose} statusBarTranslucent>
      <View style={styles.sheetRoot}>
        <Pressable accessibilityRole="button" accessibilityLabel={t('kit.close')} onPress={onClose} style={styles.scrim} testID={testID ? `${testID}-scrim` : undefined} />
        <View accessibilityViewIsModal style={[styles.sheet, { paddingBottom: Math.max(bottomInset, space[3]) }]} testID={testID}>
          <View style={styles.grabber} />
          <View style={styles.sheetHead}>
            <Text accessibilityRole="header" style={[textStyles.section, styles.flex]}>
              {title}
            </Text>
            <IconButton icon="x" label={t('kit.close')} onPress={onClose} testID={testID ? `${testID}-close` : undefined} />
          </View>
          <ScrollView contentContainerStyle={styles.sheetBody} keyboardShouldPersistTaps="handled">
            {children}
          </ScrollView>
          {footer ? <View style={styles.sheetFoot}>{footer}</View> : null}
        </View>
      </View>
    </Modal>
  );
}

interface ToastMessage {
  id: number;
  text: string;
  tone: 'neutral' | 'success' | 'danger';
  action?: { label: string; onPress: () => void };
}

const ToastContext = createContext<((text: string, opts?: { tone?: ToastMessage['tone']; action?: ToastMessage['action'] }) => void) | null>(null);

/**
 * Toasts for the whole app: one at a time over the bottom bar, ink with white words (a success or danger icon when it
 * says so), announced to VoiceOver / TalkBack, gone after 4 s or on Dismiss. `bottomOffset` clears the tab bar.
 */
export function ToastProvider({ children, bottomOffset = space[16] + space[4] }: { children: ReactNode; bottomOffset?: number }) {
  const [toast, setToast] = useState<ToastMessage | null>(null);
  const seq = useRef(0);
  const show = useCallback((text: string, opts?: { tone?: ToastMessage['tone']; action?: ToastMessage['action'] }) => {
    seq.current += 1;
    setToast({ id: seq.current, text, tone: opts?.tone ?? 'neutral', action: opts?.action });
    AccessibilityInfo.announceForAccessibility?.(text);
  }, []);
  useEffect(() => {
    if (!toast) return undefined;
    const timer = setTimeout(() => setToast((t) => (t?.id === toast.id ? null : t)), 4000);
    return () => clearTimeout(timer);
  }, [toast]);
  return (
    <ToastContext.Provider value={show}>
      {children}
      {toast ? <Toast text={toast.text} tone={toast.tone} action={toast.action} onDismiss={() => setToast(null)} style={{ bottom: bottomOffset }} /> : null}
    </ToastContext.Provider>
  );
}

/** `const toast = useToast(); toast(t('cart.added'))`. Outside a {@link ToastProvider} it only announces. */
export function useToast() {
  const ctx = useContext(ToastContext);
  return useMemo(() => ctx ?? ((text: string) => AccessibilityInfo.announceForAccessibility?.(text)), [ctx]);
}

/** One toast (also drawn by itself in the gallery). */
export function Toast({ text, tone = 'neutral', action, onDismiss, style, testID }: { text: string; tone?: ToastMessage['tone']; action?: ToastMessage['action']; onDismiss?: () => void; style?: object; testID?: string }) {
  const { t } = useKit();
  const icon: IconName | null = tone === 'success' ? 'check-circle' : tone === 'danger' ? 'warning' : null;
  return (
    <View accessibilityRole="alert" accessibilityLiveRegion="polite" style={[styles.toast, style]} testID={testID ?? 'toast'}>
      {icon ? <Icon name={icon} size={TYPE.section} color={semantic['inverse-text']} /> : null}
      <Text style={[textStyles.body, styles.toastText]}>{text}</Text>
      {action ? (
        <Pressable accessibilityRole="button" accessibilityLabel={action.label} onPress={action.onPress} style={styles.toastAction}>
          <Text style={[textStyles.bodyStrong, styles.toastText]}>{action.label}</Text>
        </Pressable>
      ) : null}
      {onDismiss ? (
        <Pressable accessibilityRole="button" accessibilityLabel={t('kit.dismiss')} onPress={onDismiss} style={styles.toastClose}>
          <Icon name="x" size={TYPE.sub} color={semantic['inverse-muted']} />
        </Pressable>
      ) : null}
    </View>
  );
}

/** Nothing to show: the well grey, the icon, a title, one line and the one action. */
export function EmptyState({ icon = 'package', title, message, action, onAction, testID }: { icon?: IconName; title?: string; message?: string; action?: string; onAction?: () => void; testID?: string }) {
  return (
    <View style={styles.empty} testID={testID ?? 'empty'}>
      <View style={styles.emptyIcon}>
        <Icon name={icon} size={TYPE['price-lg']} color={semantic['well-ink']} />
      </View>
      {title ? <Text style={[textStyles.sub, styles.center]}>{title}</Text> : null}
      {message ? <Text style={[textStyles.body, styles.muted, styles.center]}>{message}</Text> : null}
      {action && onAction ? <Button label={action} onPress={onAction} /> : null}
    </View>
  );
}

/** A grey block standing in for content while it loads (the well grey, the panel corners). */
export function Skeleton({ height = space[4], width = '100%', round }: { height?: number; width?: DimensionValue; round?: boolean }) {
  return <View style={[styles.skeleton, { height, width }, round && styles.round]} />;
}

/** A product card while it loads: the 1:1 well, two lines of title, the price. Announced once as "Loading…". */
export function CardSkeleton({ count = 2, testID }: { count?: number; testID?: string }) {
  const { t } = useKit();
  return (
    <View style={styles.cards} accessibilityRole="progressbar" accessibilityLabel={t('kit.loading')} testID={testID ?? 'loading'}>
      {Array.from({ length: count }, (_, i) => (
        <View key={i} style={styles.cardSkel}>
          <View style={styles.wellSkel} />
          <Skeleton height={TYPE.body} />
          <Skeleton height={TYPE.body} width="70%" />
          <Skeleton height={TYPE.price} width="40%" />
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  center: { textAlign: 'center' },
  muted: { color: semantic['text-muted'] },
  sheetRoot: { flex: 1, justifyContent: 'flex-end' },
  scrim: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: colors.overlay },
  sheet: {
    maxHeight: '88%',
    backgroundColor: semantic.surface,
    borderTopLeftRadius: radius.panel,
    borderTopRightRadius: radius.panel,
    paddingTop: space[2],
  },
  grabber: { alignSelf: 'center', width: space[10], height: space[1], borderRadius: radius.round, backgroundColor: semantic['border-strong'] },
  sheetHead: { flexDirection: 'row', alignItems: 'center', gap: space[2], paddingLeft: space[4], paddingRight: space[2], minHeight: MIN_TARGET + space[2], borderBottomWidth: LINE.hair, borderBottomColor: semantic.border },
  sheetBody: { padding: space[4], gap: space[2] },
  sheetFoot: { paddingHorizontal: space[4], paddingTop: space[3], borderTopWidth: LINE.hair, borderTopColor: semantic.border, gap: space[2] },
  toast: {
    position: 'absolute',
    left: space[4],
    right: space[4],
    flexDirection: 'row',
    alignItems: 'center',
    gap: space[3],
    minHeight: MIN_TARGET + space[1],
    paddingLeft: space[4],
    paddingRight: space[1],
    paddingVertical: space[1],
    borderRadius: radius.panel,
    backgroundColor: semantic['inverse-bg'],
    shadowColor: semantic.text,
    shadowOpacity: 0.18,
    shadowRadius: space[3],
    shadowOffset: { width: 0, height: space[1] },
    elevation: 6,
  },
  toastText: { flex: 1, color: semantic['inverse-text'] },
  toastAction: { minHeight: MIN_TARGET, justifyContent: 'center', paddingHorizontal: space[2] },
  toastClose: { width: MIN_TARGET, height: MIN_TARGET, alignItems: 'center', justifyContent: 'center' },
  empty: { alignItems: 'center', gap: space[3], paddingVertical: space[8], paddingHorizontal: space[5], borderRadius: radius.panel, backgroundColor: semantic.well },
  emptyIcon: { width: SIZE['control-lg'] + space[4], height: SIZE['control-lg'] + space[4], alignItems: 'center', justifyContent: 'center', borderRadius: radius.round, borderWidth: LINE.hair, borderStyle: 'dashed', borderColor: semantic['well-line'], backgroundColor: semantic.surface },
  skeleton: { backgroundColor: semantic.well, borderRadius: radius.image },
  round: { borderRadius: radius.round },
  cards: { flexDirection: 'row', gap: space[3] },
  cardSkel: { flex: 1, gap: space[2] },
  wellSkel: { width: '100%', aspectRatio: 1, backgroundColor: semantic.well, borderRadius: radius.image },
});
