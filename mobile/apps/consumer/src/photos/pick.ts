import * as ImagePicker from 'expo-image-picker';

import type { PickedImage } from '@northline/mobile-kit';

/**
 * One image from the phone (mobile gaps part 1), through `expo-image-picker` — the Expo SDK 57 module, chosen over a
 * view-capture module because it does both jobs (a pilot's screenshot from the photo library, a report's photo from the
 * camera or the library) with one native module:
 *
 * - `library`: the system photo picker (iOS PHPicker, Android's photo picker) — no photo-library permission; iOS hands
 *   HEIC photos over as JPEG (`Compatible`);
 * - `camera`: the system camera, after the camera permission (asked here, at the tap; refused → `denied`).
 *
 * No EXIF (it can hold the place a photo was taken). Null when the person cancels.
 */
export async function pickImage(source: 'library' | 'camera', quality = 0.8): Promise<PickedImage | 'denied' | null> {
  const options: ImagePicker.ImagePickerOptions = {
    mediaTypes: ['images'],
    quality,
    exif: false,
    allowsMultipleSelection: false,
    preferredAssetRepresentationMode: ImagePicker.UIImagePickerPreferredAssetRepresentationMode.Compatible,
  };
  if (source === 'camera') {
    const permission = await ImagePicker.requestCameraPermissionsAsync();
    if (!permission.granted) return 'denied';
  }
  const result = source === 'camera' ? await ImagePicker.launchCameraAsync(options) : await ImagePicker.launchImageLibraryAsync(options);
  if (result.canceled || !result.assets?.[0]) return null;
  const a = result.assets[0];
  return { uri: a.uri, mimeType: a.mimeType ?? null, fileSize: a.fileSize ?? null, fileName: a.fileName ?? null, width: a.width, height: a.height };
}
