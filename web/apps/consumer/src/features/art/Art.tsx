import { useId } from 'react';
import clsx from 'clsx';
import { subjectFor, toneFor, type Subject, type Tone } from './subjects';

export interface ArtProps {
  /** What to draw: a category slug, department slug, cuisine code or name (see `subjectFor`). */
  subject: string;
  /** The colour family; by default a stable one for the subject. */
  tone?: Tone;
  /** Override the drawn subject (e.g. the 404's compass). */
  draw?: Subject;
  className?: string;
}

/**
 * An illustrated still life (light direction, phase B): the picture a category, department, cuisine or empty page
 * shows until a real photograph exists — a soft backdrop and ground in one of the palette's ramps, a light disc, the
 * subject's pictogram with a smaller companion and a few dots. One SVG with `slice`, so it fills any box (a tile, a
 * banner, a card) and stays centred; colours come from the tokens only. Decorative (aria-hidden): the text beside it
 * names the thing.
 */
export function Art({ subject, tone, draw, className }: ArtProps) {
  const id = useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const { main: Main, side: Side } = draw ?? subjectFor(subject);
  const t = tone ?? toneFor(subject);
  return (
    <span className={clsx('nl-art', className)} data-tone={t} aria-hidden>
      <svg viewBox="0 0 400 300" preserveAspectRatio="xMidYMid slice" focusable="false">
        <defs>
          <linearGradient id={`${id}-sky`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" className="nl-art-sky-a" /><stop offset="1" className="nl-art-sky-b" />
          </linearGradient>
          <radialGradient id={`${id}-glow`} cx=".5" cy=".42" r=".5">
            <stop offset="0" className="nl-art-glow-a" /><stop offset="1" className="nl-art-glow-b" />
          </radialGradient>
        </defs>
        <rect width="400" height="300" fill={`url(#${id}-sky)`} />
        <circle cx="200" cy="128" r="112" fill={`url(#${id}-glow)`} />
        <path className="nl-art-ground" d="M-20 222 Q200 196 420 222 V320 H-20Z" />
        <ellipse className="nl-art-shadow" cx="196" cy="222" rx="86" ry="11" />
        <circle className="nl-art-dot nl-art-dot-a" cx="92" cy="78" r="7" />
        <circle className="nl-art-dot nl-art-dot-b" cx="318" cy="62" r="5" />
        <circle className="nl-art-dot nl-art-dot-c" cx="334" cy="168" r="9" />
        <Main className="nl-art-main" weight="fill" x={128} y={64} width={144} height={144} />
        {Side ? (
          <g>
            <circle className="nl-art-badge" cx="262" cy="180" r="34" />
            <Side className="nl-art-side" weight="fill" x={239} y={157} width={46} height={46} />
          </g>
        ) : null}
      </svg>
    </span>
  );
}

export { subjectFor, toneFor, TONES, type Tone } from './subjects';
