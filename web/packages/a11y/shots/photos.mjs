// Placeholder "photos" for `make ui-shots` (shots.spec.ts): soft still-life pictures drawn as SVG, generated on request
// from a seed ("bread-sourdough", "provider-wrench", …) so the screenshots show what a card looks like with a real
// picture. Nothing is hotlinked; the pictures are served same-origin under PHOTO_PREFIX (img-src 'self' already allows
// them). The subject follows the first word of the seed; the colours vary with the rest of it.

export const PHOTO_PREFIX = '/__shots/photo/';
/** The URL of the placeholder photo for `seed`. */
export const photoUrl = seed => `${PHOTO_PREFIX}${encodeURIComponent(seed)}.svg`;

const hash = s => { let h = 2166136261; for (const c of s) h = Math.imul(h ^ c.charCodeAt(0), 16777619); return h >>> 0; };
const hsl = (h, s, l) => `hsl(${Math.round(h) % 360} ${s}% ${l}%)`;

const BACKDROPS = [[36, 38, 90], [28, 30, 86], [150, 18, 86], [200, 22, 88], [12, 34, 88], [48, 40, 86], [90, 16, 85], [330, 20, 89]];

/** Each subject: shapes on a 400×400 canvas, in the subject's own colours (h = a hue picked from the seed). */
const SUBJECTS = {
  bread: h => `
    <ellipse cx="200" cy="300" rx="150" ry="22" fill="#000" opacity=".18" filter="url(#blur)"/>
    <path d="M70 250 C70 150 330 150 330 250 C330 290 70 290 70 250Z" fill="url(#crust)"/>
    <path d="M130 200 q25 -14 50 0 M190 190 q25 -14 50 0 M250 200 q20 -10 40 2" stroke="${hsl(36, 45, 85)}" stroke-width="7" fill="none" stroke-linecap="round" opacity=".8"/>`,
  pastry: h => `
    <ellipse cx="200" cy="300" rx="140" ry="20" fill="#000" opacity=".16" filter="url(#blur)"/>
    <circle cx="150" cy="240" r="62" fill="url(#crust)"/><circle cx="250" cy="250" r="56" fill="url(#crust)"/>
    <path d="M118 236 q32 -40 64 0 M222 248 q28 -36 56 0" stroke="${hsl(30, 60, 30)}" stroke-width="6" fill="none" opacity=".55"/>
    <path d="M120 222 q30 -18 60 -4" stroke="#fff" stroke-width="10" fill="none" opacity=".7" stroke-linecap="round"/>`,
  jar: h => `
    <ellipse cx="200" cy="320" rx="110" ry="18" fill="#000" opacity=".18" filter="url(#blur)"/>
    <rect x="130" y="120" width="140" height="200" rx="26" fill="${hsl(h, 70, 52)}"/>
    <rect x="130" y="120" width="140" height="200" rx="26" fill="url(#gloss)"/>
    <rect x="140" y="92" width="120" height="38" rx="8" fill="${hsl(h + 180, 25, 30)}"/>
    <rect x="148" y="190" width="104" height="70" rx="6" fill="#fffaf0" opacity=".92"/>
    <rect x="164" y="210" width="72" height="8" rx="4" fill="${hsl(h + 180, 25, 30)}" opacity=".6"/><rect x="176" y="230" width="48" height="6" rx="3" fill="${hsl(h + 180, 25, 30)}" opacity=".4"/>`,
  bottle: h => `
    <ellipse cx="200" cy="330" rx="80" ry="14" fill="#000" opacity=".18" filter="url(#blur)"/>
    <path d="M178 60 h44 v60 c0 20 36 30 36 70 v130 a18 18 0 0 1 -18 18 h-80 a18 18 0 0 1 -18 -18 v-130 c0 -40 36 -50 36 -70z" fill="${hsl(h, 45, 38)}"/>
    <path d="M178 60 h44 v60 c0 20 36 30 36 70 v130 a18 18 0 0 1 -18 18 h-80 a18 18 0 0 1 -18 -18 v-130 c0 -40 36 -50 36 -70z" fill="url(#gloss)"/>
    <rect x="152" y="210" width="96" height="76" rx="4" fill="#fffaf0" opacity=".9"/>`,
  produce: h => `
    <ellipse cx="200" cy="310" rx="150" ry="20" fill="#000" opacity=".16" filter="url(#blur)"/>
    <circle cx="140" cy="250" r="60" fill="${hsl(h, 70, 48)}"/><circle cx="250" cy="240" r="70" fill="${hsl(h + 20, 70, 44)}"/><circle cx="200" cy="285" r="44" fill="${hsl(h - 25, 72, 50)}"/>
    <circle cx="140" cy="250" r="60" fill="url(#gloss)"/><circle cx="250" cy="240" r="70" fill="url(#gloss)"/>
    <path d="M250 172 q10 -30 34 -36 M140 192 q-6 -24 12 -38" stroke="${hsl(110, 45, 30)}" stroke-width="7" fill="none" stroke-linecap="round"/>`,
  bowl: h => `
    <ellipse cx="200" cy="300" rx="160" ry="24" fill="#000" opacity=".18" filter="url(#blur)"/>
    <ellipse cx="200" cy="200" rx="150" ry="44" fill="${hsl(h, 50, 70)}"/>
    <path d="M50 200 a150 110 0 0 0 300 0z" fill="#fbf7f0"/><path d="M50 200 a150 110 0 0 0 300 0z" fill="url(#gloss)"/>
    <path d="M90 196 q40 -20 80 -4 q40 14 80 -6 q30 -12 60 6" stroke="${hsl(h + 30, 60, 45)}" stroke-width="12" fill="none" stroke-linecap="round"/>
    <circle cx="150" cy="185" r="14" fill="${hsl(110, 50, 38)}"/><circle cx="250" cy="182" r="12" fill="${hsl(110, 50, 38)}"/>`,
  mug: h => `
    <ellipse cx="190" cy="310" rx="120" ry="18" fill="#000" opacity=".18" filter="url(#blur)"/>
    <path d="M270 170 a46 46 0 0 1 0 92" stroke="${hsl(h, 40, 45)}" stroke-width="20" fill="none"/>
    <rect x="110" y="140" width="170" height="170" rx="24" fill="${hsl(h, 40, 52)}"/><rect x="110" y="140" width="170" height="170" rx="24" fill="url(#gloss)"/>
    <path d="M165 120 q-14 -26 4 -46 M205 118 q-14 -26 4 -46" stroke="#fff" stroke-width="6" fill="none" opacity=".7" stroke-linecap="round"/>`,
  box: h => `
    <ellipse cx="200" cy="320" rx="140" ry="18" fill="#000" opacity=".16" filter="url(#blur)"/>
    <path d="M90 150 L200 110 L310 150 L310 290 L200 330 L90 290Z" fill="${hsl(h, 35, 60)}"/>
    <path d="M200 190 L310 150 L310 290 L200 330Z" fill="${hsl(h, 35, 48)}"/><path d="M90 150 L200 190 L310 150 L200 110Z" fill="${hsl(h, 40, 72)}"/>
    <path d="M145 130 L255 170 L255 210" stroke="#fffaf0" stroke-width="14" fill="none" opacity=".75"/>`,
  tool: h => `
    <ellipse cx="200" cy="320" rx="150" ry="18" fill="#000" opacity=".16" filter="url(#blur)"/>
    <rect x="70" y="200" width="260" height="110" rx="12" fill="${hsl(h, 65, 45)}"/><rect x="70" y="200" width="260" height="110" rx="12" fill="url(#gloss)"/>
    <rect x="150" y="170" width="100" height="40" rx="10" fill="none" stroke="${hsl(h, 30, 25)}" stroke-width="14"/>
    <rect x="70" y="240" width="260" height="10" fill="${hsl(h, 50, 32)}"/>
    <path d="M110 140 l70 -50 a22 22 0 1 1 26 30 l-70 50" stroke="#9aa3ad" stroke-width="18" fill="none" stroke-linecap="round"/>`,
  home: h => `
    <rect x="0" y="270" width="400" height="130" fill="${hsl(100, 30, 58)}"/>
    <path d="M90 190 L200 110 L310 190 V300 H90Z" fill="#fbf7f0"/><path d="M70 200 L200 100 L330 200" stroke="${hsl(h, 45, 38)}" stroke-width="22" fill="none" stroke-linejoin="round"/>
    <rect x="180" y="226" width="44" height="74" rx="4" fill="${hsl(h, 40, 40)}"/><rect x="118" y="214" width="40" height="36" rx="3" fill="${hsl(205, 50, 70)}"/><rect x="246" y="214" width="40" height="36" rx="3" fill="${hsl(205, 50, 70)}"/>`,
  portrait: h => `
    <circle cx="200" cy="170" r="70" fill="${hsl(25, 35, 55 + (h % 20))}"/>
    <path d="M70 400 C80 290 320 290 330 400Z" fill="${hsl(h, 40, 42)}"/>
    <path d="M130 160 C130 90 270 90 270 160 C250 120 150 120 130 160Z" fill="${hsl(20, 30, 20)}"/>`,
};

const KEYWORDS = [
  [/bread|loaf|sourdough|levain|rye|baguette|bake/, 'bread'], [/bun|croissant|pastr|cinnamon|cookie|muffin|cake/, 'pastry'],
  [/honey|jam|jar|preserve|sauce|pickle|spread/, 'jar'], [/oil|bottle|kombucha|juice|soda|vinegar|syrup/, 'bottle'],
  [/apple|veg|produce|tomato|fruit|berr|farm|greens|squash/, 'produce'], [/pho|bowl|soup|noodle|food|kitchen|meal|ramen|curry/, 'bowl'],
  [/coffee|tea|mug|cafe/, 'mug'], [/wrench|mechanic|tool|repair|plumb|electric|handy|auto|car/, 'tool'],
  [/clean|home|house|garden|lawn|real-estate|move|paint/, 'home'], [/provider|pro|person|portrait|tutor|hair|beauty|massage|trainer|photo/, 'portrait'],
];

/** An SVG still life for `seed`. */
export function photo(seed) {
  const s = String(seed).toLowerCase();
  const subject = SUBJECTS[s.split(/[-_ ]/)[0]] ? s.split(/[-_ ]/)[0] : (KEYWORDS.find(([re]) => re.test(s))?.[1] ?? 'box');
  const n = hash(s);
  const [bh, bs, bl] = BACKDROPS[n % BACKDROPS.length];
  const hue = (n >> 4) % 360;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 400" width="800" height="800">
<defs>
  <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${hsl(bh, bs, bl + 4)}"/><stop offset=".68" stop-color="${hsl(bh, bs, bl)}"/><stop offset=".68" stop-color="${hsl(bh, bs - 6, bl - 8)}"/><stop offset="1" stop-color="${hsl(bh, bs - 6, bl - 12)}"/></linearGradient>
  <radialGradient id="light" cx=".3" cy=".2" r=".9"><stop offset="0" stop-color="#fff" stop-opacity=".55"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>
  <linearGradient id="gloss" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#fff" stop-opacity=".0"/><stop offset=".2" stop-color="#fff" stop-opacity=".35"/><stop offset=".35" stop-color="#fff" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity=".18"/></linearGradient>
  <radialGradient id="crust" cx=".4" cy=".3" r=".8"><stop offset="0" stop-color="${hsl(34, 62, 62)}"/><stop offset=".7" stop-color="${hsl(28, 58, 42)}"/><stop offset="1" stop-color="${hsl(22, 55, 30)}"/></radialGradient>
  <filter id="blur" x="-20%" y="-50%" width="140%" height="200%"><feGaussianBlur stdDeviation="10"/></filter>
</defs>
<rect width="400" height="400" fill="url(#bg)"/><rect width="400" height="400" fill="url(#light)"/>
${SUBJECTS[subject](hue)}
</svg>`;
}
