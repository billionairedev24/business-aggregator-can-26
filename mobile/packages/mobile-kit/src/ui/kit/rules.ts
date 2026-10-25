/**
 * S-178/S-179: the native `lint:tokens` for the apps' screens (both apps' conventions tests run it over app/ and src/;
 * the kit's own rules test is stricter). Screens use the kit's roles, scale and components only — what the E-15
 * audit found and the revamp removed can't come back.
 */

/**
 * S-188: the only screens that may draw the display face (Archivo expanded 800, `textStyles.displayHero / displayTile /
 * displayMaker`): the consumer home's hero band with its department tiles, and the maker of the week's headline.
 */
export const DISPLAY_FACE_FILES: readonly string[] = ['src/shop/HomeBand.tsx', 'src/shop/Home.tsx'];

/** S-188: colours that are a fill only (yellow `commerce`, the cart count, the mark's dot, …): never text or an icon. */
const FILL_ONLY = ['commerce', 'cart-badge', 'mark-highlight', 'subheader-deal', 'hero-glow'];

/** [what it is, the pattern, the files (relative to the app) where it is allowed]. */
export const SCREEN_RULES: ReadonlyArray<readonly [string, RegExp, (readonly string[])?]> = [
  ['raw colour', /['"`]#[0-9a-fA-F]{3,8}['"`]|\brgba?\(|\bhsla?\(/],
  ['raw font size (use textStyles / TYPE)', /fontSize:\s*\d/],
  ['raw line height (use textStyles)', /lineHeight:\s*\d/],
  ['the display face by name (use textStyles.displayHero / displayTile / displayMaker)', /fonts\.display/],
  ['the display face outside the home hero, its tiles and the maker headline', /textStyles\.display(?:Hero|Tile|Maker)\b/, DISPLAY_FACE_FILES],
  ['a fill-only colour as text or an icon (yellow is ink on yellow: DealBadge, CutoffPill)', new RegExp(`\\b(?:color|fill|stroke|tintColor)\\b[^,;]*semantic(?:\\.commerce\\b|\\[['"](?:${FILL_ONLY.join('|')})['"]\\])`)],
  ['raw spacing (use space[…]; 0–2 pt nudges allowed)', /\b(?:padding|margin|gap|rowGap|columnGap)(?:Horizontal|Vertical|Top|Bottom|Left|Right)?:\s*-?(?:[3-9]|\d{2,})\b/],
  ['raw corner radius (use radius.control / panel / image / status / round)', /\bborder(?:Top|Bottom)?(?:Left|Right)?Radius:\s*[1-9]/],
  ['an older radius name (use radius.control / panel)', /radius\.(?:pill|lg|md|sm|xs)\b/],
  ['the retired linen (use semantic.well)', /surface-sunken/],
  ['a drawn scene (use the photo well: Photo / PhotoWell)', /<Scene\b|<Art\b/],
  ['a price formatted by hand (use formatPrice / Price)', /style:\s*['"]currency['"]/],
];

/** Rules on a whole source (an import over several lines). */
export const SOURCE_RULES: ReadonlyArray<readonly [string, RegExp]> = [
  ['the native switch (use the kit’s Switch)', /import\s*\{[^}]*\bSwitch\b[^}]*\}\s*from\s*['"]react-native['"]/],
];

/** Every rule a source breaks, as "rule at file:line: code" (comment lines skipped); `file` is relative to the app. */
export function screenRuleHits(file: string, source: string): string[] {
  const whole = SOURCE_RULES.filter(([, re]) => re.test(source)).map(([name]) => `${name} in ${file}`);
  return whole.concat(SCREEN_RULES.filter(([, , allowed]) => !allowed?.includes(file)).flatMap(([name, re]) =>
    source
      .split('\n')
      .map((line, i) => [line, i + 1] as const)
      .filter(([line]) => re.test(line) && !/^\s*(\*|\/\/|\/\*)/.test(line))
      .map(([line, n]) => `${name} at ${file}:${n}: ${line.trim()}`),
  ));
}
