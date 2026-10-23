import { useState, type ReactNode } from 'react';
import { Image, PixelRatio, Platform, StyleSheet, View, type LayoutChangeEvent, type StyleProp, type ViewStyle } from 'react-native';

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
  const [width, setWidth] = useState(0);
  if (!p) return <>{fallback}</>;
  const a11y = label
    ? ({ accessible: true, accessibilityRole: 'image', accessibilityLabel: label } as const)
    : ({ accessible: false, importantForAccessibility: 'no-hide-descendants', accessibilityElementsHidden: true } as const);
  return (
    <View
      style={[styles.box, p.placeholder ? { backgroundColor: p.placeholder } : null, style]}
      onLayout={WEB ? (e: LayoutChangeEvent) => setWidth(e.nativeEvent.layout.width) : undefined}
      testID={testID ?? 'photo'}
      {...a11y}
    >
      <Image source={WEB ? (width > 0 ? webSource(sourceFor(p.sources, width)) : undefined) : p.sources} style={StyleSheet.absoluteFill} resizeMode="cover" />
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

/**
 * S-178: react-native-web's Image takes one source (an array draws nothing — why the web build's screenshots showed only
 * the placeholder colour), so on the web the view's width picks it: the narrowest variant at least as wide as the
 * view in device pixels, else the widest (nothing until the view is laid out, so one file loads). iOS and Android get every variant and choose themselves.
 */
const WEB = Platform.OS === 'web';
export function sourceFor<S extends { width: number }>(sources: readonly S[], width: number, ratio = PixelRatio.get()): S | undefined {
  const sorted = [...sources].sort((a, b) => a.width - b.width);
  const need = width * ratio;
  return sorted.find((s) => s.width >= need) ?? sorted[sorted.length - 1];
}

/** react-native-web sizes an Image by its source's width and height when they are given: only the uri, so it fills. */
const webSource = (s: { uri: string } | undefined) => (s ? { uri: s.uri } : undefined);

const styles = StyleSheet.create({ box: { overflow: 'hidden' } });
