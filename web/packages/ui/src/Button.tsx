import clsx from 'clsx';
import type { ButtonHTMLAttributes } from 'react';
export type ButtonVariant = 'primary' | 'highlight' | 'secondary' | 'ghost';
export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> { variant?: ButtonVariant; block?: boolean; icon?: boolean }
export function Button({ variant = 'primary', block, icon, className, ...rest }: ButtonProps) {
  return <button className={clsx('btn', `btn-${variant}`, block && 'btn-block', icon && 'btn-icon', className)} {...rest} />;
}
