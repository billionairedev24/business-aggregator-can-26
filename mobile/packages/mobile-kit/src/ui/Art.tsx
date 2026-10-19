import { useId } from 'react';
import { StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';
import Svg, { Circle, Defs, Ellipse, G, LinearGradient, Path, RadialGradient, Rect, Stop } from 'react-native-svg';

import { ramp, semantic } from '../theme';
import { ART_PATHS, type ArtIcon } from './art.generated';
import { subjectFor, toneFor, type Subject, type Tone } from './artSubjects';

export { TONES, subjectFor, toneFor, type Subject, type Tone } from './artSubjects';

export interface ArtProps {
  /** What to draw: a category slug, department slug, shop or product name (see `subjectFor`). */
  subject: string;
  /** The colour family; by default a stable one for the subject. */
  tone?: Tone;
  /** Override the drawn subject (e.g. the empty cart's basket). */
  draw?: Subject;
  /**
   * cover (default): the scene fills the box and is cropped at the sides — tiles, cards, thumbnails. contain: the
   * subject keeps its size by the box's height and the backdrop runs on to the sides — wide banners and covers.
   */
  fit?: 'cover' | 'contain';
  style?: StyleProp<ViewStyle>;
  testID?: string;
}

interface Colours {
  c50: string;
  c100: string;
  c200: string;
  ink: string;
  deep: string;
  pop: string;
  popSoft: string;
}

/** Each tone is one ramp of the palette (the web's art.css, the same steps). */
const TONE: Record<Tone, Colours> = {
  sage: { c50: ramp.primary['50'], c100: ramp.primary['100'], c200: ramp.primary['200'], ink: ramp.primary['500'], deep: ramp.primary['700'], pop: ramp.accent['500'], popSoft: ramp.accent['200'] },
  ember: { c50: ramp.accent['50'], c100: ramp.accent['100'], c200: ramp.accent['200'], ink: ramp.accent['500'], deep: ramp.accent['700'], pop: ramp.primary['500'], popSoft: ramp.primary['200'] },
  linen: { c50: semantic.canvas, c100: semantic['surface-sunken'], c200: semantic['border-strong'], ink: ramp.primary['500'], deep: ramp.primary['800'], pop: ramp.accent['500'], popSoft: ramp.accent['200'] },
  mint: { c50: ramp.success['50'], c100: ramp.success['100'], c200: ramp.success['200'], ink: ramp.success['500'], deep: ramp.success['700'], pop: ramp.warning['400'], popSoft: ramp.warning['200'] },
  clay: { c50: ramp.warning['50'], c100: ramp.warning['100'], c200: ramp.warning['200'], ink: ramp.warning['500'], deep: ramp.warning['700'], pop: ramp.primary['400'], popSoft: ramp.primary['200'] },
  stone: { c50: ramp.neutral['50'], c100: ramp.neutral['100'], c200: ramp.neutral['200'], ink: ramp.neutral['700'], deep: ramp.neutral['900'], pop: ramp.accent['500'], popSoft: ramp.accent['200'] },
};

/** A Phosphor fill pictogram placed on the 400 × 300 scene (Phosphor's grid is 256). */
function Pictogram({ icon, x, y, size, color }: { icon: ArtIcon; x: number; y: number; size: number; color: string }) {
  return (
    <G transform={`translate(${x} ${y}) scale(${size / 256})`} fill={color}>
      {ART_PATHS[icon].map((d, i) => (
        <Path key={i} d={d} />
      ))}
    </G>
  );
}

function Scene({ subject, c }: { subject: Subject; c: Colours }) {
  return (
    <>
      <Ellipse cx={196} cy={222} rx={86} ry={11} fill={c.deep} opacity={0.14} />
      <Circle cx={92} cy={78} r={7} fill={c.popSoft} />
      <Circle cx={318} cy={62} r={5} fill={c.pop} opacity={0.7} />
      <Circle cx={334} cy={168} r={9} fill={semantic.surface} opacity={0.8} />
      <Pictogram icon={subject.main} x={128} y={64} size={144} color={c.ink} />
      {subject.side ? (
        <G>
          <Circle cx={262} cy={180} r={34} fill={semantic.surface} stroke={c.c200} strokeWidth={2} />
          <Pictogram icon={subject.side} x={239} y={157} size={46} color={c.pop} />
        </G>
      ) : null}
    </>
  );
}

/**
 * An illustrated still life (light direction; the consumer web's `Art`, drawn with react-native-svg): the picture a
 * category, department, shop, product or empty screen shows until a real photograph exists — a soft backdrop and
 * ground in one of the palette's ramps, a light disc, the subject's pictogram with a smaller companion and a few dots.
 * It fills its box (give it a size); colours come from the tokens only. Decorative: hidden from VoiceOver and
 * TalkBack — the text beside it names the thing.
 */
export function Art({ subject, tone, draw, fit = 'cover', style, testID }: ArtProps) {
  const id = useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const what = draw ?? subjectFor(subject);
  const c = TONE[tone ?? toneFor(subject)];
  const backdrop = (
    <>
      <Defs>
        <LinearGradient id={`${id}-sky`} x1="0" y1="0" x2="0" y2="1">
          <Stop offset="0" stopColor={c.c50} />
          <Stop offset="1" stopColor={c.c100} />
        </LinearGradient>
        <RadialGradient id={`${id}-glow`} cx="0.5" cy="0.42" r="0.5">
          <Stop offset="0" stopColor={semantic.surface} stopOpacity={0.95} />
          <Stop offset="1" stopColor={semantic.surface} stopOpacity={0.15} />
        </RadialGradient>
      </Defs>
      <Rect width={400} height={300} fill={`url(#${id}-sky)`} />
      <Circle cx={200} cy={128} r={112} fill={`url(#${id}-glow)`} />
      <Path d="M-20 222 Q200 196 420 222 V320 H-20Z" fill={c.c200} opacity={0.7} />
    </>
  );
  const a11y = { accessible: false, importantForAccessibility: 'no-hide-descendants', accessibilityElementsHidden: true } as const;
  if (fit === 'contain') {
    // the backdrop stretched to the box, the scene at the box's height in the middle
    return (
      <View style={[styles.box, { backgroundColor: c.c50 }, style]} testID={testID ?? 'art'} {...a11y}>
        <Svg width="100%" height="100%" style={StyleSheet.absoluteFill} viewBox="0 0 400 300" preserveAspectRatio="none">
          {backdrop}
        </Svg>
        <Svg width="100%" height="100%" style={StyleSheet.absoluteFill} viewBox="0 0 400 300" preserveAspectRatio="xMidYMid meet">
          <Scene subject={what} c={c} />
        </Svg>
      </View>
    );
  }
  return (
    <View style={[styles.box, { backgroundColor: c.c50 }, style]} testID={testID ?? 'art'} {...a11y}>
      <Svg width="100%" height="100%" style={StyleSheet.absoluteFill} viewBox="0 0 400 300" preserveAspectRatio="xMidYMid slice">
        {backdrop}
        <Scene subject={what} c={c} />
      </Svg>
    </View>
  );
}

const styles = StyleSheet.create({ box: { overflow: 'hidden' } });
