import { expect, test } from "@playwright/test";

/**
 * The only runtime coverage of the offline shell.
 *
 * Every other spec runs with `serviceWorkers: "block"` so worker-controlled caching cannot make a
 * stale build look healthy. Without this file the caching strategy was asserted only by comparing
 * string positions in sw.js, which cannot tell whether the worker installs, claims clients, serves
 * a navigation offline, or drops superseded caches.
 */
test.use({ serviceWorkers: "allow" });

async function activeWorkerCache(page) {
  return page.evaluate(async () => {
    await navigator.serviceWorker.ready;
    const keys = await caches.keys();
    return keys.filter((key) => key.startsWith("mecon-"));
  });
}

test("the service worker caches the shell and serves a navigation while offline", async ({ page, context }) => {
  await page.goto("/");
  await expect(page.getByRole("status")).toBeVisible();

  const caches = await activeWorkerCache(page);
  expect(caches).toHaveLength(1);
  expect(caches[0]).toContain("mecon-free-practice");

  const cachedShell = await page.evaluate(async (cacheName) => {
    const cache = await window.caches.open(cacheName);
    const entries = await cache.keys();
    return entries.map((request) => new URL(request.url).pathname);
  }, caches[0]);
  expect(cachedShell).toContain("/index.html");
  expect(cachedShell).toContain("/fonts/Bravura.otf");

  // The first load races the worker's activation, so its hashed assets are fetched uncontrolled and
  // never reach the runtime cache. One controlled reload is what actually makes the shell offline-
  // capable — and asserting that is the point of running the worker instead of reading its source.
  await page.reload();
  await expect(page.getByRole("status")).toBeVisible();
  await expect.poll(async () => page.evaluate(async () => {
    const cache = await window.caches.open((await window.caches.keys()).find((key) => key.startsWith("mecon-")));
    const entries = await cache.keys();
    return entries.filter((request) => new URL(request.url).pathname.endsWith(".js")).length;
  })).toBeGreaterThan(0);

  // Navigation is network-first, so the offline copy must only take over when the network fails.
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByRole("status")).toBeVisible();
  await expect(page.locator(".file-button")).toBeVisible();
  await expect(page.getByText("revision 0", { exact: true })).toBeVisible();

  await context.setOffline(false);
});
