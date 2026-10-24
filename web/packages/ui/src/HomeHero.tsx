import type { ReactNode } from 'react';
import clsx from 'clsx';
import { ArrowRight } from '@phosphor-icons/react';
import { Sticker } from './Commerce';
import { Skeleton } from './Layout';
import { SiteLink } from './SiteLink';

export interface HomeHeroTrust {
  key: string;
  icon: ReactNode;
  label: ReactNode;
  /** Wide screens only (the third point is dropped on phones, as in the mockup). */
  wide?: boolean;
}

/** The run card: the next pooled run and its cut-off ("Tonight's pooled run · Closes 5:20 p.m. · 2 h 48 min left"). */
export interface HomeHeroRun {
  icon?: ReactNode;
  kicker: ReactNode;
  title: ReactNode;
  /** The time left, on the yellow sticker; absent when there is nothing to count down. */
  left?: ReactNode;
  note?: ReactNode;
}

export interface HomeHeroDepartment {
  key: string;
  href: string;
  icon: ReactNode;
  kicker: ReactNode;
  title: ReactNode;
  meta: ReactNode;
  /** The photo, filling the tile (a <Scene> or an <img>; decorative: the tile's words name it). */
  picture: ReactNode;
}

export interface HomeHeroProps {
  /** The headline's lead ("Everything Kensington needs,") and its light-violet end ("tonight."). */
  title: ReactNode;
  accent?: ReactNode;
  titleId?: string;
  trust?: readonly HomeHeroTrust[];
  /** `undefined` while it loads (its box is held at its size), `null` for none. */
  run: HomeHeroRun | null | undefined;
  runLabel: string;
  /** Read to screen readers while the run loads. */
  loadingLabel?: string;
  departments: readonly HomeHeroDepartment[];
  departmentsLabel: string;
  className?: string;
}

/**
 * The consumer home's hero band (S-188 brand layer, Chinook's "tonight" hero on Plainsight; design/revamp/hybrid/index.html
 * screens 1–2): a deep-violet band with a violet glow, the headline in the display face (Archivo, expanded, 800) with its
 * last words in the light violet, the trust line, the run card with the cut-off on a yellow sticker, then three department
 * photo tiles that straddle the band's lower edge (the photo, a dark scrim, the department in caps, its name in the display
 * face, a line of meta, a white arrow square that turns violet on hover while the tile lifts). Every box has its size
 * from the first render (the run card holds its place while it loads; the headline holds two lines, three on phones), so
 * nothing moves when the data and the photos arrive. No violet text on violet: text there is white, `hero-text` or `hero-accent`.
 */
export function HomeHero({ title, accent, titleId = 'home-hero-band-title', trust = [], run, runLabel, loadingLabel, departments, departmentsLabel, className }: HomeHeroProps) {
  return (
    <section className={clsx('nl-hhero', className)} aria-labelledby={titleId}>
      <div className="nl-hhero-top">
        <div className="nl-hhero-lead">
          <h1 id={titleId} className="nl-hhero-title">{title}{accent ? <>{' '}<em>{accent}</em></> : null}</h1>
          {trust.length ? (
            <ul className="nl-hhero-trust">
              {trust.map(i => <li key={i.key} className={clsx(i.wide && 'nl-hhero-wide')}><span className="nl-hhero-trust-icon" aria-hidden>{i.icon}</span>{i.label}</li>)}
            </ul>
          ) : null}
        </div>
        {run === null ? null : (
          <aside className="nl-hhero-run" aria-label={runLabel} aria-busy={run === undefined || undefined}>
            {run === undefined ? (
              <>
                {loadingLabel ? <span className="nl-sr-only" role="status">{loadingLabel}</span> : null}
                <span className="nl-hhero-run-k" aria-hidden><Skeleton width={150} height={12} /></span>
                <span className="nl-hhero-run-t" aria-hidden><Skeleton width={170} height={24} /></span>
                <span className="nl-hhero-run-s" aria-hidden><Skeleton width={220} height={14} /></span>
              </>
            ) : (
              <>
                <span className="nl-hhero-run-k">{run.icon ? <span className="nl-hhero-run-icon" aria-hidden>{run.icon}</span> : null}{run.kicker}</span>
                <span className="nl-hhero-run-t">{run.title}</span>
                {run.left ? <Sticker className="nl-hhero-run-left">{run.left}</Sticker> : null}
                {run.note ? <span className="nl-hhero-run-s">{run.note}</span> : null}
              </>
            )}
          </aside>
        )}
      </div>
      <ul className="nl-hhero-depts" aria-label={departmentsLabel}>
        {departments.map(d => (
          <li key={d.key}>
            <SiteLink href={d.href} className="nl-hhero-dept">
              <span className="nl-hhero-dept-pic" aria-hidden>{d.picture}</span>
              <span className="nl-hhero-dept-c">
                <span className="nl-hhero-dept-k"><span aria-hidden>{d.icon}</span>{d.kicker}</span>
                <strong className="nl-hhero-dept-t">{d.title}</strong>
                <span className="nl-hhero-dept-m">{d.meta}</span>
              </span>
              <span className="nl-hhero-dept-go" aria-hidden><ArrowRight size={20} weight="bold" /></span>
            </SiteLink>
          </li>
        ))}
      </ul>
    </section>
  );
}
