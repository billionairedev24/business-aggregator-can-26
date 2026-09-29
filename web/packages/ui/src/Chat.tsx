import type { ReactNode } from 'react';
import clsx from 'clsx';
import { defineMessages } from './i18n';
import './Chat.css';

const useT = defineMessages({
  en: { stars: '{n} out of 5 stars' },
  fr: { stars: '{n} étoiles sur 5' },
});

export interface ChatLogProps { children: ReactNode; label: string; className?: string }
/** A conversation: a polite live region so new messages are announced. Bubbles stack with the design's 12px gap. */
export function ChatLog({ children, label, className }: ChatLogProps) {
  return <div role="log" aria-live="polite" aria-label={label} className={clsx('nl-chat', className)}>{children}</div>;
}

export interface ChatBubbleProps {
  /** `me` = the business side (right, spruce tint); `them` = customer or Northline (left, outlined). */
  side: 'me' | 'them';
  /** Screen-reader name of the sender ("You", "Amara Osei"). */
  sender: string;
  /** Small line under the text: time, "Sending…", "Couldn't send". */
  meta?: ReactNode;
  /** Attachments, a flag note… rendered under the text inside the bubble. */
  footer?: ReactNode;
  pending?: boolean;
  failed?: boolean;
  children?: ReactNode;
}
export function ChatBubble({ side, sender, meta, footer, pending, failed, children }: ChatBubbleProps) {
  return (
    <div className="nl-chat-bubble" data-side={side} data-pending={pending || undefined} data-failed={failed || undefined}>
      <span className="nl-sr-only">{sender}: </span>
      {children ? <div className="nl-chat-text">{children}</div> : null}
      {footer}
      {meta ? <div className="nl-chat-meta">{meta}</div> : null}
    </div>
  );
}

/** Read-only star rating: "★★★★☆" in rosehip, announced as "4 out of 5 stars". */
export function Stars({ rating, className }: { rating: number; className?: string }) {
  const t = useT();
  const n = Math.max(0, Math.min(5, Math.round(rating)));
  return <span className={clsx('nl-stars', className)} role="img" aria-label={t('stars', { n })}>{'★'.repeat(n)}{'☆'.repeat(5 - n)}</span>;
}
