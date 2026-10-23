import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

/**
 * S-169: the native counterpart of the web's `pnpm lint:tokens` for the kit's components (src/ui/kit): semantic
 * tokens only — no raw colours, no raw font sizes, no raw spacing (padding, margin, gap; 0–2 pt nudges allowed), no
 * raw corner radius, and never `radius.pill` (3 pt in Plainsight; a circle is `radius.round`). The apps' conventions
 * tests carry the `radius.pill` rule for the screens.
 */
const KIT = join(__dirname, '../src/ui/kit');
const files = readdirSync(KIT).filter((f) => /\.tsx?$/.test(f) && f !== 'tokens.ts' && f !== 'messages.ts');

const RULES: Array<[string, RegExp]> = [
  ['raw colour', /['"`]#[0-9a-fA-F]{3,8}['"`]|\brgba?\(|\bhsla?\(/],
  ['raw font size', /fontSize:\s*\d/],
  ['raw spacing', /\b(?:padding|margin|gap|rowGap|columnGap)(?:Horizontal|Vertical|Top|Bottom|Left|Right)?:\s*-?(?:[3-9]|\d{2,})\b/],
  ['raw corner radius', /\bborder(?:Top|Bottom)?(?:Left|Right)?Radius:\s*\d/],
  ['radius.pill', /radius\.pill/],
  ['raw line height', /lineHeight:\s*\d/],
];

describe('the kit uses tokens only', () => {
  it.each(files)('%s', (file) => {
    const src = readFileSync(join(KIT, file), 'utf8');
    const hits = RULES.flatMap(([name, re]) =>
      src
        .split('\n')
        .map((line, i) => [line, i + 1] as const)
        .filter(([line]) => re.test(line) && !/^\s*(\*|\/\/)/.test(line))
        .map(([line, n]) => `${name} at ${file}:${n}: ${line.trim()}`),
    );
    expect(hits).toEqual([]);
  });
});
