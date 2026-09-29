import { MSG } from './validation';

export const MAX_IMAGE_BYTES = 15 * 1024 * 1024;

/** Natural size of an image file, decoded by the browser. */
export function readImageSize(file: File): Promise<{ width: number; height: number }> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => { resolve({ width: img.naturalWidth, height: img.naturalHeight }); URL.revokeObjectURL(url); };
    img.onerror = () => { reject(new Error('decode')); URL.revokeObjectURL(url); };
    img.src = url;
  });
}

/** Client-side image standards (the server re-checks): JPG/PNG, ≤ 15 MB, ≥ 1000 px on the longest side. */
export async function imageProblem(file: File): Promise<string | undefined> {
  if (!/^image\/(jpeg|png)$/.test(file.type) && !/\.(jpe?g|png)$/i.test(file.name)) return MSG.IMAGE_TYPE;
  if (file.size > MAX_IMAGE_BYTES) return MSG.IMAGE_TOO_LARGE;
  try {
    const { width, height } = await readImageSize(file);
    return Math.max(width, height) < 1000 ? MSG.IMAGE_TOO_SMALL : undefined;
  } catch {
    return MSG.IMAGE_TYPE;
  }
}
