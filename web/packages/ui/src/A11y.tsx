import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
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
 *
 * `tuckTo` (the consumer header, after AfroTransact's): a selector inside the header. On short screens — the header
 * taller than 16% of the window and that element at least a row below the header's top — scrolling down tucks
 * everything above it away past the top edge (the header's `top` goes to `-var(--nl-header-tuck)`) and scrolling up,
 * or focusing into the header, brings it back. The returned flag is true while tucked; the published heights are then
 * what is still on screen. Without `tuckTo` it is always false.
 */
export function useStickyOffset<E extends HTMLElement>(ref: RefObject<E | null>, onHeight?: (height: number) => void, tuckTo?: string): boolean {
  const listener = useRef(onHeight);
  listener.current = onHeight;
  const [tucked, setTucked] = useState(false);
  useIsoLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const root = document.documentElement;
    let tuck = 0;
    let isTucked = false;
    const publish = () => {
      const box = el.getBoundingClientRect();
      const full = Math.round(box.height);
      const below = tuckTo ? el.querySelector<HTMLElement>(tuckTo) : null;
      const offset = below ? Math.round(below.getBoundingClientRect().top - box.top) - 4 : 0;
      tuck = offset > 24 && full > window.innerHeight * 0.16 ? offset : 0;
      if (tuckTo) el.style.setProperty('--nl-header-tuck', `${tuck}px`);
      const h = full - (isTucked ? tuck : 0);
      listener.current?.(h);
      root.style.setProperty('--nl-sticky-top', `${h + 8}px`);
      // what sticky columns sit under (the checkout summary, the PDP buy box…): the header as drawn, which wraps to
      // more rows on narrow screens; `--size-header` (scale.json) until it is measured
      root.style.setProperty('--nl-header-h', `${h}px`);
    };
    const set = (next: boolean) => {
      if (next === isTucked) return;
      isTucked = next;
      setTucked(next);
      publish();
    };
    publish();
    const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(publish);
    ro?.observe(el);
    let lastY = window.scrollY;
    const onScroll = () => {
      const y = window.scrollY;
      // down by more than a nudge past the header tucks, up by more than a nudge shows; near the top always shown
      set(tuck > 0 && y > el.offsetHeight ? (y > lastY + 4 ? true : y < lastY - 4 ? false : isTucked) : false);
      lastY = y;
    };
    const onFocus = () => set(false); // focus never lands in the tucked-away row (WCAG 2.4.11)
    if (tuckTo) {
      window.addEventListener('scroll', onScroll, { passive: true });
      window.addEventListener('resize', publish);
      el.addEventListener('focusin', onFocus);
    }
    return () => {
      ro?.disconnect();
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', publish);
      el.removeEventListener('focusin', onFocus);
      root.style.removeProperty('--nl-sticky-top');
      root.style.removeProperty('--nl-header-h');
    };
  }, [ref, tuckTo]);
  return tucked;
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
