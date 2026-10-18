import clsx from 'clsx';
/** Status tag (redesign § 1.3): xs bold on a tint. `accent` is the brand tint (its old name), `highlight` the accent tint, `accent-2` danger. */
export type TagTone = 'accent' | 'brand' | 'accent-2' | 'danger' | 'success' | 'warning' | 'neutral' | 'highlight' | 'outline';
export function Tag({ tone = 'accent', children }: { tone?: TagTone; children: React.ReactNode }) {
  return <span className={clsx('tag', `tag-${tone}`)}>{children}</span>;
}
