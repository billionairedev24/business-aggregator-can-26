/** The gallery's section ids (packages/ui/src/gallery/Gallery.tsx GALLERY_SECTIONS), kept here so the Playwright
 * runner needs no JSX; gallery.test.tsx checks the two lists agree. */
export const GALLERY_IDS = ['tokens', 'site', 'buttons', 'tabs', 'choice', 'chips', 'filters', 'checks', 'fields', 'commerce', 'cards', 'table', 'overlays', 'states', 'formats', 'backoffice', 'worktable', 'shell'] as const;
/** Sections photographed on their own page (`?section=<id>`): the back-office shell brings its own landmarks. */
export const SOLO_IDS: readonly (typeof GALLERY_IDS)[number][] = ['shell'];
