import clsx from 'clsx';
/**
 * A tag: 12 px semibold on a tint, 2 px corners (a status — Delivered, To pack — is a StatusTag, with its dot).
 * Tones: the status pairs (success, warning, danger, info, neutral), `brand` (the action tint), `commerce` (deals; its
 * older name is `highlight`), `outline`. `accent` is brand's older name, `accent-2` danger's.
 */
export type TagTone = 'brand' | 'success' | 'warning' | 'danger' | 'info' | 'neutral' | 'commerce' | 'outline' | 'accent' | 'accent-2' | 'highlight';
export function Tag({ tone = 'neutral', children }: { tone?: TagTone; children: React.ReactNode }) {
  return <span className={clsx('tag', `tag-${tone}`)}>{children}</span>;
}
