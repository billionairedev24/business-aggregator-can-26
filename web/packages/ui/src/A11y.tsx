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

/** The keys that move focus or act on it: after one of these the ring shows, whatever moved the focus. */
const KEYBOARD_KEYS = new Set(['Tab', 'Enter', ' ', 'Escape', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End', 'PageUp', 'PageDown', 'F6']);
let modalityUsers = 0;
let stopModality: (() => void) | null = null;

/**
 * S-192 (owner, 2026-11-10: "you should not be having border line on links when i click them or hover"): which device
 * moved focus last, as `data-input="pointer" | "keyboard"` on <html>. Browsers already keep `:focus-visible` off a link
 * or button that is clicked, but not when a script moves focus after a click — a menu that opens and focuses its first
 * item, a dialog that hands focus back to its opener on close — so a mouse or finger could still leave a ring behind.
 * `base.css` hides the ring of everything but text fields while `data-input="pointer"`; a key press brings it back at
 * once (WCAG 2.4.7 / 2.4.11: the keyboard always sees where it is). Mounted by the shells (SiteHeader, AppShell); one
 * listener pair however many mount it.
 */
export function useInputModality(): void {
  useEffect(() => {
    if (typeof document === 'undefined') return;
    if (modalityUsers++ === 0) {
      const root = document.documentElement;
      const pointer = () => { root.dataset.input = 'pointer'; };
      const keyboard = (e: KeyboardEvent) => { if (KEYBOARD_KEYS.has(e.key) && !e.metaKey && !e.ctrlKey) root.dataset.input = 'keyboard'; };
      document.addEventListener('pointerdown', pointer, true);
      document.addEventListener('keydown', keyboard, true);
      stopModality = () => {
        document.removeEventListener('pointerdown', pointer, true);
        document.removeEventListener('keydown', keyboard, true);
        delete root.dataset.input;
      };
    }
    return () => { if (--modalityUsers === 0) { stopModality?.(); stopModality = null; } };
  }, []);
}

/**
 * Publishes the height of a sticky header as `--nl-sticky-top` on <html>, which `scroll-padding-top` reads, so the
 * browser scrolls a focused control clear of the header instead of under it (WCAG 2.4.11 focus not obscured).
 * `onHeight` also receives it (the Studio shell positions its sidebar under the top bar); without it nothing re-renders.
 *
 * `tuckTo` (the consumer header, after AfroTransact's): a selector inside the header. On short screens — the header
 * taller than 16% of the window and that element at least a row below the header's top — scrolling down tucks
 * everything above it away past the top edge (the header's `top` goes to `-var(--nl-header-tuck)`) and scrolling up,
 * or focusing into the header, brings it back. S-191 (Amazon's phone header): while tucked, the rows under that element
 * (the place and the department strip) are clipped away too (`--nl-header-clip`, the header's `clip-path`), so only the
 * search stays at the top of a phone. The returned flag is true while tucked; the published heights are then what is
 * still on screen. Without `tuckTo` it is always false.
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
    let clip = 0;
    let isTucked = false;
    const publish = () => {
      const box = el.getBoundingClientRect();
      const full = Math.round(box.height);
      const below = tuckTo ? el.querySelector<HTMLElement>(tuckTo) : null;
      const offset = below ? Math.round(below.getBoundingClientRect().top - box.top) - 4 : 0;
      // only a header that starts the page tucks (not one shown further down, as in the component gallery)
      const parent = el.parentElement?.getBoundingClientRect();
      const startsPage = !parent || parent.top + window.scrollY < 8;
      tuck = startsPage && offset > 24 && full > window.innerHeight * 0.16 ? offset : 0;
      // what sits under the element (4px kept for its shadow): clipped away while tucked
      const under = below ? below.getBoundingClientRect() : null;
      clip = tuck && under ? Math.max(0, full - Math.round(under.top + under.height - box.top) - 4) : 0;
      if (tuckTo) {
        el.style.setProperty('--nl-header-tuck', `${tuck}px`);
        el.style.setProperty('--nl-header-clip', `${clip}px`);
      }
      const h = full - (isTucked ? tuck + clip : 0);
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
