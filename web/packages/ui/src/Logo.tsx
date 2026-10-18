import { createElement } from 'react';
import clsx from 'clsx';
import { brand, theme, type MarkShape } from '@northline/tokens';

export interface LogoProps {
  /** `full`: the mark and the wordmark; `mark`: the mark alone (rail, favicon-sized spots). */
  variant?: 'full' | 'mark';
  /** Height of the mark in px (header 32, back office 28). The wordmark scales with it. */
  size?: number;
  /** On the dark chrome: the palette's `markOnDark` shapes (the same mark unless the palette gives one). */
  onDark?: boolean;
  /** A second word after the wordmark in a muted tone ("Studio", "Console"). */
  product?: string;
  className?: string;
}

/**
 * The brand's mark and wordmark — the one place the product's name is drawn (redesign 2026-10-18, spec § 5). Shapes and
 * name come from brand.json and its palette (@northline/tokens); the mark's colours are semantic tokens, the wordmark
 * inherits `currentColor`. The mark is decorative; the wordmark is text (a link around the logo names itself).
 */
export function Logo({ variant = 'full', size = 32, onDark, product, className }: LogoProps) {
  const mark = onDark ? theme.logo.markOnDark : theme.logo.mark;
  return (
    <span className={clsx('nl-logo', className)} style={{ ['--nl-logo-size' as string]: `${size}px` }}>
      <svg className="nl-logo-mark" width={size} height={size} viewBox={mark.viewBox} focusable="false" aria-hidden>
        {(mark.shapes as MarkShape[]).map(({ el, ...attrs }, i) => createElement(el, {
          key: i,
          ...Object.fromEntries(Object.entries(attrs).map(([k, v]) => [k, (k === 'fill' || k === 'stroke') && v !== 'none' ? `var(--color-${v})` : v])),
        }))}
      </svg>
      {variant === 'full' ? <span className="nl-logo-word">{theme.logo.wordmark}{product ? <span className="nl-logo-product"> {product}</span> : null}</span> : null}
    </span>
  );
}

/** The product's name as text (page titles, aria labels): brand.json `identity.name`. */
export const brandName = brand.name;
