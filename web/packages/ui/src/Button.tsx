import clsx from 'clsx';
import type { ButtonHTMLAttributes } from 'react';
/**
 * AfroTransact's buttons (redesign § 1.3): pills, drawn 32 / 40 / 48 px (`size`), never under the 44px target.
 * primary = the screen's main action · accent = Add to cart, Search (the brand's second colour) · secondary (= neutral)
 * = white with a hairline · dark = the chrome's colour · danger · ghost = text · link. `highlight` is accent's old name.
 */
export type ButtonVariant = 'primary' | 'accent' | 'secondary' | 'neutral' | 'dark' | 'danger' | 'ghost' | 'link' | 'highlight';
export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> { variant?: ButtonVariant; size?: 'sm' | 'md' | 'lg'; block?: boolean; icon?: boolean }
export function Button({ variant = 'primary', size = 'md', block, icon, className, ...rest }: ButtonProps) {
  return <button className={clsx('btn', `btn-${variant}`, size !== 'md' && `btn-${size}`, block && 'btn-block', icon && 'btn-icon', className)} {...rest} />;
}
