import clsx from 'clsx';
export type TagTone = 'accent' | 'accent-2' | 'neutral' | 'highlight' | 'outline';
export function Tag({ tone = 'accent', children }: { tone?: TagTone; children: React.ReactNode }) {
  return <span className={clsx('tag', `tag-${tone}`)}>{children}</span>;
}
