/**
 * A business's mark where it has no logo (design 06 provider lists, provider page, booking summary): its initial on its
 * page's brand colour. The colour is merchant data (the page builder only accepts colours with 4.5:1 against white),
 * so it and the size travel as custom properties (`--nl-mark-bg`, `--nl-mark-size`); the letter is the inverse text.
 */
export function BrandMark({ name, color, size = 64, logoUrl }: { name: string; color: string; size?: number; logoUrl?: string | null }) {
  const initial = name.trim().charAt(0).toUpperCase() || '·';
  return (
    <span className="nl-brandmark" style={{ ['--nl-mark-size' as string]: `${size}px`, ['--nl-mark-bg' as string]: color }} aria-hidden>
      {logoUrl ? <img src={logoUrl} alt="" /> : initial}
    </span>
  );
}
