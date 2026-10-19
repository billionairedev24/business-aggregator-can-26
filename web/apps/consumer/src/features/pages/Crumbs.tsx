import { SiteLink } from '@northline/ui';

export interface Crumb { label: string; href?: string; lang?: string }

/** Breadcrumbs: one muted line of links, the current page last (aria-current), "›" between them. */
export function Crumbs({ label, items }: { label: string; items: readonly Crumb[] }) {
  return (
    <nav aria-label={label} className="nl-crumbs-nav">
      <ol className="nl-crumbs">
        {items.map((c, i) => (
          <li key={i}>
            {i > 0 ? <>{' '}<span className="nl-crumbs-sep" aria-hidden>›</span>{' '}</> : null}
            {c.href && i < items.length - 1
              ? <SiteLink href={c.href} lang={c.lang}>{c.label}</SiteLink>
              : <span aria-current="page" lang={c.lang}>{c.label}</span>}
          </li>
        ))}
      </ol>
    </nav>
  );
}
