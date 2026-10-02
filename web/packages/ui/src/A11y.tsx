import { useEffect, useLayoutEffect, useRef, type RefObject } from 'react';
import { defineMessages } from './i18n';

const useT = defineMessages({ en: { skip: 'Skip to content' }, fr: { skip: 'Passer au contenu' } });

/**
 * "Skip to content" (WCAG 2.4.1): the first focusable element of a shell, off-screen until focused. `target` is the id
 * of the page's <main> (which takes tabIndex -1 so focus lands on it).
 */
export function SkipLink({ target = 'main' }: { target?: string }) {
  const t = useT();
  return <a href={`#${target}`} className="nl-skip-link">{t('skip')}</a>;
}

const useIsoLayoutEffect = typeof window === 'undefined' ? useEffect : useLayoutEffect;

/**
 * Publishes the height of a sticky header as `--nl-sticky-top` on <html>, which `scroll-padding-top` reads, so the
 * browser scrolls a focused control clear of the header instead of under it (WCAG 2.4.11 focus not obscured).
 * `onHeight` also receives it (the Studio shell positions its sidebar under the top bar); without it nothing re-renders.
 */
export function useStickyOffset<E extends HTMLElement>(ref: RefObject<E | null>, onHeight?: (height: number) => void): void {
  const listener = useRef(onHeight);
  listener.current = onHeight;
  useIsoLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const publish = () => {
      const h = Math.round(el.getBoundingClientRect().height);
      listener.current?.(h);
      document.documentElement.style.setProperty('--nl-sticky-top', `${h + 8}px`);
    };
    publish();
    if (typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(publish);
    ro.observe(el);
    return () => { ro.disconnect(); document.documentElement.style.removeProperty('--nl-sticky-top'); };
  }, [ref]);
}

/** Moves focus into `ref` when `active` turns on and back to what had it when it turns off; Tab stays inside. */
export function useFocusTrap<E extends HTMLElement>(active: boolean): { ref: RefObject<E | null>; onKeyDown: (e: React.KeyboardEvent) => void } {
  const ref = useRef<E>(null);
  useEffect(() => {
    if (!active) return;
    const prev = document.activeElement as HTMLElement | null;
    (ref.current?.querySelector<HTMLElement>('[data-autofocus]') ?? ref.current?.querySelector<HTMLElement>(FOCUSABLE) ?? ref.current)?.focus();
    return () => { if (prev?.isConnected) prev.focus(); };
  }, [active]);
  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key !== 'Tab' || !ref.current) return;
    const els = [...ref.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
    if (!els.length) { e.preventDefault(); return; }
    const [a, z] = [els[0]!, els[els.length - 1]!];
    if (e.shiftKey && document.activeElement === a) { e.preventDefault(); z.focus(); }
    else if (!e.shiftKey && document.activeElement === z) { e.preventDefault(); a.focus(); }
  };
  return { ref, onKeyDown };
}

export const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]):not([type="hidden"]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';
