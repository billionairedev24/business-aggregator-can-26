import type { ReactNode } from 'react';
import { Image, StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';

import { photoFor, type Crop, type ResolvedPhoto } from '../photos/library';
import { Art, type ArtProps } from './Art';

export interface PhotoProps {
  /** The slot(s) of the photo library, first that has a photo wins (each with its fallbacks). */
  slot: string | readonly (string | null | undefined)[];
  /** An already resolved photo (tests) — wins over `slot`. */
  photo?: ResolvedPhoto | null;
  /** square tiles, 4:3 cards (default), 16:9 banners. */
  crop?: Crop;
  /** Read out by VoiceOver / TalkBack; omitted, the photo is decorative (the text beside it names the thing). */
  label?: string;
  /** What shows while no slot has a photo. */
  fallback?: ReactNode;
  style?: StyleProp<ViewStyle>;
  testID?: string;
}

/**
 * A stock photo of the library (the web's `Photo`): every width of the crop as Image sources (the view's size picks
 * one), cover, on the photo's placeholder colour while it loads; `fallback` while the slot has none.
 */
export function Photo({ slot, photo, crop = 'card', label, fallback = null, style, testID }: PhotoProps) {
  const p = photo !== undefined ? photo : photoFor(slot, crop);
  if (!p) return <>{fallback}</>;
  const a11y = label
    ? ({ accessible: true, accessibilityRole: 'image', accessibilityLabel: label } as const)
    : ({ accessible: false, importantForAccessibility: 'no-hide-descendants', accessibilityElementsHidden: true } as const);
  return (
    <View style={[styles.box, p.placeholder ? { backgroundColor: p.placeholder } : null, style]} testID={testID ?? 'photo'} {...a11y}>
      <Image source={p.sources} style={StyleSheet.absoluteFill} resizeMode="cover" />
    </View>
  );
}

/**
 * A picture of a place in the market (the web's `Scene`): the slot's stock photo, and only while the library has none,
 * the drawn still life of `subject` (`Art`).
 */
export function Scene({ slot, photo, crop, label, style, testID, ...art }: Omit<PhotoProps, 'fallback'> & Omit<ArtProps, 'style' | 'testID'>) {
  return (
    <Photo slot={slot} photo={photo} crop={crop ?? (art.fit === 'contain' ? 'wide' : 'card')} label={label} style={style} testID={testID}
      fallback={<Art {...art} style={style} testID={testID} />} />
  );
}

const styles = StyleSheet.create({ box: { overflow: 'hidden' } });
