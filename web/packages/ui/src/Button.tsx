import clsx from 'clsx';
import type { ButtonHTMLAttributes } from 'react';
/**
 * Buttons in the locked E-15 direction (A · Plainsight): never pills — 3 px corners, drawn 38 px (32 `sm`, 46 `lg`) with
 * a 44 px hit area. Four kinds that never look alike:
 * primary = the action colour (Add to cart, Book, Place order) · secondary = white with a grey edge · tertiary = text in
 * the action colour ("See details") · destructive = red (Suspend and every destructive action).
 * dark = solid ink (the bulk-action bar). Older names stay for screens not yet rebuilt: accent/highlight = primary,
 * neutral = secondary, ghost = tertiary, danger = destructive; link = a button inside running text.
 */
export type ButtonVariant = 'primary' | 'secondary' | 'tertiary' | 'destructive' | 'dark' | 'link' | 'accent' | 'neutral' | 'danger' | 'ghost' | 'highlight';
export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> { variant?: ButtonVariant; size?: 'sm' | 'md' | 'lg'; block?: boolean; icon?: boolean }
export function Button({ variant = 'primary', size = 'md', block, icon, className, ...rest }: ButtonProps) {
  return <button className={clsx('btn', `btn-${variant}`, size !== 'md' && `btn-${size}`, block && 'btn-block', icon && 'btn-icon', className)} {...rest} />;
}
