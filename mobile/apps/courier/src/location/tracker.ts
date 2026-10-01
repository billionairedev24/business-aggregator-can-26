import * as Location from 'expo-location';
import * as TaskManager from 'expo-task-manager';
import { Platform } from 'react-native';

import { colors } from '@northline/mobile-kit';

import { PING_INTERVAL_MS } from '../config';
import { services } from '../services';
import { Pinger, type Position } from './pinger';

/**
 * The courier's location while a run is open (S-87, S-88's privacy decision: the api keeps the latest position only).
 *
 * - Background permission granted: OS location updates delivered to {@link LOCATION_TASK} even with the app in the
 *   background or the screen off; on Android a foreground service shows a notification for as long as it runs.
 * - Only "while using the app": updates while the app is open.
 * - Started when a run is open, stopped when it ends, the shift ends or the courier signs out; the pinger also stops
 *   it when the api says there is no run any more.
 */
export const LOCATION_TASK = 'nl-courier-run-location';

export type LocationAccess = 'background' | 'foreground' | 'denied' | 'undetermined' | 'unavailable';

let pinger: Pinger | null = null;
let foreground: Location.LocationSubscription | null = null;

export function runPinger(): Pinger {
  pinger ??= new Pinger({
    ping: (lat, lng, heading) => services().courier.ping(lat, lng, heading),
    run: () => services().courier.run(),
    stop: () => void stopTracking(),
  });
  return pinger;
}

const toPosition = (l: Location.LocationObject): Position => ({
  lat: l.coords.latitude,
  lng: l.coords.longitude,
  heading: l.coords.heading,
  at: l.timestamp,
});

/** Registers the background task. Must run at module load (the root layout imports this), also on a cold start. */
export function defineLocationTask() {
  if (Platform.OS === 'web' || TaskManager.isTaskDefined(LOCATION_TASK)) return;
  TaskManager.defineTask<{ locations: Location.LocationObject[] }>(LOCATION_TASK, async ({ data, error }) => {
    if (error || !data?.locations?.length) return;
    await runPinger().offer(data.locations.map(toPosition));
  });
}

export async function locationAccess(): Promise<LocationAccess> {
  if (Platform.OS === 'web') return 'unavailable';
  const fg = await Location.getForegroundPermissionsAsync();
  if (fg.status !== 'granted') return fg.status === 'undetermined' ? 'undetermined' : 'denied';
  const bg = await Location.getBackgroundPermissionsAsync();
  return bg.status === 'granted' ? 'background' : 'foreground';
}

/** Asks for "while using", then "always" (each OS shows its own dialog after the app's explanation). */
export async function requestLocationAccess(): Promise<LocationAccess> {
  if (Platform.OS === 'web') return 'unavailable';
  const fg = await Location.requestForegroundPermissionsAsync();
  if (fg.status !== 'granted') return 'denied';
  const bg = await Location.requestBackgroundPermissionsAsync();
  return bg.status === 'granted' ? 'background' : 'foreground';
}

export interface TrackingTexts {
  notificationTitle: string;
  notificationBody: string;
}

/** Starts sending positions for the open run; returns how (or 'denied' / 'unavailable'). */
export async function startTracking(texts: TrackingTexts): Promise<LocationAccess> {
  const access = await locationAccess();
  if (access === 'unavailable' || access === 'denied' || access === 'undetermined') return access;
  runPinger().runSeen();
  if (access === 'background') {
    if (await Location.hasStartedLocationUpdatesAsync(LOCATION_TASK)) return access;
    await Location.startLocationUpdatesAsync(LOCATION_TASK, {
      accuracy: Location.Accuracy.High,
      timeInterval: PING_INTERVAL_MS,
      distanceInterval: 0,
      deferredUpdatesInterval: PING_INTERVAL_MS,
      pausesUpdatesAutomatically: false,
      activityType: Location.ActivityType.OtherNavigation,
      showsBackgroundLocationIndicator: true,
      foregroundService: { ...texts, notificationColor: colors.accent, killServiceOnDestroy: false },
    });
  } else if (!foreground) {
    foreground = await Location.watchPositionAsync(
      { accuracy: Location.Accuracy.High, timeInterval: PING_INTERVAL_MS, distanceInterval: 0 },
      (l) => void runPinger().offer([toPosition(l)]),
    );
  }
  return access;
}

export async function stopTracking() {
  foreground?.remove();
  foreground = null;
  if (Platform.OS === 'web') return;
  try {
    if (await Location.hasStartedLocationUpdatesAsync(LOCATION_TASK)) await Location.stopLocationUpdatesAsync(LOCATION_TASK);
  } catch {
    // the task was not registered on this run of the app
  }
}
