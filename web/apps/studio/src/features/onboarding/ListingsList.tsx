export interface ListingRow { id: string; name: string; meta: string; state: string; cls: string }

/** "Listings · N" rows: name, meta line and vetting state (design 02 `listings`). */
export function ListingsList({ rows, empty }: { rows: readonly ListingRow[]; empty: string }) {
  if (!rows.length) return <p className="nl-muted nl-small">{empty}</p>;
  return (
    <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
      {rows.map(l => (
        <li key={l.id} className="nl-ob-listing">
          <span><strong>{l.name}</strong><br /><span className="nl-ob-listing-meta">{l.meta}</span></span>
          <span className={`tag ${l.cls}`} style={{ alignSelf: 'center' }}>{l.state}</span>
        </li>
      ))}
    </ul>
  );
}
