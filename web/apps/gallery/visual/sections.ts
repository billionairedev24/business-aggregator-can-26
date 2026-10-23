/** The gallery's section ids (packages/ui/src/gallery/Gallery.tsx GALLERY_SECTIONS), kept here so the Playwright
 * runner needs no JSX; gallery.test.tsx checks the two lists agree. */
export const GALLERY_IDS = ['tokens', 'buttons', 'tabs', 'choice', 'chips', 'checks', 'fields', 'commerce', 'cards', 'table', 'overlays', 'states', 'formats'] as const;
