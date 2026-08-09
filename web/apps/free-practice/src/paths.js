const appBaseUrl = new URL(import.meta.env.BASE_URL, window.location.origin);

export function appAssetUrl(path) {
  return new URL(path, appBaseUrl).href;
}
