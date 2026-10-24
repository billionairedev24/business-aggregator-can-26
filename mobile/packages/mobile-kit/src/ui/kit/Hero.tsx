import { useId, type ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { Defs, LinearGradient, RadialGradient, Rect, Stop } from 'react-native-svg';

import { MIN_TARGET, radius, semantic, space } from '../../theme';
import { Icon, type IconName } from '../Icon';
import { Sticker } from './Commerce';
import { CAP, LINE, TYPE, textStyles } from './tokens';

export interface HomeHeroTrust {
  key: string;
  icon: IconName;
  label: string;
}

/** The run card: the next pooled run and its cut-off ("Tonight's pooled run · Closes 5:20 p.m. · 2 h 48 min left"). */
export interface HomeHeroRun {
  kicker: string;
  title: string;
  /** The time left, on the yellow sticker; absent when there is nothing to count down. */
  left?: string;
  note?: string;
}

export interface HomeHeroDepartment {
  key: string;
  icon: IconName;
  kicker: string;
  title: string;
  meta: string;
  /** The photo, filling the tile (the kit's `Photo`; decorative: the tile's words name it). */
  photo?: ReactNode;
  onPress: () => void;
  testID?: string;
}

/** The phone's tile height (136, the web's `--nl-hhero-tile` on phones) and the part of the last one below the band (70). */
const TILE = space[16] * 2 + space[2];
const EDGE = space[16] + space[1.5];
/** The run card holds its size while it loads (the web's 112 px on phones). */
const RUN_MIN = space[16] + space[12];

/**
 * The consumer home's hero band (S-188 brand layer, the web's `HomeHero`; design/revamp/hybrid/index.html "Consumer home
 * · phone (390)"): the deep-violet band with its violet glow, the headline in the display face (Archivo expanded 800, 36)
 * with its last words in the light violet, the trust line, the run card with the time left on the yellow sticker, then
 * three department photo tiles stacked 136 pt tall, the last one reaching 70 pt onto the white page. No violet text on
 * violet: text on the band is white, `hero-text` or `hero-accent`; the tiles' arrow square is white with a violet arrow.
 */
export function HomeHero({
  title,
  accent,
  trust = [],
  run,
  runLabel,
  loadingLabel,
  departments,
  departmentsLabel,
  testID,
}: {
  /** The headline's lead ("Everything Kensington needs,") and its light-violet end ("tonight."). */
  title: string;
  accent?: string;
  trust?: readonly HomeHeroTrust[];
  /** `undefined` while it loads (its box holds its size), `null` for none. */
  run: HomeHeroRun | null | undefined;
  runLabel: string;
  loadingLabel?: string;
  departments: readonly HomeHeroDepartment[];
  departmentsLabel: string;
  testID?: string;
}) {
  // gradient ids unique per hero: a stack keeps an earlier screen mounted but hidden, and a browser draws no gradient
  // whose definition sits in a hidden subtree
  const id = `nl-hero-${useId().replace(/[^a-zA-Z0-9]/g, '')}`;
  return (
    <View style={styles.hero} testID={testID}>
      <View style={styles.band} pointerEvents="none">
        <Svg width="100%" height="100%" preserveAspectRatio="none" accessible={false} importantForAccessibility="no-hide-descendants">
          <Defs>
            <LinearGradient id={`${id}-bg`} x1="0" y1="0" x2="0" y2="1">
              <Stop offset="0" stopColor={semantic['hero-bg']} />
              <Stop offset="1" stopColor={semantic['hero-scrim']} />
            </LinearGradient>
            <RadialGradient id={`${id}-glow`} cx="86%" cy="-10%" rx="70%" ry="60%" fx="86%" fy="-10%">
              <Stop offset="0" stopColor={semantic['hero-glow']} stopOpacity={0.55} />
              <Stop offset="0.7" stopColor={semantic['hero-glow']} stopOpacity={0} />
            </RadialGradient>
            <RadialGradient id={`${id}-low`} cx="4%" cy="100%" rx="60%" ry="40%" fx="4%" fy="100%">
              <Stop offset="0" stopColor={semantic['hero-glow']} stopOpacity={0.3} />
              <Stop offset="0.7" stopColor={semantic['hero-glow']} stopOpacity={0} />
            </RadialGradient>
          </Defs>
          <Rect x="0" y="0" width="100%" height="100%" fill={`url(#${id}-bg)`} />
          <Rect x="0" y="0" width="100%" height="100%" fill={`url(#${id}-glow)`} />
          <Rect x="0" y="0" width="100%" height="100%" fill={`url(#${id}-low)`} />
        </Svg>
      </View>

      <Text accessibilityRole="header" style={styles.title} testID={testID ? `${testID}-title` : undefined}>
        {title}
        {accent ? <Text style={styles.accent}>{` ${accent}`}</Text> : null}
      </Text>

      {trust.length ? (
        <View style={styles.trust}>
          {trust.map((i) => (
            <View key={i.key} style={styles.trustItem}>
              <Icon name={i.icon} size={TYPE.section} color={semantic['hero-accent']} />
              <Text style={styles.trustText}>{i.label}</Text>
            </View>
          ))}
        </View>
      ) : null}

      {run === null ? null : (
        <View
          style={styles.run}
          accessible
          accessibilityRole="summary"
          accessibilityLabel={run ? [runLabel, run.kicker, run.title, run.left, run.note].filter(Boolean).join(', ') : (loadingLabel ?? runLabel)}
          accessibilityState={{ busy: run === undefined }}
          testID={testID ? `${testID}-run` : undefined}
        >
          <View style={styles.runFrost} />
          {run === undefined ? (
            <>
              <View style={[styles.ghost, styles.ghostK]} />
              <View style={[styles.ghost, styles.ghostT]} />
              <View style={[styles.ghost, styles.ghostS]} />
            </>
          ) : (
            <>
              <View style={styles.runK}>
                <Icon name="truck" size={TYPE.body} color={semantic['hero-accent']} />
                <Text style={styles.runKText} maxFontSizeMultiplier={CAP.control}>
                  {run.kicker}
                </Text>
              </View>
              <View style={styles.runRow}>
                <Text style={styles.runTitle}>{run.title}</Text>
                {run.left ? <Sticker label={run.left} testID={testID ? `${testID}-left` : undefined} /> : null}
              </View>
              {run.note ? <Text style={styles.runNote}>{run.note}</Text> : null}
            </>
          )}
        </View>
      )}

      <View style={styles.depts} accessibilityLabel={departmentsLabel} accessibilityRole="list">
        {departments.map((d) => (
          <Pressable
            key={d.key}
            accessibilityRole="button"
            accessibilityLabel={`${d.title}, ${d.meta}`}
            onPress={d.onPress}
            style={styles.tile}
            testID={d.testID}
          >
            {({ pressed }) => (
              <>
                <View style={styles.tilePhoto}>{d.photo}</View>
                <Svg style={StyleSheet.absoluteFill} width="100%" height="100%" preserveAspectRatio="none" accessible={false} importantForAccessibility="no-hide-descendants">
                  <Defs>
                    <LinearGradient id={`${id}-scrim-${d.key}`} x1="0" y1="0" x2="0" y2="1">
                      <Stop offset="0.28" stopColor={semantic['hero-scrim']} stopOpacity={0} />
                      <Stop offset="0.62" stopColor={semantic['hero-scrim']} stopOpacity={0.62} />
                      <Stop offset="1" stopColor={semantic['hero-scrim']} stopOpacity={0.9} />
                    </LinearGradient>
                  </Defs>
                  <Rect x="0" y="0" width="100%" height="100%" fill={`url(#${id}-scrim-${d.key})`} />
                </Svg>
                <View style={styles.tileCopy}>
                  <View style={styles.tileK}>
                    <Icon name={d.icon} size={TYPE.price - LINE.bold} color={semantic['hero-tile-meta']} />
                    <Text style={styles.tileKText} maxFontSizeMultiplier={CAP.control}>
                      {d.kicker}
                    </Text>
                  </View>
                  <Text style={textStyles.displayTile} numberOfLines={1} maxFontSizeMultiplier={CAP.control}>
                    {d.title}
                  </Text>
                  <Text style={styles.tileMeta} numberOfLines={1} maxFontSizeMultiplier={CAP.control}>
                    {d.meta}
                  </Text>
                </View>
                <View style={[styles.go, pressed && styles.goPressed]}>
                  <Icon name="arrow-right" size={TYPE.section + LINE.bold} color={pressed ? semantic['action-primary-text'] : semantic['action-primary']} />
                </View>
              </>
            )}
          </Pressable>
        ))}
      </View>
    </View>
  );
}

const GO = MIN_TARGET - space[1.5];

const styles = StyleSheet.create({
  hero: { paddingTop: space[5], paddingHorizontal: space[4] },
  band: { position: 'absolute', top: 0, left: 0, right: 0, bottom: EDGE, backgroundColor: semantic['hero-bg'] },
  title: { ...StyleSheet.flatten(textStyles.displayHero) },
  accent: { color: semantic['hero-accent'] },
  trust: { gap: space[1.5], marginTop: space[3.5] },
  trustItem: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  trustText: { ...StyleSheet.flatten(textStyles.label), fontFamily: textStyles.body.fontFamily, color: semantic['hero-text'], flexShrink: 1 },
  run: {
    marginTop: space[4],
    minHeight: RUN_MIN,
    justifyContent: 'center',
    gap: space[1],
    paddingVertical: space[3],
    paddingHorizontal: space[3.5],
    borderRadius: radius.panel,
    borderWidth: LINE.hair,
    borderColor: semantic['hero-card-line'],
    overflow: 'hidden',
  },
  runFrost: { ...StyleSheet.flatten(StyleSheet.absoluteFill), backgroundColor: semantic['header-text'], opacity: 0.07 },
  runK: { flexDirection: 'row', alignItems: 'center', gap: space[1.5] },
  runKText: { ...StyleSheet.flatten(textStyles.caps), letterSpacing: TYPE.caps * 0.08, color: semantic['header-muted'] },
  runRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: space[3] },
  runTitle: { ...StyleSheet.flatten(textStyles.runTime), flexShrink: 1 },
  runNote: { ...StyleSheet.flatten(textStyles.label), fontFamily: textStyles.body.fontFamily, color: semantic['hero-text'] },
  ghost: { height: space[3], borderRadius: radius.status, backgroundColor: semantic['header-hover-bg'] },
  ghostK: { width: '45%' },
  ghostT: { width: '55%', height: space[6] },
  ghostS: { width: '70%' },
  depts: { gap: space[2.5], marginTop: space[4] },
  tile: {
    height: TILE,
    borderRadius: radius.panel,
    overflow: 'hidden',
    backgroundColor: semantic['hero-scrim'],
    boxShadow: `0 ${space[3]}px ${space[6]}px -${space[2]}px ${semantic['hero-scrim']}`,
  },
  tilePhoto: { ...StyleSheet.flatten(StyleSheet.absoluteFill) },
  tileCopy: { position: 'absolute', left: space[4], right: space[16], bottom: space[3.5], gap: space[1] },
  tileK: { flexDirection: 'row', alignItems: 'center', gap: space[1.5] },
  tileKText: { ...StyleSheet.flatten(textStyles.caps), letterSpacing: TYPE.caps * 0.09, color: semantic['hero-tile-meta'] },
  tileMeta: { ...StyleSheet.flatten(textStyles.label), fontFamily: textStyles.body.fontFamily, color: semantic['hero-tile-meta'] },
  go: { position: 'absolute', right: space[3.5], bottom: space[3.5], width: GO, height: GO, alignItems: 'center', justifyContent: 'center', borderRadius: radius.control, backgroundColor: semantic.surface },
  goPressed: { backgroundColor: semantic['action-primary'] },
});
