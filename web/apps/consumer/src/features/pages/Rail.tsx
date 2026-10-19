import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import clsx from 'clsx';
import { CaretLeft, CaretRight } from '@phosphor-icons/react';
import { SiteLink } from '@northline/ui';
import { usePagesT } from './messages';

interface RailState { track: RefObject<HTMLUListElement | null>; onScroll: () => void }
const RailContext = createContext<RailState | null>(null);

export interface RailSectionProps {
  id: string;
  title: string;
  /** "See more" (or similar) beside the title. */
  more?: { href: string; label: string; ariaLabel?: string };
  /** Under the title, muted. */
  lede?: ReactNode;
  className?: string;
  children: ReactNode;
}

/**
 * A titled horizontal rail (light direction, phase B; Airbnb / Uber Eats): one row of cards that scrolls sideways
 * (swipe, wheel, the keyboard through its links) with snap points, never wrapping into a wall. Above it, beside the
 * title, round back/on buttons on pointer devices — never laid over the cards. `RailTrack` holds the cards.
 */
export function RailSection({ id, title, more, lede, className, children }: RailSectionProps) {
  const t = usePagesT();
  const track = useRef<HTMLUListElement>(null);
  const [edges, setEdges] = useState({ start: true, end: false });
  const onScroll = useCallback(() => {
    const el = track.current;
    if (!el) return;
    setEdges({ start: el.scrollLeft <= 4, end: el.scrollLeft + el.clientWidth >= el.scrollWidth - 4 });
  }, []);
  useEffect(() => {
    onScroll();
    window.addEventListener('resize', onScroll);
    return () => window.removeEventListener('resize', onScroll);
  }, [onScroll, children]);
  const move = (dir: 1 | -1) => {
    const el = track.current;
    if (!el) return;
    el.scrollBy({ left: dir * Math.max(240, el.clientWidth * 0.85), behavior: 'smooth' });
  };
  const scrolls = !(edges.start && edges.end);
  return (
    <section className={clsx('nl-rail', className)} aria-labelledby={id}>
      <div className="nl-rail-head">
        <div className="nl-rail-titles">
          <h2 id={id} className="nl-rail-title">{title}</h2>
          {lede ? <p className="nl-rail-lede">{lede}</p> : null}
        </div>
        <div className="nl-rail-tools">
          {more ? <SiteLink href={more.href} className="nl-rail-more" aria-label={more.ariaLabel}>{more.label}</SiteLink> : null}
          {scrolls ? (
            <span className="nl-rail-buttons">
              <button type="button" className="nl-rail-btn" aria-label={t('scrollBack', { title })} disabled={edges.start} onClick={() => move(-1)}><CaretLeft size={18} weight="bold" aria-hidden /></button>
              <button type="button" className="nl-rail-btn" aria-label={t('scrollOn', { title })} disabled={edges.end} onClick={() => move(1)}><CaretRight size={18} weight="bold" aria-hidden /></button>
            </span>
          ) : null}
        </div>
      </div>
      <RailContext.Provider value={{ track, onScroll }}>{children}</RailContext.Provider>
    </section>
  );
}

/** The rail's row: `size` sets the card width (products 200, services 300, tiles 168; narrower on phones). */
export function RailTrack({ size, children, label }: { size: 'product' | 'service' | 'tile'; children: ReactNode; label?: string }) {
  const rail = useContext(RailContext);
  return (
    <ul ref={rail?.track} className={clsx('nl-rail-track', `nl-rail-${size}`)} aria-label={label} onScroll={rail?.onScroll}>
      {children}
    </ul>
  );
}
