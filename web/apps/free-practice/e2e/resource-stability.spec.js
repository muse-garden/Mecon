import { expect, test } from "@playwright/test";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const fixture = resolve(here, "../../../build/e2e/free-practice-f1-64.mecon");
const minutes = Number(process.env.MECON_SOAK_MINUTES ?? 0);

test("free-practice workbench remains responsive and heap-bounded during sustained use", async ({ page }) => {
  test.skip(!(minutes > 0), "Run through npm run test:e2e:soak");
  test.setTimeout(minutes * 60_000 + 120_000);
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await page.setViewportSize({ width: 600, height: 900 });
  await page.goto("/");
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
  const slots = page.locator(".timeline-slot-control");
  const slotButtons = page.locator(".timeline-slot-control > button:not(.timeline-end-handle)");
  await expect(slots).toHaveCount(64, { timeout: 30_000 });

  const deadline = Date.now() + minutes * 60_000;
  const heaps = [];
  let iterations = 0;
  while (Date.now() < deadline) {
    const tablist = page.getByRole("tablist", { name: "自由练习视图" });
    await tablist.getByRole("tab", { name: "时间轴" }).click();
    // Pointer geometry is covered by the dedicated timeline E2E. Force the semantic control here
    // so adjacent resize handles cannot turn a long-running resource test into a hit-box retry test.
    await slotButtons.nth(iterations % 64).click({ force: true });
    if (iterations % 4 === 0) {
      await tablist.getByRole("tab", { name: "计划" }).click();
      await expect(page.getByLabel("自由练习计划")).toBeVisible();
      await tablist.getByRole("tab", { name: "五线谱" }).click();
      await expect(page.getByLabel("五线谱编辑区")).toBeVisible();
    }
    if (iterations % 10 === 0) {
      const sample = await page.evaluate(() => performance.memory?.usedJSHeapSize ?? null);
      if (sample != null) heaps.push(sample);
    }
    iterations += 1;
    await page.waitForTimeout(500);
  }

  expect(iterations).toBeGreaterThan(Math.max(3, minutes * 20));
  expect(pageErrors).toEqual([]);
  await expect(page.getByRole("status")).toContainText("revision");
  if (heaps.length >= 10) {
    const windowSize = Math.min(5, Math.floor(heaps.length / 2));
    const early = Math.min(...heaps.slice(windowSize, windowSize * 2));
    const late = Math.min(...heaps.slice(-windowSize));
    expect(late - early).toBeLessThan(96 * 1024 * 1024);
  }
});
