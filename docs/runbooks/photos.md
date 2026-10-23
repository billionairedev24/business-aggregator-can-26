# Photos

The web and the native apps show **real photographs** wherever they used to draw a picture (owner, 2026-10-19: "use
real pictures, because in production we will have to use real pictures"). There are two kinds:

- **Merchants' own photos** — what businesses upload in the Studio: product images, and (since this change) photos of
  the work on a service. The APIs return them (`imageUrl`, `images`), public once the listing is approved. A card shows
  the merchant's photo first.
- **Stock photos** — the **photo library** in `web/packages/media`: one photo per *slot* (the home page's hero, each
  catalogue category and department, the landing banners, empty pages, the demo businesses and listings). A card
  without a merchant photo shows its category's stock photo; a slot without a photo shows its fallback slot's
  (a category → its group → the landing's banner), and only when none has one, the drawn picture of before.

Since S-181 every slot has a photo except three categories that show the **designed fallback** by choice
(`slots.config.json` → `designed`): the grey photo well with a dashed inner line and the category's icon
(`features/art/PhotoWell.tsx`, the locked direction's "Photo and fallback"). A listing without a photo of its own shows
that well too — never its category's stock photo (which repeated one picture across a page of cards) and never a
drawn picture of its name. `pnpm lint:tokens` fails on a `fallback={<Scene …>}` or `mark={<Scene …>}`.

## Contents

1. [The library](#1-the-library)
2. [Licences: what may go in](#2-licences-what-may-go-in)
3. [Getting candidates on a Mac: the owner's three commands](#3-getting-candidates-on-a-mac-the-owners-three-commands)
4. [Picking a candidate](#4-picking-a-candidate)
5. [Adding a photo by hand (Pexels, Unsplash)](#5-adding-a-photo-by-hand-pexels-unsplash)
6. [Building the variants](#6-building-the-variants)
7. [Where the pages use them](#7-where-the-pages-use-them)
8. [Serving: the site, a CDN, the apps](#8-serving-the-site-a-cdn-the-apps)
9. [Unblocking downloads in this environment](#9-unblocking-downloads-in-this-environment)
10. [Demo data](#10-demo-data)

## 1. The library

| file | what |
|---|---|
| `web/packages/media/source/` | the source photos, one per slot, by slot path: `source/category/service.home-trades.plumber.jpg`. JPEG, about 2000 px on the long side, at most 2.5 MB (no Git LFS here) |
| `web/packages/media/photos.json` | one entry per slot that has a photo: file, alt text (English and French), focal point, site, photographer, source page, licence, date downloaded; `make photos` adds width, height, hash and the placeholder colour |
| `web/packages/media/slots.config.json` | the slots that don't come from data (heroes, banners, empty pages, sell cards), the cuisine codes, better search words for some categories |
| `web/packages/media/generated/slots.json` | **every** slot (270 today) with what to search for, where it shows and its fallback — generated from the catalogue seed (`db/seed/categories.json`: every group and leaf), the cuisines, the fixed slots and the demo plan (`scripts/demo/data/marketplace.json`, each business's and listing's `photo`) |
| `web/packages/media/candidates/` | Openverse candidates waiting for a pick (git-ignored; they travel on a `photos/candidates-*` branch) |

`make photos-slots` regenerates the slot list and prints every slot still without a photo, by group, with its search
words and what shows meanwhile; the last line counts them. `make photos-check` (and the package's tests) fail when the
slot list is stale, when an entry lacks its licence, source page, photographer or alt text, when a source file changed
since `make photos` ran, or when a file is too big.

An entry:

```json
"category/service.home-trades.plumber": {
  "file": "source/category/service.home-trades.plumber.jpg",
  "alt": { "en": "A plumber fitting a pipe under a kitchen sink", "fr": "Un plombier qui pose un tuyau sous un évier de cuisine" },
  "focal": { "x": 0.5, "y": 0.45 },
  "site": "pexels",
  "photographer": "Jane Doe",
  "photographerUrl": "https://www.pexels.com/@janedoe",
  "sourceUrl": "https://www.pexels.com/photo/plumber-fixing-a-sink-1234567/",
  "licence": "Pexels License",
  "downloaded": "2026-10-20"
}
```

- **alt** describes what is in the picture, in both languages (the French is ours to write, like every French string;
  it goes through the translator review with the rest). The pages mostly use the photo next to a heading that names it
  and mark it decorative; the alt text is read where the photo stands alone (and on the credits page).
- **focal** is the point every crop keeps in view (0–1 from the left and from the top): the face, the hands, the dish.
- Only `make photos` writes `width`, `height`, `hash` and `placeholder` — never by hand.

## 2. Licences: what may go in

Only photos a person downloaded from a site whose licence allows commercial use **as a photo on our pages**, and only
under that licence. **Never** generate, draw, edit into something else or invent a photo, and never take one from a
search engine, a competitor or social media.

| site (`site`) | `licence` | rules |
|---|---|---|
| Pexels (`pexels`) | `Pexels License` | free for commercial use, no attribution required (we credit anyway). Not allowed: selling unaltered copies, implying the people shown endorse us, showing identifiable people in a bad light, **redistributing the photos as a stock collection** |
| Unsplash (`unsplash`) | `Unsplash License` | free for commercial use, no attribution required (we credit anyway). Not allowed: selling unaltered copies, and **compiling Unsplash photos to replicate a similar or competing service** — so the library must stay what it is (pictures on our own pages), never a browsable gallery or download of photos; don't add more Unsplash photos than the slots need |
| Openverse (`openverse`) | `CC0 1.0` or `PDM 1.0` — and, only with `COMMERCIAL=1`, `CC BY` / `CC BY-SA` (with `licenceUrl` and `source`) | CC0 and the Public Domain Mark need nothing. CC BY needs the photographer and licence next to the work or on a credits page reachable from it — the footer's `/credits` page does that. CC BY-SA also asks that changed versions (our crops) stay under BY-SA: acceptable for a photo on a page, but prefer CC0. Never NC (non-commercial) or ND (no derivatives): our crops are derivatives |
| Open Images (`openimages`) | `CC BY 2.0` only (with `licenceUrl` and `source: flickr`) | Google's Open Images dataset lists each photo's Flickr page, photographer and licence; we take only those it lists under CC BY 2.0 and credit them on `/credits` like Openverse's. The files come from the dataset's public bucket (`open-images-dataset.s3.amazonaws.com`, about 1024 px on the long side, so heroes are softer than a 2000 px source) — see § 9 |

Also:

- No recognisable trademarks, shop signs, licence plates or brand logos in the picture; no real businesses.
- Recognisable people only in a neutral, everyday light (no health, money or legal trouble implied), and never for a
  category that could embarrass them.
- Region-neutral: no recognisable city skyline, landmark or province-specific sign (the product serves every province).
- Check the photo's page before picking: Openverse reports what its source says, and sources make mistakes.

The credits page (`/credits` on the consumer site, linked from every page's footer) is generated from `photos.json`:
each photo with its photographer, site and licence, linked.

## 3. Getting candidates on a Mac: the owner's three commands

The photo hosts are blocked in the environment where the agents work, so the **download runs on the owner's Mac**.
Prerequisite: the repository cloned and Node 22 (the repository's toolchain: `make doctor` checks it; `brew install
node@22` if missing). Nothing else is installed: the fetch script has no dependencies.

```sh
git checkout develop && git pull
make photos-fetch
make photos-push
```

- `make photos-fetch` asks the **Openverse API** (`https://api.openverse.org/v1/images/`, anonymous, no key) for
  every slot without a photo, with the slot's search words: photographs only, CC0 or the Public Domain Mark, at least
  1600 px wide, landscape for heroes, banners and empty pages; free stock sources first (StockSnap, rawpixel, then
  Wikimedia, Flickr), drawings and clip art skipped. It downloads the top three per slot to
  `web/packages/media/candidates/` (made smaller with macOS's `sips`, about 2048 px) and records each one's title,
  creator, source, landing page, licence and size in `candidates/candidates.json`. It pauses between calls, waits on
  "too many requests" and stops politely when Openverse keeps refusing — **run it again later and it continues where
  it stopped** (slots already searched are skipped). A full run is about 270 slots × 4 calls, so expect it to take
  a while and to need a second run the next day. Options: `COMMERCIAL=1` also accepts CC BY / CC BY-SA where CC0
  finds too little (re-searches the empty slots), `GROUP=category` (or `hero`, `banner`, `empty`, `cuisine`, `demo`,
  `sell`), `LIMIT=20`.
- `make photos-push` commits the candidates on a new branch `photos/candidates-` plus today's date, from the latest
  `develop`, and pushes it; it prints the branch name. It refuses to run when the working tree has other changes.
  You are left on that branch (`git switch develop` goes back).

What happens next: the lead (or an agent) checks out that branch, looks at the candidates, picks one per slot with
`make photos-pick`, builds, looks at every page, and opens a pull request with only `source/` and `photos.json`. The
candidates branch is then deleted (its photos are big and unpicked ones must not stay around).

## 4. Picking a candidate

On the candidates branch, with `web/` installed (`pnpm install` in `web/`):

```sh
make photos-pick SLOT=category/service.home-trades.plumber N=2 \
  ALT_EN="A plumber fitting a pipe under a kitchen sink" \
  ALT_FR="Un plombier qui pose un tuyau sous un évier de cuisine" FOCAL=0.5,0.45
# or many at once: PICKS=picks.json — [{ "slot", "n", "alt": { "en", "fr" }, "focal": { "x", "y" } }, …]
make photos
```

`photos-pick` copies the candidate to `source/` (at most 2048 px) and writes its entry with the attribution
`candidates.json` recorded (site `openverse`, the source site, creator, landing page, licence and deed). Then
`make photos` builds it. Commit `web/packages/media/source/`, `photos.json` and the regenerated
`mobile/packages/mobile-kit/src/photos/library.generated.ts` — never `candidates/`.

## 5. Adding a photo by hand (Pexels, Unsplash)

1. `make photos-slots` — choose a slot; its line says what to search for.
2. On pexels.com or unsplash.com, open the photo's page; check the people, signs and brands rules above.
3. Download a large size (Pexels: "Large", about 1900 px wide; Unsplash: "Medium" or "Large", 1920–2400 px) as JPEG.
   Keep it under 2.5 MB.
4. Save it as `web/packages/media/source/` + the slot + `.jpg`, e.g. `source/hero/home-main.jpg`.
5. Add the entry to `photos.json` (the example in § 1): the photo page's URL as `sourceUrl`, the photographer's name
   and profile, the licence exactly as the table above writes it, today's date, alt text in both languages, the focal
   point.
6. `make photos`, then look at the pages that use it (`make ui-shots`, § 7) and commit.

## 6. Building the variants

`make photos` (also run by every consumer build and `pnpm dev:consumer`, and by the consumer image's Dockerfile):

- cuts every photo to three crops around its focal point — **square** (tiles), **4:3** (cards), **16:9** (banners and
  heroes) — at 320, 640, 960 and 1440 px wide (never wider than the source allows), as **AVIF, WebP and JPEG**
  (sharp), into `web/apps/consumer/public/photos/` (git-ignored). File names carry the source's content hash
  (`category/x/card-640.0a1b2c3d4e.avif`), so `/photos/` is served with a year's immutable cache, like `/assets/`.
  Variants already built are kept; variants nothing links any more are removed. About 3 s a photo the first time.
- writes each entry's `width`, `height`, `hash` and `placeholder` (its dominant colour, shown while it loads) into
  `photos.json`;
- writes `credits.json` next to the variants (`/photos/credits.json`): the machine-readable attribution — slot, source
  file, title, photographer, the photo's page, licence and deed — that CC BY asks for, shipped with the images (and to a
  CDN with `--out`); the `/credits` page links it;
- lists the banner and hero photos whose 16:9 crop is narrower than 1440 px. Nothing is ever upscaled: variants are
  only cut at widths the source has, and the consumer's `Scene` caps the picture at its widest variant
  (`--nl-scene-max`), so in a wider box it sits centred on the photo well instead of being stretched;
- regenerates the native apps' list (`mobile/packages/mobile-kit/src/photos/library.generated.ts`).

## 7. Where the pages use them

- **`@northline/ui` `Photo`**: a `picture` element of a slot (or several, first with a photo wins) — the crop's AVIF,
  WebP and JPEG `srcset`s with `sizes`, lazy unless `priority` (heroes and banners), `object-position` on the focal
  point, the placeholder colour behind it; its `fallback` while no slot has a photo.
- **The consumer site's `Scene`** (`features/art/Scene.tsx`): `Photo` with the old drawing (`Art`) as its fallback. It
  is used for the home hero collage (`hero/home-*`) and tiles, the Services / Shop / Food / Sell / Help banners
  (`banner/*`), category, department and cuisine tiles and banners (`category/*`, `cuisine/*`), the sell cards
  (`sell/*`), card fallbacks (a product's or service's category), provider and kitchen covers, sign-in and register
  (`hero/sign-in`, `hero/register`), the 404 and error pages (`empty/*`), the booking and checkout-complete pictures.
  Dish thumbnails on a kitchen's menu and cart lines still draw (they have no category; dish photos have no public
  URL yet).
- **The apps**: mobile-kit `Photo` / `Scene`, the same slots, every crop width as `Image` sources.
- Merchants' photos win where an API has one: product cards (`imageUrl`), shop cards (the shop's best seller's
  photo), provider cards and pages (their most booked service's photo), search results (services too, `imageKey`).

To see the pages: `make ui-shots` (docs/LOCAL_DEVELOPMENT.md). With an empty library every page shows its drawings.

## 8. Serving: the site, a CDN, the apps

- The consumer site serves `/photos/` itself (built into its image). To serve them from a CDN, upload the output of
  `node web/packages/media/scripts/build.mjs --out` (a folder) to a bucket behind the CDN and set
  `NL_PHOTOS_BASE_URL` on the consumer site (local/dev/staging/prod runbooks). `img-src` already allows `https:`.
- The native apps load the same files over the network: from `EXPO_PUBLIC_SITE_ORIGIN/photos` (consumer app) or
  `EXPO_PUBLIC_PHOTOS_BASE_URL` (both apps; the courier's `eas.json` sets it per environment). Nothing is bundled in
  the app, so a new photo needs no app release (only the slot list does: `library.generated.ts`).

## 9. Unblocking downloads in this environment

The agents' environment blocks the photo hosts (Pexels, Unsplash, Openverse and its sources), which is why § 3 runs on
a Mac. To let an agent download candidates itself instead, the owner adds the hosts to the environment's **network
access → allowed domains** setting (claude.ai → the environment's settings): `api.openverse.org`,
`images.pexels.com`, `www.pexels.com`, `images.unsplash.com`, `unsplash.com`, `live.staticflickr.com`,
`upload.wikimedia.org`, `cdn.stocksnap.io`, `images.rawpixel.com`. Then `make photos-fetch` works here too.

### The Open Images route (what filled the library in October 2026)

**S-181 (2026-10-23), originals instead of 1024 px copies.** Most files on the CVDF mirror are resized to 1024 px, but
about 1,400 of its 1.9 million are the photographers' originals (2,000–7,000 px). They were found by listing the bucket
with sizes (`?list-type=2`, files over 1.6 MB), their human-verified labels and Flickr metadata streamed from
`storage.googleapis.com/openimages/v7/oidv7-train-annotations-human-imagelabels.csv` and
`v6/oidv6-train-images-with-labels-with-rotation.csv` (filtered on the fly; nothing that size is kept), photos labelled
Person, Text, Flag, Logo, Drawing and the like were dropped, and a person picked from contact sheets. Originals are
saved at most **1600 px** on the long side, JPEG q80; 1024 px copies are kept as they are (never enlarged). Every
entry keeps its Flickr page, photographer, profile, title and the CC BY 2.0 deed. The originals themselves are not in
git: the photo id is the last part of the file name on the mirror, so `https://open-images-dataset.s3.amazonaws.com/<train|test|validation>/<id>.jpg`
fetches one again.

Openverse throttled the owner's Mac and the photo hosts are blocked here, but Google's Open Images dataset is
reachable (its metadata on `storage.googleapis.com/openimages/`, its files on `open-images-dataset.s3.amazonaws.com`).
Each slot's query was mapped to the dataset's human-verified labels (Bakery, Barber, Snowplow, …), photos with text,
logos, drawings or black-and-white labels were left out, the top 16 per slot were laid out on contact sheets and a
person picked one per slot by eye (or none: the slot then falls back). The picks went through `make photos-pick`
(`candidates.json` with `site: openimages`), so every entry carries the Flickr page, photographer and deed. Replace
any of them with a sharper Pexels or Unsplash photo later by the usual route (§ 4–5); people photos especially, since
Flickr photos come without model releases.

## 10. Demo data

Each demo business and listing of `scripts/demo/data/marketplace.json` names a `photo` slot (`demo/merchant/<key>`
and `demo/listing/<business>.<listing>`; listed by `make photos-slots`). `make demo-data` uploads a slot's photo
through the product's own upload flow — the product image, the service's photo of the work, the business's logo (a
square PNG made from its photo when `web/` is installed) — and, while a slot has no photo, draws a picture as before
(logos only). Since S-181 every demo business and listing has its own photo (a test in
`scripts/demo/demo-data.test.mjs` checks it, and that no two are the same); a product without one stops the run instead
of uploading a drawn picture of its name. The pictures for the listing the presenter adds live are library photos too.
