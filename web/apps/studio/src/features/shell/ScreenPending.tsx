import { EmptyState, PageHeader } from '@northline/ui';

/** Temporary stand-in for a screen whose workstream hasn't landed yet. Every usage must be gone before release. */
export function ScreenPending({ title }: { title: string }) {
  return <><PageHeader title={title} /><EmptyState>{title}</EmptyState></>;
}
