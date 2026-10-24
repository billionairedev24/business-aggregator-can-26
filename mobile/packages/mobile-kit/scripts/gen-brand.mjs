#!/usr/bin/env node
/**
 * The native apps' brand assets, generated (redesign phase 4, docs/design/redesign-spec.md § 5):
 *
 *  - src/theme/brandFonts.ts — the palette's font families (web/packages/tokens/generated/theme.json, i.e. brand.json's
 *    `palette`) as `@expo-google-fonts/*` imports, one file per weight, and the family name per role. Only the chosen
 *    palette's files are imported, so the app bundles only them; every palette's package is installed, so switching
 *    the palette is `pnpm --filter @northline/tokens build` (web) + `pnpm --filter @northline/mobile-kit brand`.
 *  - src/ui/art.generated.ts — the illustrated scenes' pictograms (`Art`, fill weight only).
 *  - src/ui/icons.generated.ts — the Phosphor icons the apps draw (regular + fill, the spec's two weights), as SVG
 *    path data from `phosphor-react-native`'s definitions (MIT, © Phosphor Icons). The package itself is not shipped:
 *    its TSX sources don't type-check under the apps' settings and its index bundles every icon.
 *
 * `node scripts/gen-brand.mjs --check` (the kit's test runs it too) fails when either file is stale.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const kit = join(here, '..');
const require = createRequire(join(kit, 'package.json'));
const theme = JSON.parse(readFileSync(join(kit, '../../../web/packages/tokens/generated/theme.json'), 'utf8'));

/** The icons the apps use: name in the apps → Phosphor's component name. Add one here, then run `pnpm brand`. */
export const ICONS = {
  'arrow-left': 'ArrowLeft',
  'arrow-right': 'ArrowRight',
  'arrow-u-up-left': 'ArrowUUpLeft',
  'arrows-clockwise': 'ArrowsClockwise',
  barbell: 'Barbell',
  basket: 'Basket',
  bell: 'Bell',
  'bookmark-simple': 'BookmarkSimple',
  broom: 'Broom',
  'calendar-blank': 'CalendarBlank',
  'calendar-check': 'CalendarCheck',
  'calendar-dots': 'CalendarDots',
  camera: 'Camera',
  car: 'Car',
  'caret-down': 'CaretDown',
  'caret-left': 'CaretLeft',
  'caret-right': 'CaretRight',
  'chat-circle': 'ChatCircle',
  'chat-text': 'ChatText',
  check: 'Check',
  'check-circle': 'CheckCircle',
  circle: 'Circle',
  'clipboard-text': 'ClipboardText',
  clock: 'Clock',
  'clock-counter-clockwise': 'ClockCounterClockwise',
  'cloud-arrow-up': 'CloudArrowUp',
  'credit-card': 'CreditCard',
  crosshair: 'Crosshair',
  database: 'Database',
  drop: 'Drop',
  envelope: 'Envelope',
  export: 'Export',
  'flag-checkered': 'FlagCheckered',
  gear: 'Gear',
  globe: 'Globe',
  'graduation-cap': 'GraduationCap',
  'hand-coins': 'HandCoins',
  heart: 'Heart',
  house: 'House',
  'house-line': 'HouseLine',
  image: 'Image',
  info: 'Info',
  key: 'Key',
  lightning: 'Lightning',
  'list-checks': 'ListChecks',
  'lock-simple': 'LockSimple',
  'magnifying-glass': 'MagnifyingGlass',
  'map-pin': 'MapPin',
  'map-pin-line': 'MapPinLine',
  'map-trifold': 'MapTrifold',
  martini: 'Martini',
  minus: 'Minus',
  'navigation-arrow': 'NavigationArrow',
  package: 'Package',
  path: 'Path',
  'pencil-simple': 'PencilSimple',
  phone: 'Phone',
  plus: 'Plus',
  question: 'Question',
  receipt: 'Receipt',
  scissors: 'Scissors',
  'seal-check': 'SealCheck',
  'shield-check': 'ShieldCheck',
  'shopping-bag': 'ShoppingBag',
  'shopping-cart': 'ShoppingCart',
  'sign-out': 'SignOut',
  signature: 'Signature',
  'sliders-horizontal': 'SlidersHorizontal',
  sparkle: 'Sparkle',
  'squares-four': 'SquaresFour',
  star: 'Star',
  'steering-wheel': 'SteeringWheel',
  storefront: 'Storefront',
  timer: 'Timer',
  translate: 'Translate',
  trash: 'Trash',
  truck: 'Truck',
  user: 'User',
  'user-circle': 'UserCircle',
  warning: 'Warning',
  'wifi-slash': 'WifiSlash',
  wrench: 'Wrench',
  x: 'X',
};

/**
 * The illustrated scenes' pictograms (light direction: `Art`, ported from the consumer web's features/art): Phosphor's
 * *fill* weight only, by Phosphor's component name. Add one here (and a rule in src/ui/artSubjects.ts), then run `pnpm brand`.
 */
export const ART_ICONS = [
  'Armchair',
  'Baby',
  'BabyCarriage',
  'Balloon',
  'Bank',
  'Barbell',
  'Basket',
  'Bathtub',
  'BeerBottle',
  'Bed',
  'BookOpen',
  'Books',
  'BowlFood',
  'BowlSteam',
  'Bread',
  'Briefcase',
  'Browser',
  'Bug',
  'Buildings',
  'Cake',
  'Calculator',
  'Camera',
  'Car',
  'CarBattery',
  'CarProfile',
  'Carrot',
  'Cat',
  'Certificate',
  'Champagne',
  'ChargingStation',
  'ChartLineUp',
  'Cheese',
  'CloudRain',
  'CloudSnow',
  'Coffee',
  'CoffeeBean',
  'Compass',
  'Confetti',
  'Cookie',
  'CookingPot',
  'Couch',
  'Cow',
  'Desktop',
  'DeviceMobile',
  'Dog',
  'Dress',
  'Drop',
  'Egg',
  'Exam',
  'Fan',
  'FileText',
  'FirstAidKit',
  'Fish',
  'Flame',
  'Flower',
  'FlowerLotus',
  'Football',
  'ForkKnife',
  'Garage',
  'GasCan',
  'Gift',
  'Globe',
  'GraduationCap',
  'HairDryer',
  'Hamburger',
  'Hammer',
  'Handbag',
  'HandHeart',
  'HandPalm',
  'Handshake',
  'HandSoap',
  'Headphones',
  'Heart',
  'Heartbeat',
  'House',
  'HouseLine',
  'IceCream',
  'Image',
  'JarLabel',
  'Key',
  'Knife',
  'Lamp',
  'Laptop',
  'Leaf',
  'Lego',
  'Lightbulb',
  'Lightning',
  'Lock',
  'MagnifyingGlass',
  'Martini',
  'Medal',
  'Microphone',
  'MusicNotes',
  'Orange',
  'Package',
  'PaintBrush',
  'PaintRoller',
  'PawPrint',
  'Pill',
  'Pizza',
  'Plant',
  'Receipt',
  'Scales',
  'Scissors',
  'ShieldCheck',
  'ShoppingBag',
  'Snowflake',
  'Sneaker',
  'Sparkle',
  'SteeringWheel',
  'Storefront',
  'Sun',
  'SwimmingPool',
  'Television',
  'Tent',
  'Thermometer',
  'Toolbox',
  'Translate',
  'Trash',
  'Tree',
  'Truck',
  'TShirt',
  'VideoCamera',
  'Watch',
  'WifiHigh',
  'Wine',
  'Wrench',
  'MapTrifold',
  'CalendarCheck',
];

const HEADER = (what) => `// GENERATED by mobile/packages/mobile-kit/scripts/gen-brand.mjs (${what}) — do not edit; \`pnpm --filter @northline/mobile-kit brand\`.\n`;

// ── fonts ────────────────────────────────────────────────────────────────────────────────────────────────────────────
const WEIGHT_NAMES = { 100: 'Thin', 200: 'ExtraLight', 300: 'Light', 400: 'Regular', 500: 'Medium', 600: 'SemiBold', 700: 'Bold', 800: 'ExtraBold', 900: 'Black' };
const firstFamily = (stack) => /^\s*"([^"]+)"/.exec(stack ?? '')?.[1] ?? null;
/**
 * S-188: a family the web draws at another width (`stretch`, e.g. Archivo at 125 %) is a static instance the kit ships
 * itself (React Native can't set a variable font's width; @expo-google-fonts has the normal width only), laid out like
 * an @expo-google-fonts package under assets/fonts/<family>-<width>/ (scripts/gen-display-font.py).
 */
const WIDTH_NAMES = { '112.5%': 'SemiExpanded', '125%': 'Expanded', '150%': 'ExtraExpanded' };
const widthOf = (family) => {
  const stretch = theme.font.families.find((f) => f.family === family)?.stretch;
  if (!stretch || stretch === '100%') return '';
  if (!WIDTH_NAMES[stretch]) throw new Error(`${family}: no static instance for width ${stretch}`);
  return WIDTH_NAMES[stretch];
};
const faceOf = (family) => `${family}${widthOf(family) ? ` ${widthOf(family)}` : ''}`;
const pkgOf = (family) => (widthOf(family) ? `../../assets/fonts/${faceOf(family).toLowerCase().replace(/\s+/g, '-')}` : `@expo-google-fonts/${family.toLowerCase().replace(/\s+/g, '-')}`);
const exportOf = (family, w) => `${faceOf(family).replace(/\s+/g, '')}_${w}${WEIGHT_NAMES[w]}`;

function fontsFile() {
  const sans = firstFamily(theme.font.sans);
  // the heading role is theme.json `font.heading` (the sans in Plainsight)
  const display = firstFamily(theme.font.heading ?? theme.font.display);
  // S-188: the display role is `font.display` (Archivo expanded 800): the home hero, its department tiles and the maker
  // of the week's headline only (the screens' rules allow it nowhere else)
  const hero = firstFamily(theme.font.display);
  if (!sans) throw new Error('theme.json has no sans family');
  const weightsOf = (family) => theme.font.families.find((f) => f.family === family)?.weights ?? [];
  /** The closest weight the palette loads, preferring the lighter side (DM Sans has no 800: black falls back to 700). */
  const pick = (family, want) => {
    const ws = weightsOf(family);
    if (!ws.length) throw new Error(`theme.json loads no weights of ${family}`);
    return ws.includes(want) ? want : [...ws].sort((a, b) => Math.abs(a - want) - Math.abs(b - want) || a - b)[0];
  };
  const headingFamily = display && display !== sans ? display : sans;
  const roles = {
    regular: [sans, pick(sans, 400)],
    medium: [sans, pick(sans, 500)],
    semibold: [sans, pick(sans, 600)],
    bold: [sans, pick(sans, 700)],
    black: [sans, pick(sans, 800)],
    heading: [headingFamily, pick(headingFamily, 700)],
    // the display face at its heaviest loaded weight (Archivo 800); no display face = the heading face
    display: hero && hero !== sans ? [hero, pick(hero, 800)] : [headingFamily, pick(headingFamily, 700)],
  };
  const imports = new Map();
  for (const [family, w] of Object.values(roles)) {
    const name = exportOf(family, w);
    const path = `${pkgOf(family)}/${w}${WEIGHT_NAMES[w]}`;
    // fails here when the palette's family isn't installed (add its @expo-google-fonts package) or its static
    // instance isn't built (scripts/gen-display-font.py)
    require.resolve(path.startsWith('.') ? join(kit, 'src/theme', path) : path);
    imports.set(name, path);
  }
  const lines = [
    HEADER(`fonts of the "${theme.brand.palette}" palette, from web/packages/tokens/generated/theme.json`),
    ...[...imports].sort(([a], [b]) => a.localeCompare(b)).map(([name, path]) => `import { ${name} } from '${path}';`),
    '',
    '/** The font files to load with `useFonts` (expo-font) before the first screen draws. */',
    `export const BRAND_FONT_ASSETS = { ${[...imports.keys()].sort().join(', ')} } as const;`,
    '',
    '/** The family name to use per role: React Native picks a weight by family name (fontWeight is ignored on Android). */',
    'export const BRAND_FONTS = {',
    ...Object.entries(roles).map(([role, [family, w]]) => `  ${role}: '${exportOf(family, w)}',`),
    '} as const;',
    '',
    `export const BRAND_FONT_SOURCE = { palette: '${theme.brand.palette}', sans: '${sans}', heading: '${headingFamily}', display: '${faceOf(roles.display[0])}' } as const;`,
    '',
  ];
  return lines.join('\n');
}

// ── icons ────────────────────────────────────────────────────────────────────────────────────────────────────────────
function pathsOf(component, weight) {
  // the package exports no package.json: its root is two levels above the CommonJS entry (lib/commonjs/index.js)
  const root = join(dirname(require.resolve('phosphor-react-native')), '../..');
  const file = readFileSync(join(root, 'src/defs', `${component}.tsx`), 'utf8');
  const block = new RegExp(`\\[\\s*'${weight}',\\s*<>([\\s\\S]*?)</>`).exec(file)?.[1];
  if (!block) throw new Error(`${component}: no ${weight} weight`);
  if (/<(?!Path\b)[A-Z]/.test(block)) throw new Error(`${component} ${weight}: only <Path> elements are supported`);
  return [...block.matchAll(/\bd="([^"]+)"/g)].map((m) => m[1]);
}

function iconsFile() {
  const rows = Object.entries(ICONS).map(([name, component]) => {
    const regular = JSON.stringify(pathsOf(component, 'regular'));
    const fill = JSON.stringify(pathsOf(component, 'fill'));
    return `  '${name}': { regular: ${regular}, fill: ${fill} },`;
  });
  return [
    HEADER('Phosphor Icons 2 path data, MIT licence © Phosphor Icons, https://phosphoricons.com'),
    '/** SVG path data on Phosphor\'s 256 × 256 grid, regular and fill (the spec\'s two weights). */',
    'export const ICON_PATHS = {',
    ...rows,
    '} as const satisfies Record<string, { regular: readonly string[]; fill: readonly string[] }>;',
    '',
    'export type IconName = keyof typeof ICON_PATHS;',
    '',
  ].join('\n');
}

function artFile() {
  const rows = ART_ICONS.map((component) => `  ${component}: ${JSON.stringify(pathsOf(component, 'fill'))},`);
  return [
    HEADER('Phosphor Icons 2 path data, fill weight, MIT licence © Phosphor Icons, https://phosphoricons.com'),
    "/** The illustrated scenes' pictograms (`Art`): SVG path data on Phosphor's 256 × 256 grid, fill weight. */",
    'export const ART_PATHS = {',
    ...rows,
    '} as const satisfies Record<string, readonly string[]>;',
    '',
    'export type ArtIcon = keyof typeof ART_PATHS;',
    '',
  ].join('\n');
}

const outputs = [
  [join(kit, 'src/theme/brandFonts.ts'), fontsFile()],
  [join(kit, 'src/ui/icons.generated.ts'), iconsFile()],
  [join(kit, 'src/ui/art.generated.ts'), artFile()],
];
const check = process.argv.includes('--check');
let stale = 0;
for (const [file, content] of outputs) {
  let current = null;
  try {
    current = readFileSync(file, 'utf8');
  } catch {
    // missing = stale
  }
  if (current === content) continue;
  if (check) {
    console.error(`stale: ${file} — run \`pnpm --filter @northline/mobile-kit brand\``);
    stale++;
  } else {
    writeFileSync(file, content);
    console.log(`wrote ${file}`);
  }
}
process.exit(stale ? 1 : 0);
