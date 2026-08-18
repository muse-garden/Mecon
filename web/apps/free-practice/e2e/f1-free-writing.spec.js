import { expect, test } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { loadMeconDocument } from "../../../packages/frozen-score/index.js";

const here = dirname(fileURLToPath(import.meta.url));
const fixture = resolve(here, "../../../build/e2e/free-practice-f1.mecon");
const longFixture = resolve(here, "../../../build/e2e/free-practice-f1-64.mecon");

async function openPracticeFixture(page, path, engineVersion) {
  await expect(page.locator(".app-loading-overlay")).toBeHidden({ timeout: 30_000 });
  const previousRequestId = await page.evaluate(() => (
    window.__MECON_E2E__?.documentRequest()?.latest ?? 0
  ));
  await page.getByLabel("打开 .mecon").setInputFiles(path);
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__?.documentRequest()))
    .toEqual({ latest: previousRequestId + 1, completed: previousRequestId + 1 });
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__?.documentManifest()?.engineVersion
  )), { timeout: 30_000 }).toBe(engineVersion);
  await expect(page.locator(".app-loading-overlay")).toBeHidden({ timeout: 30_000 });
}

async function clickScoreNote(page, occurrence) {
  const point = await page.evaluate((index) => {
    const frame = window.__MECON_E2E__.snapshot();
    const surface = frame.bundle.surfaces[0];
    const noteheads = surface.elements.filter((element) => element.type === "NOTEHEAD");
    const element = noteheads[Math.min(index, noteheads.length - 1)];
    if (!element) throw new Error("Missing score notehead");
    const value = (item) => Number(item?.value ?? item ?? 0);
    const origin = frame.bundle.paginated ? { x: 0, y: surface.contentOffsetY ?? 0 } : {
      x: value(frame.bundle.bounds?.origin?.x), y: value(frame.bundle.bounds?.origin?.y),
    };
    return {
      x: value(element.hitBox.origin.x) + value(element.hitBox.width) / 2 - origin.x,
      y: value(element.hitBox.origin.y) + value(element.hitBox.height) / 2 - origin.y,
    };
  }, occurrence);
  await page.locator("canvas").click({ position: point });
}

test("2/4 practice shows a passive empty beat and a separate add button", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");
  await expect(page.locator(".app-loading-overlay")).toBeHidden({ timeout: 30_000 });
  await expect(page.getByText("revision 0", { exact: true })).toBeVisible({ timeout: 30_000 });

  // Exercise the user path. Loading a fixture that was already saved as 2/4 misses regressions in
  // new-document construction, the pristine-meter intent, and the Worker scene refresh.
  const toolbar = page.getByRole("toolbar", { name: "自由练习工具栏" });
  await toolbar.getByRole("button", { name: "新建", exact: true }).click();
  await expect(page.locator(".app-loading-overlay")).toBeHidden({ timeout: 30_000 });
  await toolbar.getByRole("button", { name: "设置拍号", exact: true }).click();
  const meterDialog = page.getByRole("dialog", { name: "设置拍号" });
  await meterDialog.getByRole("button", { name: "2/4 拍", exact: true }).click();
  await meterDialog.getByRole("button", { name: "应用", exact: true }).click();
  await expect(page.locator(".app-loading-overlay")).toBeHidden({ timeout: 30_000 });
  await expect.poll(() => page.evaluate(() => {
    const meter = window.__MECON_E2E__?.snapshot()?.practiceUpdate?.structure
      ?.effectiveTimeSignature;
    return meter ? { numerator: meter.numerator, denominator: meter.denominator } : null;
  }), { timeout: 30_000 }).toEqual({ numerator: 2, denominator: 4 });
  await expect(toolbar.getByLabel("默认和弦拍数", { exact: true })).toHaveValue("1");
  await expect(page.locator("[data-slot-id]")).toHaveCount(1);

  const state = await page.evaluate(() => {
    const timeline = window.__MECON_E2E__.snapshot().practiceUpdate.timeline;
    const value = (fraction) => fraction.numerator / fraction.denominator;
    return {
      chordEnd: value(timeline.slots[0].onset) + value(timeline.slots[0].duration),
      timelineEnd: value(timeline.end),
      emptySlot: {
        onset: value(timeline.emptySlots[0].onset),
        duration: value(timeline.emptySlots[0].duration),
      },
    };
  });
  expect(state).toEqual({
    chordEnd: 1 / 4,
    timelineEnd: 1 / 2,
    emptySlot: { onset: 1 / 4, duration: 1 / 4 },
  });

  const geometry = await page.evaluate(() => {
    const snapshot = window.__MECON_E2E__.snapshot();
    const surface = document.querySelector(".harmony-timeline").getBoundingClientRect();
    const chord = document.querySelector('[data-slot-id="slot-0"]').getBoundingClientRect();
    const emptyPaint = document.querySelector('svg rect[data-object-id^="empty-slot:"]')
      .getBoundingClientRect();
    const append = document.querySelector('[aria-label="追加和弦槽"]').parentElement
      .getBoundingClientRect();
    const finalGrid = [...document.querySelectorAll('svg [data-object-id^="grid:measure:"]')]
      .at(-1).getBoundingClientRect();
    const finalScoreBarline = snapshot.bundle.surfaces.flatMap((item) => item.elements)
      .filter((element) => element.type === "BARLINE")
      .sort((left, right) => left.measureNumber - right.measureNumber).at(-1);
    const value = (item) => Number(item?.value ?? item ?? 0);
    const scoreOriginX = snapshot.bundle.paginated
      ? 0 : value(snapshot.bundle.bounds?.origin?.x);
    const scoreBarlineRightX = Math.max(...finalScoreBarline.commands.map((command) => (
      value(command.bounds.origin.x) + value(command.bounds.width)
    ))) - scoreOriginX;
    return {
      chord: { x: chord.x - surface.x, width: chord.width },
      emptySlot: {
        x: emptyPaint.x - surface.x - 3,
        y: emptyPaint.y - surface.y,
        width: emptyPaint.width + 6,
        height: emptyPaint.height,
      },
      append: { x: append.x - surface.x, width: append.width },
      finalGridX: finalGrid.x - surface.x,
      scoreBarlineRightX,
    };
  });
  const appendButton = page.getByRole("button", { name: "追加和弦槽", exact: true });
  await expect(page.locator('[aria-label^="空和弦位"]')).toHaveCount(0);
  await expect(page.locator('[data-object-id^="empty-slot:"][data-object-id$=":text"]')).toHaveCount(0);
  await expect(appendButton).toBeVisible();
  expect(Math.abs(geometry.chord.width - geometry.emptySlot.width)).toBeLessThan(1);
  expect(Math.abs(geometry.chord.x + geometry.chord.width - geometry.emptySlot.x)).toBeLessThan(1);
  expect(Math.abs(geometry.emptySlot.x + geometry.emptySlot.width - geometry.finalGridX)).toBeLessThan(1);
  expect(Math.abs(geometry.finalGridX - geometry.scoreBarlineRightX)).toBeLessThan(2);
  expect(Math.abs(geometry.append.x - geometry.finalGridX)).toBeLessThan(1);
  expect(Math.abs(geometry.append.width - geometry.chord.width)).toBeLessThan(1);
  await expect(page.getByLabel("和声时间轴")).toHaveScreenshot(
    "new-2-4-empty-chord-slot.png",
    { animations: "disabled" },
  );

  const revisionBeforePassiveClick = await page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.revision
  ));
  await page.locator(".harmony-timeline").click({
    position: {
      x: geometry.emptySlot.x + 12,
      y: geometry.emptySlot.y + geometry.emptySlot.height / 2,
    },
  });
  await expect(page.locator("[data-slot-id]")).toHaveCount(1);
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.revision
  ))).toBe(revisionBeforePassiveClick);

  await appendButton.click();
  await expect(page.locator("[data-slot-id]")).toHaveCount(2, { timeout: 30_000 });
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.selection.slotId
  ))).toBe("slot-1");
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.timeline.emptySlots.length
  ))).toBe(0);
  await expect(appendButton).toBeVisible();
  const afterAddGeometry = await page.evaluate(() => {
    const surface = document.querySelector(".harmony-timeline").getBoundingClientRect();
    const added = document.querySelector('[data-slot-id="slot-1"]').getBoundingClientRect();
    const append = document.querySelector('[aria-label="追加和弦槽"]').parentElement
      .getBoundingClientRect();
    const finalGrid = [...document.querySelectorAll('svg [data-object-id^="grid:measure:"]')]
      .at(-1).getBoundingClientRect();
    return {
      addedRightX: added.right - surface.x,
      appendX: append.x - surface.x,
      finalGridX: finalGrid.x - surface.x,
    };
  });
  expect(Math.abs(afterAddGeometry.addedRightX - afterAddGeometry.finalGridX)).toBeLessThan(1);
  expect(Math.abs(afterAddGeometry.appendX - afterAddGeometry.finalGridX)).toBeLessThan(1);

  const addedSlot = page.locator('[data-slot-id="slot-1"]');
  const dragAddedEndBy = async (deltaX) => {
    const endHandle = addedSlot.getByRole("button", { name: /调整.*终点/ });
    const handle = await endHandle.boundingBox();
    await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2);
    await page.mouse.down();
    await page.mouse.move(
      handle.x + handle.width / 2 + deltaX,
      handle.y + handle.height / 2,
      { steps: 6 },
    );
    await page.mouse.up();
  };

  // Extend the last chord into a newly created second measure, then pull it back to the first
  // barline. The now-completely-empty and noteless second measure must disappear atomically.
  const oneBeatWidth = (await addedSlot.boundingBox()).width;
  await dragAddedEndBy(oneBeatWidth);
  await expect.poll(() => page.evaluate(() => {
    const update = window.__MECON_E2E__.snapshot().practiceUpdate;
    const value = (fraction) => fraction.numerator / fraction.denominator;
    return {
      duration: value(update.timeline.slots[1].duration),
      end: value(update.timeline.end),
      measures: update.structure.lastMeasure,
      fillers: update.timeline.emptySlots.map((slot) => ({
        onset: value(slot.onset), duration: value(slot.duration),
      })),
    };
  })).toEqual({
    duration: 1 / 2,
    end: 1,
    measures: 2,
    fillers: [{ onset: 3 / 4, duration: 1 / 4 }],
  });
  await expect(page.locator('svg rect[data-object-id^="empty-slot:"]')).toHaveCount(1);

  await dragAddedEndBy(-oneBeatWidth);
  await expect.poll(() => page.evaluate(() => {
    const update = window.__MECON_E2E__.snapshot().practiceUpdate;
    const value = (fraction) => fraction.numerator / fraction.denominator;
    return {
      duration: value(update.timeline.slots[1].duration),
      end: value(update.timeline.end),
      measures: update.structure.lastMeasure,
      fillers: update.timeline.emptySlots.length,
    };
  })).toEqual({ duration: 1 / 4, end: 1 / 2, measures: 1, fillers: 0 });

  // Shortening the newly inserted real chordless slot used to leave a naked gap before the
  // barline because the remainder was smaller than the default chord duration.
  const addedBox = await addedSlot.boundingBox();
  await dragAddedEndBy(-addedBox.width / 2);
  await expect.poll(() => page.evaluate(() => {
    const timeline = window.__MECON_E2E__.snapshot().practiceUpdate.timeline;
    const value = (fraction) => fraction.numerator / fraction.denominator;
    return {
      addedDuration: value(timeline.slots[1].duration),
      fillers: timeline.emptySlots.map((slot) => ({
        onset: value(slot.onset),
        duration: value(slot.duration),
      })),
    };
  })).toEqual({
    addedDuration: 1 / 8,
    fillers: [{ onset: 3 / 8, duration: 1 / 8 }],
  });
  await expect(page.locator('[data-object-id^="empty-slot:"][data-object-id$=":text"]')).toHaveCount(0);
  const shortenedGeometry = await page.evaluate(() => {
    const surface = document.querySelector(".harmony-timeline").getBoundingClientRect();
    const realEmpty = document.querySelector('svg [data-object-id="slot:slot-1"]');
    const filler = document.querySelector('svg rect[data-object-id^="empty-slot:"]');
    const finalGrid = [...document.querySelectorAll('svg [data-object-id^="grid:measure:"]')]
      .at(-1).getBoundingClientRect();
    const realBounds = realEmpty.getBoundingClientRect();
    const fillerBounds = filler.getBoundingClientRect();
    return {
      realRightX: realBounds.right - surface.x + 3,
      fillerX: fillerBounds.x - surface.x - 3,
      fillerRightX: fillerBounds.right - surface.x + 3,
      finalGridX: finalGrid.x - surface.x,
      realFill: realEmpty.getAttribute("fill"),
      fillerFill: filler.getAttribute("fill"),
    };
  });
  expect(Math.abs(shortenedGeometry.realRightX - shortenedGeometry.fillerX)).toBeLessThan(1);
  expect(Math.abs(shortenedGeometry.fillerRightX - shortenedGeometry.finalGridX)).toBeLessThan(1);
  expect(shortenedGeometry.fillerFill).not.toBe(shortenedGeometry.realFill);
  await expect(page.getByLabel("和声时间轴")).toHaveScreenshot(
    "new-2-4-shortened-tail-filler.png",
    { animations: "disabled" },
  );
});

test("lays out compact note properties and highlights uniform states", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");
  await openPracticeFixture(page, longFixture, "free-practice-f1-64");
  await expect(page.getByText("revision 0", { exact: true })).toBeVisible({ timeout: 30_000 });
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().bundle.surfaces
      .flatMap((surface) => surface.elements)
      .filter((element) => element.type === "NOTEHEAD").length
  )), { timeout: 30_000 }).toBeGreaterThan(0);
  const noteProperties = page.locator("#workbench-panel-plan .practice-note-properties");
  await clickScoreNote(page, 0);

  await expect(noteProperties.locator(".practice-role-buttons > button")).toHaveCount(3);
  await expect(noteProperties.locator(".practice-role-filters > label")).toHaveCount(2);
  await expect(noteProperties.locator(".practice-lock-scope")).toHaveCount(3);
  expect(await noteProperties.locator(".practice-role-buttons").evaluate((element) => (
    getComputedStyle(element).display
  ))).toBe("flex");
  expect(await noteProperties.locator(".practice-lock-scopes").evaluate((element) => (
    getComputedStyle(element).display
  ))).toBe("flex");
  const filterAlignment = await noteProperties.locator(".practice-role-filters label").first()
    .evaluate((label) => {
      const checkbox = label.querySelector("input").getBoundingClientRect();
      const text = label.querySelector("span").getBoundingClientRect();
      return Math.abs((checkbox.top + checkbox.bottom) / 2 - (text.top + text.bottom) / 2);
    });
  expect(filterAlignment).toBeLessThan(1);
  const lockCardLayout = await noteProperties.locator(".practice-lock-scope").first()
    .evaluate((card) => {
      const label = card.querySelector(":scope > span").getBoundingClientRect();
      const actions = card.querySelector(":scope > div").getBoundingClientRect();
      return {
        centerDifference: Math.abs((label.top + label.bottom) / 2
          - (actions.top + actions.bottom) / 2),
        flexWrap: getComputedStyle(card).flexWrap,
      };
    });
  expect(lockCardLayout).toEqual({ centerDifference: 0, flexWrap: "wrap" });

  await expect(noteProperties.getByRole("button", { name: "清除内外音标记", exact: true }))
    .toHaveAttribute("aria-pressed", "true");
  await expect(noteProperties.getByRole("button", { name: "解锁音符", exact: true }))
    .toHaveAttribute("aria-pressed", "true");
  await expect(noteProperties.getByRole("button", { name: "解锁声部", exact: true }))
    .toHaveAttribute("aria-pressed", "true");
  await expect(noteProperties.getByRole("button", { name: "解锁谱表", exact: true }))
    .toHaveAttribute("aria-pressed", "true");

  await noteProperties.getByRole("button", { name: "标记为和弦内音", exact: true }).click();
  await expect(noteProperties.getByRole("button", { name: "标记为和弦内音", exact: true }))
    .toHaveAttribute("aria-pressed", "true");
  await noteProperties.getByRole("button", { name: "锁定音符", exact: true }).click();
  await expect(noteProperties.getByRole("button", { name: "锁定音符", exact: true }))
    .toHaveAttribute("aria-pressed", "true");
});

test("marks a selected note and enables shared harmonic-role filters", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");
  await openPracticeFixture(page, longFixture, "free-practice-f1-64");
  await expect(page.getByText("revision 0", { exact: true })).toBeVisible({ timeout: 30_000 });
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().bundle.surfaces
      .flatMap((surface) => surface.elements)
      .filter((element) => element.type === "NOTEHEAD").length
  )), { timeout: 30_000 }).toBeGreaterThan(0);
  const noteProperties = page.locator("#workbench-panel-plan .practice-note-properties");
  await expect(noteProperties).toBeVisible();
  await expect(noteProperties.getByRole("heading", { name: "和弦内外音" })).toBeVisible();
  await expect(noteProperties.getByRole("heading", { name: "锁定情况" })).toBeVisible();
  expect(await noteProperties.evaluate((panel) => (
    panel.nextElementSibling?.textContent?.includes("当前调性") ?? false
  ))).toBe(true);
  await clickScoreNote(page, 0);
  await noteProperties.getByRole("button", { name: "标记为和弦内音", exact: true }).click();
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.document.noteConstraints.harmonicRoles.length
  ))).toBe(1);
  await page.getByRole("button", { name: "锁定声部", exact: true }).click();
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.document.noteConstraints.lockedVoiceTrackIds.length
  ))).toBe(1);
  await page.getByRole("button", { name: "锁定谱表", exact: true }).click();
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.document.noteConstraints.lockedStaffTrackIds.length
  ))).toBe(1);
  const chordChoiceCountBefore = await page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.plan.chordCatalogFilters
      .flatMap((filter) => filter.chordGroups)
      .flatMap((group) => group.choices).length
  ));
  await page.getByLabel("筛选和弦").click();
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.noteConstraints.chordCatalogFilterEnabled
  ))).toBe(true);
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.plan.chordCatalogFilters
      .flatMap((filter) => filter.chordGroups)
      .flatMap((group) => group.choices).length
  ))).toBeLessThan(chordChoiceCountBefore);
  const idiomVariantsBefore = await page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.plan.idiomCatalog.definitions
      .flatMap((definition) => definition.variants).map((variant) => variant.id).sort()
  ));
  await page.getByLabel("筛选惯用进行").click();
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.noteConstraints
  ))).toMatchObject({ chordCatalogFilterEnabled: true, idiomCatalogFilterEnabled: true });
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.plan.idiomCatalog.definitions
      .flatMap((definition) => definition.variants).map((variant) => variant.id).sort()
  )), { timeout: 30_000 }).not.toEqual(idiomVariantsBefore);
});

async function marqueeFirstTwoScoreOnsets(page) {
  const box = await page.evaluate(() => {
    const frame = window.__MECON_E2E__.snapshot();
    const surface = frame.bundle.surfaces[0];
    const value = (item) => Number(item?.value ?? item ?? 0);
    const origin = frame.bundle.paginated ? { x: 0, y: surface.contentOffsetY ?? 0 } : {
      x: value(frame.bundle.bounds?.origin?.x), y: value(frame.bundle.bounds?.origin?.y),
    };
    const fraction = (item) => Number(item?.numerator ?? 0) / Number(item?.denominator ?? 1);
    const sameTime = (left, right) => left.measure === right.measure
      && fraction(left.beat) === fraction(right.beat);
    const slots = frame.practiceUpdate.timeline.slots;
    const renderedEventIds = new Set(surface.elements
      .filter((element) => element.type === "NOTEHEAD").map((element) => element.eventId));
    const eventsBySlot = Object.values(frame.practiceUpdate.score.score.voiceTracks)
      .flatMap((voice) => voice.events ?? []).reduce((result, event) => {
        if (!renderedEventIds.has(event.id)) return result;
        const eventTime = frame.timeAxis.anchors.find((anchor) => sameTime(anchor.scoreTime, event.onset))?.time;
        const absolute = fraction(eventTime);
        const index = slots.findIndex((slot) => fraction(slot.onset) <= absolute
          && fraction(slot.onset) + fraction(slot.duration) > absolute);
        if (index >= 0) {
          if (!result.has(index)) result.set(index, new Set());
          result.get(index).add(event.id);
        }
        return result;
      }, new Map());
    const populatedSlots = [...eventsBySlot.keys()].sort((left, right) => left - right).slice(0, 2);
    const eventIdsBySlot = new Set(populatedSlots.flatMap((index) => [...eventsBySlot.get(index)]));
    const selected = surface.elements.filter((element) => element.type === "NOTEHEAD"
      && eventIdsBySlot.has(element.eventId));
    if (populatedSlots.length < 2 || !selected.length) throw new Error("Missing two populated score slots");
    return {
      left: Math.min(...selected.map((element) => value(element.hitBox.origin.x))) - origin.x - 12,
      top: Math.min(...selected.map((element) => value(element.hitBox.origin.y))) - origin.y - 12,
      right: Math.max(...selected.map((element) => value(element.hitBox.origin.x)
        + value(element.hitBox.width))) - origin.x + 12,
      bottom: Math.max(...selected.map((element) => value(element.hitBox.origin.y)
        + value(element.hitBox.height))) - origin.y + 12,
    };
  });
  const canvas = page.locator("canvas");
  const canvasBox = await canvas.boundingBox();
  await page.mouse.move(canvasBox.x + box.left, canvasBox.y + box.top);
  await page.mouse.down();
  await page.mouse.move(canvasBox.x + box.right, canvasBox.y + box.bottom, { steps: 4 });
  await page.mouse.up();
}

test("new free practice starts from the shared preset and exports a complete container", async ({ page }) => {
  await page.addInitScript(() => { window.showSaveFilePicker = undefined; });
  await page.goto("/");
  await expect(page.locator(".app-loading-overlay")).toBeHidden({ timeout: 30_000 });
  await page.getByRole("toolbar", { name: "自由练习工具栏" })
    .getByRole("button", { name: "新建", exact: true }).click();
  await expect(page.locator(".app-loading-overlay")).toBeHidden({ timeout: 30_000 });
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });
  await expect(page.locator("canvas")).toBeVisible();
  await expect(page.getByRole("toolbar", { name: "自由练习工具栏" })).toBeVisible();
  await expect(page.locator(".score-pane > .score-editor-toolbar")).toBeVisible();
  await expect(page.locator(".score-pane .music-glyph-button").first()).toBeVisible();
  await expect.poll(() => page.evaluate(() => ({
    slots: window.__MECON_E2E__?.snapshot()?.practiceUpdate?.timeline?.slots?.length,
    revision: window.__MECON_E2E__?.snapshot()?.practiceUpdate?.revision,
  }))).toEqual({ slots: 1, revision: 0 });

  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("button", { name: "保存" }).click(),
  ]);
  const exportPath = resolve(here, "../../../build/e2e/browser-new-free-practice.mecon");
  await download.saveAs(exportPath);
  const reopened = await loadMeconDocument(new Uint8Array(await readFile(exportPath)));
  const moduleId = reopened.manifest.workspace.activeModuleId;
  const scoreId = reopened.manifest.activeScoreId;
  expect(reopened.manifest.scores).toHaveLength(1);
  expect(reopened.manifest.modules).toHaveLength(1);
  expect(reopened.manifest.modules[0]).toMatchObject({
    id: moduleId,
    type: "exploration.free-practice",
    scoreId,
  });
  expect(reopened.modules.get(moduleId).payload.workspace.slots).toHaveLength(1);
  expect(reopened.scores.get(scoreId).id).toBe(scoreId);
});

test("both free-practice toolbars replay the common stable-id descriptor", async ({ page }) => {
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });
  const snapshot = await page.evaluate(() => {
    const descriptor = window.__MECON_E2E__.toolbarDescriptor();
    const read = (selector) => [...document.querySelectorAll(selector)].map((element) => ({
      group: element.closest("[data-group]")?.dataset.group,
      id: element.dataset.controlId,
    }));
    return {
      descriptor,
      top: read(".workbench-toolbar [data-control-id]"),
      score: read(".score-pane > .score-editor-toolbar [data-control-id]"),
    };
  });
  const assertOrderedSubset = (actual, layer) => {
    const expected = layer.groups.flatMap((group) => group.controls.map((id) => ({ group: group.id, id })));
    let previous = -1;
    for (const item of actual) {
      const index = expected.findIndex((candidate) => candidate.group === item.group && candidate.id === item.id);
      expect(index, JSON.stringify(item)).toBeGreaterThan(previous);
      previous = index;
    }
  };
  assertOrderedSubset(snapshot.top, snapshot.descriptor.top);
  assertOrderedSubset(snapshot.score, snapshot.descriptor.score);
  expect(snapshot.top.map((item) => item.id)).toEqual(snapshot.descriptor.top.groups
    .flatMap((group) => group.controls));
  expect(snapshot.score.map((item) => item.id)).not.toEqual(expect.arrayContaining([
    "selection.copy", "selection.transposeUp", "input.position", "input.midi",
  ]));
  await expect(page.locator(".workbench-toolbar")).toHaveCSS("min-height", "72px");
  await expect(page.locator(".score-pane .music-glyph-button").first()).toHaveCSS("min-height", "28px");
  const flaggedNote = page.getByRole("button", { name: "八分音符", exact: true });
  await expect(flaggedNote).toHaveClass(/flagged-note/);
  await expect(flaggedNote).toHaveCSS("overflow", "hidden");
  await flaggedNote.click();
  const flaggedBounds = await flaggedNote.evaluate((button) => {
    const buttonBounds = button.getBoundingClientRect();
    const glyphBounds = button.querySelector("span").getBoundingClientRect();
    return { buttonTop: buttonBounds.top, glyphTop: glyphBounds.top };
  });
  expect(flaggedBounds.glyphTop - flaggedBounds.buttonTop).toBeGreaterThanOrEqual(8);

  const slurButton = page.getByRole("button", { name: "圆滑线", exact: true });
  await expect(slurButton.locator("svg.curve-arc path")).toHaveCount(1);
  const slurNoteTops = await slurButton.locator(".curve-note").evaluateAll((notes) =>
    notes.map((note) => note.getBoundingClientRect().top));
  expect(slurNoteTops[0] - slurNoteTops[1]).toBeGreaterThanOrEqual(5);

  const beamGroup = page.getByRole("button", { name: "将选中音符组成符杠组", exact: true });
  for (const label of ["左右都连符杠", "符杠仅连右", "符杠仅连左"]) {
    const beamButton = page.getByRole("button", { name: label, exact: true });
    await expect(beamButton.locator(".beam-stems")).toHaveCount(1);
    await expect(beamButton.locator(".beam-bar")).not.toHaveCount(0);
  }
  await expect(beamGroup.locator(".beam-stems")).toHaveCount(1);
  await expect(beamGroup.locator(".beam-bar")).toHaveCount(1);
  await expect(beamGroup.locator(".beam-bracket")).toHaveCount(1);
  await expect(beamGroup).toHaveCSS("width", "28px");
  await expect(beamGroup).toHaveCSS("height", "28px");
  await page.evaluate(() => document.fonts.ready);
  await expect(page.locator(".workbench-toolbar")).toHaveScreenshot("free-practice-top-toolbar.png", {
    animations: "disabled",
  });
  await expect(page.locator(".score-pane > .score-editor-toolbar")).toHaveScreenshot(
    "free-practice-score-toolbar.png",
    { animations: "disabled" },
  );
  await expect(page.getByLabel("和声时间轴")).toHaveScreenshot("free-practice-timeline.png", {
    animations: "disabled",
  });

});

test("new and open protect unsaved work and save establishes a clean baseline", async ({ page }) => {
  await page.addInitScript(() => {
    window.__MECON_SAVE_PICKER_RESULT__ = "saved";
    window.__MECON_SAVE_PICKER_CALLS__ = 0;
    window.showSaveFilePicker = async () => {
      window.__MECON_SAVE_PICKER_CALLS__ += 1;
      if (window.__MECON_SAVE_PICKER_RESULT__ === "cancelled") {
        throw new DOMException("cancelled", "AbortError");
      }
      return {
        createWritable: async () => ({ write: async () => {}, close: async () => {} }),
      };
    };
  });
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.hasUnsavedChanges())).toBe(false);

  const toolbar = page.getByRole("toolbar", { name: "自由练习工具栏" });
  const defaultBpm = await toolbar.getByLabel("BPM", { exact: true }).inputValue();
  await toolbar.getByRole("button", { name: "BPM加一" }).click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.hasUnsavedChanges())).toBe(true);
  await page.reload();
  await expect(page.getByRole("toolbar", { name: "自由练习工具栏" })).toBeVisible({ timeout: 30_000 });
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.hasUnsavedChanges())).toBe(true);

  let confirmation = "";
  page.once("dialog", async (dialog) => {
    confirmation = dialog.message();
    await dialog.dismiss();
  });
  await toolbar.getByRole("button", { name: "新建", exact: true }).click();
  expect(confirmation).toContain("尚未保存");
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.hasUnsavedChanges())).toBe(true);

  await page.evaluate(() => { window.__MECON_SAVE_PICKER_RESULT__ = "cancelled"; });
  await toolbar.getByRole("button", { name: "保存", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__MECON_SAVE_PICKER_CALLS__)).toBe(1);
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.hasUnsavedChanges())).toBe(true);

  await page.evaluate(() => { window.__MECON_SAVE_PICKER_RESULT__ = "saved"; });
  await toolbar.getByRole("button", { name: "保存", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.hasUnsavedChanges())).toBe(false);

  await toolbar.getByRole("button", { name: "新建", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot().practiceUpdate.revision)).toBe(0);
  await expect(toolbar.getByLabel("BPM", { exact: true })).toHaveValue(defaultBpm);
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.hasUnsavedChanges())).toBe(false);

  await toolbar.getByRole("button", { name: "BPM加一" }).click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.hasUnsavedChanges())).toBe(true);
  page.once("dialog", (dialog) => dialog.accept());
  await openPracticeFixture(page, fixture, "free-practice-f1");
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot().practiceUpdate.revision)).toBe(0);
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.hasUnsavedChanges())).toBe(false);
});

test("top toolbar uses desktop-style controls and wraps without scrollbars", async ({ page }) => {
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });

  const toolbar = page.getByRole("toolbar", { name: "自由练习工具栏" });
  await expect(toolbar).toHaveCSS("min-height", "72px");
  const buttonStyle = await toolbar.getByRole("button", { name: "保存" }).evaluate((button) => ({
    borderWidth: getComputedStyle(button).borderWidth,
    flexDirection: getComputedStyle(button).flexDirection,
    fontSize: getComputedStyle(button).fontSize,
  }));
  expect(buttonStyle).toEqual({ borderWidth: "0px", flexDirection: "column", fontSize: "12px" });
  await expect(toolbar.getByRole("button", { name: "自动写作" })).toHaveAttribute("aria-pressed", "true");
  const gridUnit = toolbar.getByLabel("自动吸附单位", { exact: true });
  await expect(gridUnit).toHaveValue("8");
  await expect(gridUnit.locator("option")).toHaveText([
    "四分音符", "八分音符", "十六分音符", "三十二分音符", "六十四分音符",
  ]);
  await expect(toolbar).toHaveScreenshot("free-practice-top-toolbar.png", { animations: "disabled" });

  const bpm = toolbar.getByLabel("BPM", { exact: true });
  await bpm.fill("");
  await bpm.type("6");
  await expect(bpm).toHaveValue("6");
  await expect(page.getByRole("status")).toHaveText("revision 0");
  await bpm.type("0");
  await expect(bpm).toHaveValue("60");
  await expect(page.getByRole("status")).toHaveText("revision 1");

  await page.setViewportSize({ width: 1000, height: 720 });
  const toolbarOverflow = await toolbar.evaluate((element) => ({
    clientWidth: element.clientWidth,
    scrollWidth: element.scrollWidth,
    clientHeight: element.clientHeight,
    scrollHeight: element.scrollHeight,
  }));
  expect(toolbarOverflow.scrollWidth).toBe(toolbarOverflow.clientWidth);
  expect(toolbarOverflow.scrollHeight).toBe(toolbarOverflow.clientHeight);
});

test("wide workbench partitions plan sections without toolbar duplicates and resizes", async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 });
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });

  const panel = page.locator(".workbench-side");
  await expect(panel.getByRole("heading", { name: "当前调性" })).toBeVisible();
  await expect(panel.getByRole("heading", { name: "和弦详情" })).toBeVisible();
  await expect(panel.locator("details.chord-details")).toHaveAttribute("open", "");
  await expect(panel.getByRole("heading", { name: "和声选择" })).toHaveCount(0);
  await expect(panel.getByRole("heading", { name: "惯用进行" })).toHaveCount(0);
  const lower = page.locator(".workbench-lower");
  await expect(lower.getByRole("heading", { name: "和声选择" })).toBeVisible();
  await expect(lower.getByRole("heading", { name: "惯用进行" })).toBeVisible();
  await expect(panel.getByText("练习设置", { exact: true })).toHaveCount(0);
  await expect(panel.getByText("写作设置", { exact: true })).toHaveCount(0);
  await expect(panel.getByRole("button", { name: "配声", exact: true })).toHaveCount(0);

  const before = await panel.boundingBox();
  const separator = page.getByRole("separator", { name: "调整右侧面板宽度" });
  const handle = await separator.boundingBox();
  await page.mouse.move(handle.x + handle.width / 2, handle.y + 80);
  await page.mouse.down();
  await page.mouse.move(handle.x - 96, handle.y + 80, { steps: 4 });
  await page.mouse.up();
  const after = await panel.boundingBox();
  expect(after.width).toBeGreaterThan(before.width + 80);
  await expect(separator).toHaveAttribute("aria-valuenow", String(Math.round(after.width)));
});

test("tall harmony panel keeps catalog scrolling inside the remaining space", async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 });
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });

  const lowerSeparator = page.getByRole("separator", { name: "调整和声面板高度" });
  const lowerHandle = await lowerSeparator.boundingBox();
  await page.mouse.move(lowerHandle.x + lowerHandle.width / 2, lowerHandle.y + 4);
  await page.mouse.down();
  await page.mouse.move(lowerHandle.x + lowerHandle.width / 2, lowerHandle.y - 240, { steps: 4 });
  await page.mouse.up();

  const metrics = await page.locator(".harmony-workbench-pane").evaluate((pane) => {
    const catalog = pane.querySelector(".chord-catalog-groups");
    const body = pane.querySelector(".harmony-panel-body");
    return {
      paneClientHeight: pane.clientHeight,
      paneScrollHeight: pane.scrollHeight,
      bodyClientHeight: body?.clientHeight ?? 0,
      bodyScrollHeight: body?.scrollHeight ?? 0,
      catalogClientHeight: catalog?.clientHeight ?? 0,
      catalogScrollHeight: catalog?.scrollHeight ?? 0,
    };
  });
  expect(metrics.paneScrollHeight).toBe(metrics.paneClientHeight);
  expect(metrics.bodyScrollHeight).toBe(metrics.bodyClientHeight);
  expect(metrics.catalogClientHeight).toBeGreaterThan(0);
  expect(metrics.catalogScrollHeight).toBeGreaterThan(metrics.catalogClientHeight);
});

test("bass choice writes the visibly selected chord member as the score's actual lowest pitch", async ({ page }) => {
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });

  await page.getByRole("button", { name: "调性", exact: true }).click();
  await page.getByRole("dialog", { name: "调性选择" })
    .getByRole("button", { name: "F", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.document.settings.initialKey)).toEqual({ fifths: -1, mode: "MAJOR" });

  const bassChoices = page.getByRole("group", { name: "低音" });
  await expect(bassChoices.getByRole("button")).toHaveText(["任意", "1", "3", "5"]);
  const dominantBass = bassChoices.getByRole("button", { name: "5", exact: true });
  await dominantBass.click();
  await expect(dominantBass).toHaveAttribute("aria-pressed", "true");
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.writing.phase), { timeout: 60_000 }).not.toBe("RUNNING");

  const result = await page.evaluate(() => {
    const snapshot = window.__MECON_E2E__.snapshot();
    const score = snapshot.update.score;
    const semitones = [0, 2, 4, 5, 7, 9, 11];
    const midiNumber = (pitch) => {
      const octaveOffset = Math.floor(pitch.diatonicSteps / 7);
      const noteInOctave = ((pitch.diatonicSteps % 7) + 7) % 7;
      return 60 + octaveOffset * 12 + semitones[noteInOctave] + pitch.chromaticOffset;
    };
    const pitches = Object.values(score.pitchTracks).flatMap((track) => track.events)
      .flatMap((event) => event.pitches ?? []);
    const lowestMidi = Math.min(...pitches.map(midiNumber));
    return {
      selectedBass: snapshot.practiceUpdate.document.workspace.slots[0].chordChoice.bassPitchClass,
      lowestPitchClass: ((lowestMidi % 12) + 12) % 12,
      pitchCount: pitches.length,
    };
  });
  expect(result.pitchCount).toBeGreaterThan(0);
  expect(result.selectedBass).toBe(0);
  expect(result.lowestPitchClass).toBe(0);
});

test("64-slot renderer anchors align the harmony timeline without blocking the browser", async ({ page }) => {
  await page.goto("/");
  const startedAt = Date.now();
  await openPracticeFixture(page, longFixture, "free-practice-f1-64");
  await expect(page.locator(".timeline-slot-control")).toHaveCount(64, { timeout: 30_000 });
  expect(Date.now() - startedAt).toBeLessThan(30_000);
  await expect(page.getByLabel("和声时间轴")).toHaveAttribute("data-axis-source", "renderer");

  const alignment = await page.evaluate(() => {
    const snapshot = window.__MECON_E2E__.snapshot();
    const anchors = snapshot.timeAxis.anchors.map((anchor) => ({
      time: anchor.time.numerator / anchor.time.denominator,
      x: Number(anchor.x),
    })).sort((left, right) => left.time - right.time);
    const value = (fraction) => fraction.numerator / fraction.denominator;
    const xAt = (time) => {
      const upperIndex = anchors.findIndex((anchor) => anchor.time >= time);
      if (upperIndex <= 0) return anchors[0].x;
      if (upperIndex < 0) return anchors.at(-1).x;
      const lower = anchors[upperIndex - 1];
      const upper = anchors[upperIndex];
      const ratio = (time - lower.time) / (upper.time - lower.time);
      return lower.x + (upper.x - lower.x) * ratio;
    };
    const semanticBounds = document.querySelector(".timeline-semantic-layer").getBoundingClientRect();
    const errors = snapshot.practiceUpdate.timeline.slots.map((slot) => {
      const bounds = document.querySelector(`[data-slot-id="${slot.id}"]`).getBoundingClientRect();
      return Math.abs(bounds.left - semanticBounds.left - xAt(value(slot.onset)));
    });
    return {
      maxError: Math.max(...errors),
      semanticWidth: semanticBounds.width,
      contentEndX: snapshot.timeAxis.contentEndX,
      surfaceWidth: snapshot.timeAxis.surfaceWidth,
    };
  });
  expect(alignment.semanticWidth).toBeGreaterThanOrEqual(alignment.surfaceWidth);
  expect(alignment.maxError).toBeLessThanOrEqual(1);
  await page.locator(".canvas-scroll").evaluate((element) => {
    element.scrollLeft = 420;
    element.dispatchEvent(new Event("scroll", { bubbles: true }));
  });
  await expect.poll(() => page.locator(".harmony-timeline-scroll").evaluate((element) => element.scrollLeft))
    .toBeCloseTo(420, 0);
  const fixedSurfaceWidth = await page.evaluate(() => window.__MECON_E2E__.snapshot().timeAxis.surfaceWidth);
  await page.setViewportSize({ width: 1600, height: 900 });
  await expect.poll(
    () => page.evaluate(() => window.__MECON_E2E__.snapshot().timeAxis.surfaceWidth),
  ).toBe(fixedSurfaceWidth);
});

test("free writing selects a catalog chord, voices it, checks it, alternates, and auditions", async ({ page }) => {
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });
  await expect(page.getByLabel("和声时间轴")).toBeVisible();

  const catalog = page.getByLabel("选择和弦", { exact: true });
  const choices = catalog.locator("[data-choice-id]");
  await expect(choices).not.toHaveCount(0);
  await choices.nth(1).click();

  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.writing.outcome?.type), { timeout: 60_000 }).toBe("solved");
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.playbackTrace()
    .some((entry) => entry.kind === "edit-playback" && entry.type === "excerpt")))
    .toBe(true);
  await expect(page.locator(".score-playhead")).toHaveCount(0);
  await expect(page.getByLabel("自由练习反馈")).toBeVisible();

  const alternate = page.getByRole("button", { name: "换一个结果" });
  await expect(alternate).toBeEnabled({ timeout: 60_000 });
  await alternate.click();
  await expect(page.getByRole("status")).toContainText("revision");

  await page.getByRole("button", { name: "从选择播放" }).click();
  await page.getByRole("button", { name: "暂停" }).click();

  await page.reload();
  await expect(page.getByLabel("和声时间轴")).toBeVisible({ timeout: 30_000 });
});

test("score note selection consumes shared edit audition playback", async ({ page }) => {
  await page.goto("/");
  await openPracticeFixture(page, longFixture, "free-practice-f1-64");
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });

  await clickScoreNote(page, 0);
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.playbackTrace()
    .some((entry) => entry.kind === "edit-playback" && entry.type === "audition")))
    .toBe(true);
  await expect(page.locator(".score-playhead")).toHaveCount(0);
});

test("top toolbar adjusts meter and inserts measure-aligned chord slots", async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 1000 });
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  await expect(page.getByText("revision 0", { exact: true })).toBeVisible({ timeout: 30_000 });
  await page.getByLabel("追加和弦槽").click();
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.structure.pristine
  ))).toBe(false);

  const toolbar = page.getByRole("toolbar", { name: "自由练习工具栏" });
  await toolbar.getByRole("button", { name: /设置拍号|调整拍号/ }).click();
  const meterDialog = page.getByRole("dialog", { name: /设置拍号|调整拍号/ });
  const threeFour = meterDialog.getByRole("button", { name: "3/4 拍" });
  const glyphStyle = await threeFour.locator(".bravura-time-signature").evaluate((element) => ({
    fontFamily: getComputedStyle(element).fontFamily,
    codePoints: [...element.textContent].map((character) => character.codePointAt(0)),
  }));
  expect(glyphStyle.fontFamily).toContain("Bravura");
  expect(glyphStyle.codePoints).toEqual([0xE083, 0xE084]);
  await threeFour.click();
  await meterDialog.getByRole("button", { name: "应用", exact: true }).click();
  const canvas = page.locator("canvas[aria-label='可编辑五线谱']");
  await expect(canvas).toHaveAttribute("data-editor-tool", "timeSignature");
  const measurePoint = await page.evaluate(() => {
    const frame = window.__MECON_E2E__.snapshot();
    const surface = frame.bundle.surfaces[0];
    const value = (item) => Number(item?.value ?? item ?? 0);
    const origin = frame.bundle.paginated ? { x: 0, y: value(surface.contentOffsetY) } : {
      x: value(frame.bundle.bounds?.origin?.x), y: value(frame.bundle.bounds?.origin?.y),
    };
    const position = frame.bundle.timePositions.find((item) => item.timeCode?.measure === 1 &&
      value(item.timeCode?.beat?.numerator) === 0);
    const staff = surface.elements.find((item) => item.type === "STAFF");
    if (!position || !staff) throw new Error("Missing first-measure downbeat or staff");
    return {
      x: value(position.x) - origin.x,
      y: value(staff.hitBox.origin.y) + value(staff.hitBox.height) / 2 - origin.y,
    };
  });
  await canvas.hover({ position: measurePoint });
  await expect(page.locator(".score-time-signature-preview .bravura-time-signature")).toBeVisible();
  await canvas.click({ position: measurePoint });
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.structure.effectiveTimeSignature.numerator
  ))).toBe(3);
  const beforeMeasures = await page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.structure.lastMeasure
  ));

  await toolbar.getByRole("button", { name: "插入小节" }).click();
  const insertDialog = page.getByRole("dialog", { name: "插入小节" });
  await insertDialog.getByRole("spinbutton", { name: "每个和弦拍数" }).fill("2");
  await insertDialog.getByRole("button", { name: "插入", exact: true }).click();
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.structure.lastMeasure
  ))).toBe(beforeMeasures + 1);
  const insertedSlots = await page.evaluate(() => {
    const update = window.__MECON_E2E__.snapshot().practiceUpdate;
    const end = update.timeline.end.numerator / update.timeline.end.denominator;
    return update.timeline.slots.filter((slot) => {
      const onset = slot.onset.numerator / slot.onset.denominator;
      return onset >= end - 3 / 4;
    }).map((slot) => slot.duration.numerator / slot.duration.denominator);
  });
  expect(insertedSlots).toContain(1 / 2);
});

test("score note selection focuses its filled harmony slot", async ({ page }) => {
  await page.goto("/");
  await openPracticeFixture(page, longFixture, "free-practice-f1-64");
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__?.snapshot()?.practiceUpdate?.revision
  )), { timeout: 60_000 }).toBe(0);

  const distantSlotId = await page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.document.workspace.slots.filter((slot) => slot.chordChoice).at(-1).id);
  const distantSlot = page.locator(`[data-slot-id="${distantSlotId}"]`);
  await distantSlot.getByRole("button").first().click();
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.selection.slotId
  ))).toBe(distantSlotId);

  await clickScoreNote(page, 0);
  await expect.poll(() => page.evaluate(() => {
    const snapshot = window.__MECON_E2E__.snapshot();
    const update = snapshot.practiceUpdate;
    const target = update.selection.scoreTargets.find((item) => item.type === "event");
    if (!target) return false;
    const event = update.score.score.voiceTracks[target.voiceTrackId].events
      .find((item) => item.id === target.eventId);
    const value = (fraction) => Number(fraction.numerator) / Number(fraction.denominator);
    const anchor = snapshot.timeAxis.anchors.find((item) => item.scoreTime.measure === event.onset.measure
      && value(item.scoreTime.beat) === value(event.onset.beat));
    const slot = update.timeline.slots.find((item) => {
      const start = value(item.onset);
      return value(anchor.time) >= start && value(anchor.time) < start + value(item.duration);
    });
    return update.selection.slotId === slot?.id;
  })).toBe(true);
});

test("marquee rewrite and alternate keep the selected score time range", async ({ page }) => {
  await page.goto("/");
  await openPracticeFixture(page, longFixture, "free-practice-f1-64");
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__?.snapshot()?.practiceUpdate?.revision
  )), { timeout: 60_000 }).toBe(0);

  await marqueeFirstTwoScoreOnsets(page);
  await expect.poll(() => page.evaluate(() => new Set(
    window.__MECON_E2E__.snapshot().practiceUpdate.selection.scoreTargets
      .filter((target) => target.type === "event").map((target) => target.eventId),
  ).size)).toBeGreaterThan(1);

  const rewrite = page.locator('[data-control-id="writing.rewrite"] button');
  await expect(rewrite).toBeEnabled();
  await rewrite.click();
  await expect.poll(() => page.evaluate(() => {
    const writing = window.__MECON_E2E__.snapshot().practiceUpdate.writing;
    return writing.lastScope?.length > 1 ? writing.lastScope : null;
  })).not.toBeNull();
  const requestedScope = await page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.writing.lastScope
  ));

  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.writing.phase
  )), { timeout: 60_000 }).toBe("READY");
  const expectedScope = await page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.writing.lastScope
  ));
  expect(expectedScope).toEqual(requestedScope);

  const alternate = page.locator('[data-control-id="writing.alternate"] button');
  await expect(alternate).toBeEnabled({ timeout: 60_000 });
  await alternate.click();
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.writing.lastScope
  ))).toEqual(expectedScope);
});

test("score transport steps its playhead, resumes complete notes, and starts from score selection", async ({ page }) => {
  await page.goto("/");
  await openPracticeFixture(page, longFixture, "free-practice-f1-64");
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });

  await page.getByRole("button", { name: "从头播放" }).click();
  const playhead = page.locator(".score-playhead");
  await expect(playhead).toBeVisible({ timeout: 30_000 });
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.playback().state)).toBe("playing");
  const firstAnchorLeft = await playhead.evaluate((element) => element.style.left);
  await page.waitForTimeout(80);
  await expect.poll(() => playhead.evaluate((element) => element.style.left)).toBe(firstAnchorLeft);

  await page.getByRole("button", { name: "暂停" }).click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.playback().state)).toBe("paused");
  await expect(playhead).toHaveAttribute("data-playback-state", "paused");
  const pausedLeft = await playhead.evaluate((element) => element.style.left);
  await page.waitForTimeout(250);
  await expect.poll(() => playhead.evaluate((element) => element.style.left)).toBe(pausedLeft);

  await page.getByRole("button", { name: "播放", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.playback().state)).toBe("playing");

  const secondSlot = page.locator("[data-slot-id]").nth(1);
  const secondSlotId = await secondSlot.getAttribute("data-slot-id");
  await secondSlot.getByRole("button").first().click();
  await expect.poll(() => page.evaluate(() => {
    const update = window.__MECON_E2E__.snapshot().practiceUpdate;
    return update.selection.slotId ?? update.selectedSlotId;
  })).toBe(secondSlotId);
  await clickScoreNote(page, 0);
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.selection.scoreTargets?.length ?? 0
  ))).toBeGreaterThan(0);
  await page.getByRole("button", { name: "从选择播放" }).click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.playback().state)).toBe("playing");
  const selectionRange = await page.evaluate(() => {
    const snapshot = window.__MECON_E2E__.snapshot();
    const target = snapshot.practiceUpdate.selection.scoreTargets.find((item) => item.type === "event");
    const event = snapshot.practiceUpdate.score.score.voiceTracks[target.voiceTrackId].events
      .find((item) => item.id === target.eventId);
    const value = (fraction) => Number(fraction.numerator) / Number(fraction.denominator);
    const anchor = snapshot.timeAxis.anchors.find((item) => item.scoreTime.measure === event.onset.measure
      && value(item.scoreTime.beat) === value(event.onset.beat));
    const range = window.__MECON_E2E__.playbackRange();
    return { selectedOnset: anchor.time, rangeStart: range.start, rangeEnd: range.end,
      scoreEnd: snapshot.practiceUpdate.timeline.end };
  });
  expect(selectionRange.rangeStart).toEqual(selectionRange.selectedOnset);
  expect(selectionRange.rangeEnd).toEqual(selectionRange.scoreEnd);

  await page.getByRole("button", { name: "暂停" }).click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.playback().state)).toBe("paused");
  const playheadAlignment = await page.evaluate(() => {
    const snapshot = window.__MECON_E2E__.snapshot();
    const target = snapshot.practiceUpdate.selection.scoreTargets.find((item) => item.type === "event");
    const event = snapshot.practiceUpdate.score.score.voiceTracks[target.voiceTrackId].events
      .find((item) => item.id === target.eventId);
    const value = (fraction) => Number(fraction.numerator) / Number(fraction.denominator);
    const position = snapshot.bundle.timePositions.find((item) => item.timeCode.measure === event.onset.measure
      && value(item.timeCode.beat) === value(event.onset.beat));
    const originX = Number(snapshot.bundle.bounds.origin.x?.value ?? snapshot.bundle.bounds.origin.x ?? 0);
    return {
      expected: Number(position.x) - originX,
      actual: Number.parseFloat(document.querySelector(".score-playhead").style.left),
    };
  });
  expect(playheadAlignment.actual).toBeCloseTo(playheadAlignment.expected, 3);

  await page.getByRole("button", { name: "音频设置" }).click();
  await expect(playhead).toHaveCount(0);
});

test("pausing just after a new note keeps that note's playhead anchor", async ({ page }) => {
  const debugLines = [];
  page.on("console", (message) => {
    if (message.type() === "debug" && message.text().includes("[mecon-playback]")) {
      debugLines.push(message.text());
    }
  });
  await page.goto("/");
  await openPracticeFixture(page, longFixture, "free-practice-f1-64");
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });
  await page.getByRole("button", { name: "从头播放" }).click();

  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.playbackTrace()
    .some((entry) => entry.kind === "schedule" && entry.noteStarts.length >= 2))).toBe(true);
  await page.evaluate(() => new Promise((resolvePause, rejectPause) => {
    const schedule = window.__MECON_E2E__.playbackTrace().find((entry) => entry.kind === "schedule");
    const secondNoteTick = schedule.startTick + schedule.noteStarts[1] / schedule.secondsPerTick;
    const targetTick = secondNoteTick + 0.03 / schedule.secondsPerTick;
    const deadline = performance.now() + 5_000;
    const pauseAtTarget = () => {
      if (window.__MECON_E2E__.playback().tick >= targetTick) {
        document.querySelector('button[aria-label="暂停"]').click();
        resolvePause();
      } else if (performance.now() >= deadline) {
        rejectPause(new Error(`cursor never reached ${targetTick}`));
      } else requestAnimationFrame(pauseAtTarget);
    };
    requestAnimationFrame(pauseAtTarget);
  }));
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.playback().state)).toBe("paused");
  const diagnosis = await page.evaluate(() => {
    const trace = window.__MECON_E2E__.playbackTrace();
    const pause = trace.findLast((entry) => entry.kind === "pause");
    const cursor = trace.filter((entry) => entry.kind === "cursor-anchor" && entry.atMs <= pause.atMs).at(-1);
    const schedule = trace.find((entry) => entry.kind === "schedule");
    const secondNoteTick = schedule.startTick + schedule.noteStarts[1] / schedule.secondsPerTick;
    return { pause, cursor, secondNoteTick };
  });
  expect(diagnosis.pause.visibleAnchorTick).toBe(diagnosis.cursor.anchorTick);
  expect(diagnosis.pause.pausedTick).toBeGreaterThanOrEqual(diagnosis.secondNoteTick);
  expect(diagnosis.pause.resumeTick).toBeCloseTo(diagnosis.secondNoteTick, 6);
  expect(debugLines.some((line) => line.includes("schedule"))).toBe(true);
  expect(debugLines.some((line) => line.includes("cursor-anchor"))).toBe(true);
  expect(debugLines.some((line) => line.includes("pause"))).toBe(true);
});

test("harmony timeline commits keyboard and pointer edits through the shared session", async ({ page }) => {
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });
  await expect(page.getByLabel("和声时间轴")).toHaveAttribute("data-axis-source", "renderer");
  await page.getByLabel("吸附单位").selectOption("16");

  const slot = page.locator('[data-slot-id="slot-0"]');
  await slot.locator("button").first().press("ArrowRight");
  await expect(page.getByRole("status")).not.toHaveText("revision 0", { timeout: 30_000 });
  // Moving a slot may start automatic writing. The intent queue deliberately holds settings while
  // that session-owned job is RUNNING, so wait on the authoritative phase rather than panel text.
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.writing.phase), { timeout: 60_000 }).not.toBe("RUNNING");
  const autoWriting = page.getByRole("button", { name: "自动写作", exact: true });
  await expect(autoWriting).toHaveAttribute("aria-pressed", "true");
  await autoWriting.click();
  await expect.poll(() => page.evaluate(() => ({
    autoWritingEnabled: window.__MECON_E2E__.snapshot()
      .practiceUpdate.document.settings.writing.autoWritingEnabled,
    ...window.__MECON_E2E__.intentQueue(),
  }))).toEqual({ autoWritingEnabled: false, inFlightRequestId: null, pendingCount: 0 });
  const revisionBeforeResize = await page.evaluate(() => window.__MECON_E2E__.snapshot().practiceUpdate.revision);
  const durationBeforeResize = await page.evaluate(() => {
    const duration = window.__MECON_E2E__.snapshot().practiceUpdate.timeline.slots[0].duration;
    return duration.numerator / duration.denominator;
  });

  const handle = slot.getByRole("button", { name: /调整.*终点/ });
  await handle.scrollIntoViewIfNeeded();
  const box = await handle.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  // Shrink the only slot so the following append has deterministic free timeline space.
  await page.mouse.move(box.x - 36, box.y + box.height / 2, { steps: 4 });
  await page.mouse.up();
  await expect.poll(
    () => page.evaluate(() => window.__MECON_E2E__.snapshot().practiceUpdate.revision),
    { timeout: 30_000 },
  ).toBeGreaterThan(revisionBeforeResize);
  await expect.poll(() => page.evaluate(() => {
    const duration = window.__MECON_E2E__.snapshot().practiceUpdate.timeline.slots[0].duration;
    return duration.numerator / duration.denominator;
  })).toBeCloseTo(durationBeforeResize - 1 / 16, 8);

  const rangeBeforeLeftResize = await page.evaluate(() => {
    const value = (fraction) => fraction.numerator / fraction.denominator;
    const first = window.__MECON_E2E__.snapshot().practiceUpdate.timeline.slots[0];
    return { onset: value(first.onset), duration: value(first.duration) };
  });
  const revisionBeforeLeftResize = await page.evaluate(
    () => window.__MECON_E2E__.snapshot().practiceUpdate.revision,
  );
  const leftHandle = slot.getByRole("button", { name: /调整.*起点/ });
  await leftHandle.scrollIntoViewIfNeeded();
  const leftBox = await leftHandle.boundingBox();
  await page.mouse.move(leftBox.x + leftBox.width / 2, leftBox.y + leftBox.height / 2);
  await page.mouse.down();
  await page.mouse.move(leftBox.x + 36, leftBox.y + leftBox.height / 2, { steps: 4 });
  await page.mouse.up();
  await expect.poll(
    () => page.evaluate(() => window.__MECON_E2E__.snapshot().practiceUpdate.revision),
    { timeout: 30_000 },
  ).toBeGreaterThan(revisionBeforeLeftResize);
  await expect.poll(() => page.evaluate(() => {
    const value = (fraction) => fraction.numerator / fraction.denominator;
    const first = window.__MECON_E2E__.snapshot().practiceUpdate.timeline.slots[0];
    return { onset: value(first.onset), duration: value(first.duration) };
  })).toEqual({
    onset: rangeBeforeLeftResize.onset + 1 / 16,
    duration: rangeBeforeLeftResize.duration - 1 / 16,
  });

  await page.getByRole("button", { name: "追加和弦槽" }).click();
  await expect(page.locator(".timeline-slot-control")).toHaveCount(2, { timeout: 30_000 });
  await page.getByRole("button", { name: "删除当前和弦槽" }).click();
  await expect(page.locator(".timeline-slot-control")).toHaveCount(1, { timeout: 30_000 });
  const committedRange = await page.evaluate(() => {
    const slot = window.__MECON_E2E__.snapshot().practiceUpdate.timeline.slots[0];
    return { onset: slot.onset, duration: slot.duration };
  });
  await page.getByRole("button", { name: "撤销" }).click();
  await expect(page.locator(".timeline-slot-control")).toHaveCount(2, { timeout: 30_000 });
  await page.getByRole("button", { name: "重做" }).click();
  await expect(page.locator(".timeline-slot-control")).toHaveCount(1, { timeout: 30_000 });
  await page.reload();
  await expect(page.getByLabel("和声时间轴")).toBeVisible({ timeout: 30_000 });
  await expect.poll(() => page.evaluate(() => {
    const slot = window.__MECON_E2E__.snapshot()?.practiceUpdate?.timeline?.slots?.[0];
    return slot ? { onset: slot.onset, duration: slot.duration } : null;
  })).toEqual(committedRange);
});

test("harmony timeline Ctrl-drag creates one undoable history item without a stale alert", async ({ page }) => {
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });
  await page.getByLabel("吸附单位").selectOption("16");

  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.document.settings.writing.autoWritingEnabled)).toBe(true);
  await page.getByRole("button", { name: "追加和弦槽" }).click();
  await expect(page.locator(".timeline-slot-control")).toHaveCount(2, { timeout: 30_000 });

  const before = await page.evaluate(() => {
    const snapshot = window.__MECON_E2E__.snapshot();
    const value = (fraction) => fraction.numerator / fraction.denominator;
    return {
      revision: snapshot.practiceUpdate.revision,
      ranges: snapshot.practiceUpdate.timeline.slots.map((slot) => ({
        onset: value(slot.onset), duration: value(slot.duration),
      })),
      traceLength: window.__MECON_E2E__.timelineTrace().length,
    };
  });
  const body = page.locator('[data-slot-id="slot-0"] > button').first();
  const box = await body.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.keyboard.down("Control");
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 36, box.y + box.height / 2, { steps: 6 });
  await page.mouse.up();
  await page.keyboard.up("Control");

  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.revision), { timeout: 30_000 }).toBeGreaterThan(before.revision);
  await expect.poll(() => page.evaluate(() => {
    const value = (fraction) => fraction.numerator / fraction.denominator;
    return window.__MECON_E2E__.snapshot().practiceUpdate.timeline.slots
      .map((slot) => value(slot.onset));
  })).toEqual(before.ranges.map(({ onset }) => onset + 1 / 16));
  await expect(page.getByRole("button", { name: "撤销" })).toBeEnabled();
  await page.keyboard.press("Control+z");
  await expect.poll(() => page.evaluate(() => {
    const value = (fraction) => fraction.numerator / fraction.denominator;
    return window.__MECON_E2E__.snapshot().practiceUpdate.timeline.slots.map((slot) => ({
      onset: value(slot.onset), duration: value(slot.duration),
    }));
  })).toEqual(before.ranges);
  expect(await page.evaluate((traceLength) => window.__MECON_E2E__.timelineTrace()
    .slice(traceLength).some(({ result }) => result?.reasonKey === "stale_scene" && !result.ignored),
  before.traceLength)).toBe(false);
});

test("harmony timeline replays the shared hover cursor and highlight for real pointer moves", async ({ page }) => {
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });

  const surface = page.locator(".harmony-timeline");
  const cursor = () => surface.evaluate((element) => getComputedStyle(element).cursor);
  const slot = page.locator('[data-slot-id="slot-0"]');

  const body = await slot.locator("button").first().boundingBox();
  await page.mouse.move(body.x + body.width / 2, body.y + body.height / 2);
  await expect(surface).toHaveAttribute("data-hover-target", "slot:slot-0");
  await expect(surface.locator('[data-object-id="slot:slot-0:hover"]')).toHaveCount(1);
  expect(await cursor()).toBe("grab");

  const handle = slot.getByRole("button", { name: /调整.*终点/ });
  const handleBox = await handle.boundingBox();
  await page.mouse.move(handleBox.x + handleBox.width / 2, handleBox.y + handleBox.height / 2);
  await expect(surface).toHaveAttribute("data-hover-target", "slot:slot-0:end");
  await expect(surface.locator('[data-object-id="slot:slot-0:end:hover"]')).toHaveCount(1);
  expect(await cursor()).toBe("ew-resize");

  // Leaving the surface must drop both the highlight and the cursor claim.
  await page.mouse.move(handleBox.x + handleBox.width / 2, handleBox.y - 200);
  await expect(surface).not.toHaveAttribute("data-hover-target", /.*/);
  await expect(surface.locator('[data-object-id="slot:slot-0:end:hover"]')).toHaveCount(0);
  expect(await cursor()).toBe("default");
});

test("harmony timeline switches between full and compact shared scenes without editing the practice", async ({ page }) => {
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  const revision = page.locator(".practice-revision-announcer");
  await expect(revision).toHaveText("revision 0", { timeout: 30_000 });

  const timeline = page.getByLabel("和声时间轴");
  const surface = page.locator(".harmony-timeline");
  const modeSwitch = page.getByRole("switch", { name: "时间轴显示模式" });
  await expect(timeline).toHaveAttribute("data-display-mode", "full");
  await expect(page.locator(".timeline-key-labels")).not.toHaveCount(0);
  await expect(timeline.locator(".harmony-timeline-mode")).toContainText("完整精简");
  const fullHeight = (await surface.boundingBox()).height;

  const timelineScroll = page.locator(".harmony-timeline-scroll");
  await timelineScroll.evaluate((element) => { element.style.width = "220px"; });
  const appendControl = page.getByRole("button", { name: "追加和弦槽" }).locator("..");
  const appendLeft = await appendControl.evaluate((element) => element.style.left);
  await timelineScroll.evaluate((element) => {
    element.scrollLeft = 70;
    element.dispatchEvent(new Event("scroll"));
  });
  await expect.poll(() => appendControl.evaluate((element) => element.style.left)).toBe(appendLeft);
  const switchBox = await modeSwitch.boundingBox();
  const firstSlotBox = await page.locator('[data-slot-id="slot-0"]').boundingBox();
  expect(firstSlotBox.x).toBeGreaterThanOrEqual(switchBox.x + switchBox.width);

  await expect(modeSwitch).toHaveAttribute("aria-checked", "false");
  await modeSwitch.click();
  await expect(timeline).toHaveAttribute("data-display-mode", "compact");
  await expect(modeSwitch).toHaveAttribute("aria-checked", "true");
  await expect(page.locator(".timeline-key-labels")).toHaveCount(0);
  await expect.poll(async () => (await surface.boundingBox()).height).toBeLessThan(fullHeight);
  await expect(revision).toHaveText("revision 0");

  await modeSwitch.click();
  await expect(timeline).toHaveAttribute("data-display-mode", "full");
  await expect(modeSwitch).toHaveAttribute("aria-checked", "false");
  await expect(page.locator(".timeline-key-labels")).not.toHaveCount(0);
  await expect(revision).toHaveText("revision 0");
});

test("tonal layouts stay session-owned and narrow workbench tabs preserve the mounted views", async ({ page }) => {
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  const expectRevision = (revision) => expect.poll(
    () => page.evaluate(() => window.__MECON_E2E__?.snapshot()?.practiceUpdate?.revision),
    { timeout: 30_000 },
  ).toBe(revision);
  await expectRevision(0);

  await page.getByRole("button", { name: "+ 插入", exact: true }).click();
  await page.getByRole("dialog", { name: "在当前和弦插入调性线" })
    .getByRole("button", { name: "F#", exact: true }).first().click();
  await expectRevision(1);

  const initialCatalogTonic = await page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.plan.chordCatalogGroups[0].choices[0].choice.pitchClasses);
  const layoutLabels = page.locator(".timeline-key-labels button");
  await layoutLabels.first().click();
  await expectRevision(2);
  await layoutLabels.last().click();
  await expectRevision(3);
  const catalogTonality = page.getByRole("group", { name: "按哪个调选和弦" });
  await expect(catalogTonality.getByRole("button", { name: "C", exact: true }))
    .toHaveAttribute("aria-pressed", "true");
  await catalogTonality.getByRole("button", { name: "F#", exact: true }).click();
  await expectRevision(4);
  const idiomTonality = page.getByRole("group", { name: "按哪个调选惯用进行" });
  await idiomTonality.getByRole("button", { name: "C", exact: true }).click();
  await expectRevision(5);
  await expect(catalogTonality.getByRole("button", { name: "F#", exact: true }))
    .toHaveAttribute("aria-pressed", "true");
  await idiomTonality.getByRole("button", { name: "F#", exact: true }).click();
  await expectRevision(6);
  await expect(catalogTonality.getByRole("button", { name: "F#", exact: true }))
    .toHaveAttribute("aria-pressed", "true");
  await expect.poll(() => page.evaluate(() => ({
    key: window.__MECON_E2E__.snapshot().practiceUpdate.plan.currentKey,
    tonic: window.__MECON_E2E__.snapshot()
      .practiceUpdate.plan.chordCatalogFilters.find((filter) => filter.selected)
      .chordGroups[0].choices[0].choice.pitchClasses,
  }))).toEqual({
    key: { fifths: 6, mode: "MAJOR" },
    tonic: expect.not.arrayContaining(initialCatalogTonic),
  });
  await expect(page.getByText(/另 1 调：/).first()).toBeVisible();
  const toneCountFilter = page.getByRole("group", { name: "组成音个数" });
  await expect(toneCountFilter.getByRole("button", { name: "任意", exact: true }))
    .toHaveAttribute("aria-pressed", "true");
  await toneCountFilter.getByRole("button", { name: "4音", exact: true }).click();
  await expect(toneCountFilter.getByRole("button", { name: "4音", exact: true }))
    .toHaveAttribute("aria-pressed", "true");
  const visibleToneCounts = await page.locator(".chord-catalog-group [data-choice-id]:visible")
    .evaluateAll((buttons) => buttons.map((button) => button.dataset.choiceId));
  const fourToneChoiceIds = await page.evaluate(() => {
    const selected = window.__MECON_E2E__.snapshot().practiceUpdate.plan.chordCatalogFilters
      .find((filter) => filter.selected);
    return selected.toneCountFilters.find((filter) => filter.toneCount === 4).chordGroups
      .flatMap((group) => group.choices).map((choice) => choice.id);
  });
  expect(visibleToneCounts.every((id) => fourToneChoiceIds.includes(id))).toBe(true);
  await toneCountFilter.getByRole("button", { name: "任意", exact: true }).click();
  const autoWriting = page.getByRole("button", { name: "自动写作", exact: true });
  await autoWriting.click();
  await expectRevision(7);
  await expect(autoWriting).toHaveAttribute("aria-pressed", "false");
  const replacementChoice = await page.evaluate(() => {
    const plan = window.__MECON_E2E__.snapshot().practiceUpdate.plan;
    const selectedPitchClasses = [...plan.selectedSlot.pitchClasses].sort((a, b) => a - b).join(",");
    return plan.chordCatalogFilters.find((filter) => filter.selected).chordGroups
      .flatMap((group) => group.choices)
      .find((choice) => [...choice.choice.pitchClasses].sort((a, b) => a - b).join(",") !== selectedPitchClasses);
  });
  await page.locator(`[data-choice-id="${replacementChoice.id}"]`).click();
  await expectRevision(8);
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.plan.selectedSlot.pitchClasses)).toEqual(replacementChoice.choice.pitchClasses);

  const layoutEndHandle = page.getByRole("button", { name: "调整调性线终点" }).last();
  const layoutEndBox = await layoutEndHandle.boundingBox();
  await page.mouse.move(layoutEndBox.x + layoutEndBox.width / 2, layoutEndBox.y + layoutEndBox.height / 2);
  await page.mouse.down();
  await page.mouse.move(layoutEndBox.x + 96, layoutEndBox.y + layoutEndBox.height / 2, { steps: 6 });
  await page.mouse.up();
  await expectRevision(9);

  const tonalLayouts = page.locator(".tonal-layout-row > button:first-child");
  await expect(tonalLayouts).toHaveCount(2);
  await expect(tonalLayouts.last()).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("spinbutton", { name: "上谱声部", exact: true }).fill("1");
  await expectRevision(10);
  await page.getByRole("button", { name: /删除调性线/ }).click();
  await expectRevision(11);

  await page.locator(".harmony-timeline-scroll").evaluate((element) => { element.style.width = "400px"; });
  await page.locator(".harmony-timeline").evaluate((element) => { element.style.minWidth = "1200px"; });
  await page.locator(".canvas-scroll").evaluate((element) => {
    element.style.width = "400px";
    element.querySelector("canvas").style.minWidth = "1200px";
    element.scrollLeft = 80;
    element.dispatchEvent(new Event("scroll"));
  });
  await expect.poll(() => page.locator(".harmony-timeline-scroll").evaluate((element) => element.scrollLeft))
    .toBeGreaterThan(0);
  await page.setViewportSize({ width: 600, height: 900 });
  const tabs = page.getByRole("tablist", { name: "自由练习视图" });
  await expect(tabs).toBeVisible();
  await expect(page.getByLabel("五线谱编辑区")).toBeVisible();
  const scoreTab = tabs.getByRole("tab", { name: "五线谱" });
  await expect(scoreTab).toHaveAttribute("aria-selected", "true");
  await scoreTab.press("ArrowLeft");
  await expect(tabs.getByRole("tab", { name: "时间轴" })).toBeFocused();
  await expect(page.getByLabel("和声时间轴")).toBeVisible();
  await tabs.getByRole("tab", { name: "计划" }).click();
  await expect(page.getByRole("tabpanel", { name: "计划" }).getByLabel("自由练习计划")).toBeVisible();
  await tabs.getByRole("tab", { name: "反馈" }).click();
  await expect(page.getByRole("tabpanel", { name: "反馈" }).getByLabel("自由练习反馈")).toBeVisible();
});

test("idiom tonality selection stays independent and insertion keeps the later tonal layout", async ({ page }) => {
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  const revision = () => page.evaluate(() => window.__MECON_E2E__?.snapshot()?.practiceUpdate?.revision);
  await expect.poll(revision, { timeout: 30_000 }).toBe(0);

  await page.getByRole("button", { name: "+ 插入", exact: true }).click();
  await page.getByRole("dialog", { name: "在当前和弦插入调性线" })
    .getByRole("button", { name: "F#", exact: true }).first().click();
  await expect.poll(revision).toBe(1);

  const chordTonality = page.getByRole("group", { name: "按哪个调选和弦" });
  const idiomTonality = page.getByRole("group", { name: "按哪个调选惯用进行" });
  await chordTonality.getByRole("button", { name: "F#", exact: true }).click();
  await expect.poll(revision).toBe(2);
  await idiomTonality.getByRole("button", { name: "C", exact: true }).click();
  await expect.poll(revision).toBe(3);
  await idiomTonality.getByRole("button", { name: "F#", exact: true }).click();
  await expect.poll(revision).toBe(4);
  await expect(chordTonality.getByRole("button", { name: "F#", exact: true }))
    .toHaveAttribute("aria-pressed", "true");

  const autoWriting = page.getByRole("button", { name: "自动写作", exact: true });
  await autoWriting.click();
  await expect.poll(revision).toBe(5);
  await expect(autoWriting).toHaveAttribute("aria-pressed", "false");
  await page.getByRole("button", { name: "全部教材进行", exact: true }).click();
  const variant = page.getByTestId("idiom-catalog").locator("button:not([disabled])").first();
  await expect(variant).toBeVisible({ timeout: 60_000 });
  await variant.click();
  await expect.poll(revision, { timeout: 30_000 }).toBe(6);

  await expect.poll(() => page.evaluate(() => {
    const update = window.__MECON_E2E__.snapshot().practiceUpdate;
    const laterLayout = update.document.workspace.tonalLayouts.find((layout) => layout.fifths === 6);
    return {
      selectionLayoutId: update.selection.tonalLayoutId,
      idiomLayoutId: update.document.workspace.idiomInstances[0]?.tonalLayoutId,
      laterLayoutId: laterLayout?.id,
    };
  })).toEqual({
    selectionLayoutId: expect.any(String),
    idiomLayoutId: expect.any(String),
    laterLayoutId: expect.any(String),
  });
  const ids = await page.evaluate(() => {
    const update = window.__MECON_E2E__.snapshot().practiceUpdate;
    const laterLayoutId = update.document.workspace.tonalLayouts.find((layout) => layout.fifths === 6).id;
    return [update.selection.tonalLayoutId, update.document.workspace.idiomInstances[0].tonalLayoutId, laterLayoutId];
  });
  expect(ids).toEqual([ids[2], ids[2], ids[2]]);
});

test("narrow workbench exposes a coherent assistive-technology tree and score summary", async ({ page }) => {
  await page.setViewportSize({ width: 600, height: 900 });
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });

  const tabs = page.getByRole("tablist", { name: "自由练习视图" });
  await expect(tabs.getByRole("tab")).toHaveCount(4);
  await expect(tabs.getByRole("tab", { name: "五线谱" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("tabpanel", { name: "五线谱" })).toBeVisible();
  await expect(page.getByRole("tabpanel", { name: "时间轴" })).toBeHidden();

  const scoreCanvas = page.getByRole("img", { name: "可编辑五线谱" });
  await expect(scoreCanvas).toBeVisible();
  await expect(scoreCanvas).toHaveAccessibleDescription(/小节.*谱表.*记谱事件.*当前选择/);
  await scoreCanvas.focus();
  await expect(scoreCanvas).toBeFocused();

  await tabs.getByRole("tab", { name: "五线谱" }).focus();
  await page.keyboard.press("End");
  await expect(tabs.getByRole("tab", { name: "反馈" })).toBeFocused();
  await expect(page.getByRole("tabpanel", { name: "反馈" })).toBeVisible();
  await expect(page.getByLabel("自由练习反馈").locator(':scope > div[aria-live="polite"]')).toBeVisible();

  const accessibilityTree = await page.locator("main").ariaSnapshot();
  expect(accessibilityTree).toContain("tablist \"自由练习视图\"");
  expect(accessibilityTree).toContain("tabpanel \"反馈\"");
  expect(accessibilityTree).not.toContain("tabpanel \"五线谱\"");
});

test("teaching idiom catalog renders kernel data and dispatches stable-id insertion", async ({ page }) => {
  await page.addInitScript(() => { window.showSaveFilePicker = undefined; });
  await page.goto("/");
  await openPracticeFixture(page, fixture, "free-practice-f1");
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });

  const idiomCatalog = page.getByTestId("idiom-catalog");
  await page.getByRole("button", { name: "全部教材进行", exact: true }).click();
  await expect(idiomCatalog.locator("button").first()).toBeVisible({ timeout: 60_000 });
  const defaultVariantCount = await idiomCatalog.locator("button").count();
  const catalogGeneration = await idiomCatalog.getAttribute("data-generation");
  const offKeyToggle = page.getByRole("checkbox", { name: "展示离调进行" });
  await offKeyToggle.click();
  await expect(offKeyToggle).toBeChecked({ timeout: 30_000 });
  await expect(idiomCatalog).not.toHaveAttribute("data-generation", catalogGeneration, { timeout: 60_000 });
  await expect(idiomCatalog.locator("button")).toHaveCount(defaultVariantCount);
  const collapsedCadence = await page.evaluate(() => {
    const definition = window.__MECON_E2E__.snapshot().practiceUpdate.plan.idiomCatalog.definitions
      .find((item) => item.id === "schoenberg.cadence.complete-authentic");
    return {
      listed: definition.choices.filter((choice) => choice.availableByDefault).map((choice) => choice.title),
      concreteCount: definition.variants.filter((variant) => variant.availableByDefault).length,
    };
  });
  expect(collapsedCadence.listed).toEqual(["ii – V – I"]);
  expect(collapsedCadence.concreteCount).toBeGreaterThan(1);
  const firstVariant = idiomCatalog.locator("button:not([disabled])").first();
  await expect(firstVariant).toBeEnabled();
  await firstVariant.click();
  await expect(page.getByRole("status")).not.toHaveText("revision 0", { timeout: 30_000 });
  await expect(page.locator(".practice-idiom-list li")).toHaveCount(1, { timeout: 30_000 });
  await expect(page.locator(".timeline-idiom-range")).toHaveCount(1);
  await expect(page.locator(".timeline-slot-control.locked")).not.toHaveCount(0);
  await expect(page.locator(".timeline-slot-control.locked .timeline-start-handle")).toHaveCount(0);
  const form = await page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.plan.selectedIdiomForm);
  expect(form.steps.length).toBeGreaterThan(0);
  const firstStep = form.steps[0];
  const alternate = firstStep.options.find((option) => !option.selected);
  const revisionBeforeFormChange = await page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.revision);
  const alternateFormButton = page.getByRole("group", { name: `${firstStep.chordLabel} 调整和弦形态` })
    .getByRole("button", { name: alternate.label, exact: true });
  await expect(alternateFormButton).toBeEnabled({ timeout: 30_000 });
  await alternateFormButton.click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.revision), { timeout: 30_000 }).toBeGreaterThan(revisionBeforeFormChange);

  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("button", { name: "保存" }).click(),
  ]);
  const exportPath = resolve(here, "../../../build/e2e/browser-free-practice-export.mecon");
  await download.saveAs(exportPath);
  const reopened = await loadMeconDocument(new Uint8Array(await readFile(exportPath)));
  expect(reopened.modules.get("free-practice").payload.workspace.idiomInstances).toHaveLength(1);
  expect(reopened.scores.get("free-practice-sibling").metadata.title).toBe("Free writing sibling");
  expect(reopened.modules.get("future-practice").payload).toEqual({
    futureField: "free-practice-preserved",
    nested: { version: 17 },
  });
  expect(reopened.manifest.workspace).toEqual({
    activeModuleId: "free-practice",
    selectedScoreIds: [reopened.manifest.activeScoreId],
  });
  await page.getByRole("button", { name: "删除惯用进行" }).click();
  await expect(page.locator(".timeline-idiom-range")).toHaveCount(0, { timeout: 30_000 });
  await expect(page.locator(".practice-idiom-list li")).toHaveCount(0);
});

test("off-key German-sixth form changes keep their target key and tail navigation", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");
  await expect(page.locator(".practice-revision-announcer")).toHaveText("revision 0", {
    timeout: 30_000,
  });

  await page.getByRole("button", { name: "在末尾添加和弦" }).click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.timeline.slots.length)).toBe(2);
  await page.getByRole("button", { name: /^V7 ·/ }).click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.writing.phase), { timeout: 60_000 }).toBe("READY");

  const offKeyToggle = page.getByRole("checkbox", { name: "展示离调进行" });
  await offKeyToggle.click();
  await expect(offKeyToggle).toBeChecked({ timeout: 30_000 });
  const idiomCatalog = page.getByTestId("idiom-catalog");
  const gerToV = idiomCatalog.getByRole("button", { name: /^Ger\+6 – V(?: ·|$)/ }).first();
  await expect(gerToV).toBeEnabled({ timeout: 60_000 });
  await gerToV.click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.document.workspace.idiomInstances.length), { timeout: 30_000 }).toBe(1);
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.writing.phase), { timeout: 60_000 }).toBe("READY");

  const beforeFormChange = await page.evaluate(() => {
    const update = window.__MECON_E2E__.snapshot().practiceUpdate;
    const instance = update.document.workspace.idiomInstances[0];
    const definition = update.plan.idiomCatalog.definitions
      .find((item) => item.id === instance.definitionId);
    const variant = definition.variants.find((item) => item.id === instance.variantId);
    const step = update.plan.selectedIdiomForm.steps
      .find((item) => item.options.some((option) => option.toneCount === 4 && !option.selected));
    return {
      revision: update.revision,
      tonalLayoutId: instance.tonalLayoutId,
      suggestedKey: variant.suggestedKey,
      stepIndex: step.stepIndex,
      chordLabel: step.chordLabel,
    };
  });
  await page.getByRole("group", {
    name: `${beforeFormChange.chordLabel} 调整和弦形态`,
  }).getByRole("button", { name: "七和弦", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.revision), { timeout: 30_000 }).toBeGreaterThan(beforeFormChange.revision);
  await expect.poll(() => page.evaluate((expected) => {
    const update = window.__MECON_E2E__.snapshot().practiceUpdate;
    const instance = update.document.workspace.idiomInstances[0];
    const definition = update.plan.idiomCatalog.definitions
      .find((item) => item.id === instance.definitionId);
    const variant = definition.variants.find((item) => item.id === instance.variantId);
    return {
      tonalLayoutId: instance.tonalLayoutId,
      suggestedKey: variant.suggestedKey,
      toneCount: variant.chordToneCounts[expected.stepIndex],
      title: variant.title,
    };
  }, beforeFormChange), { timeout: 60_000 }).toEqual({
    tonalLayoutId: beforeFormChange.tonalLayoutId,
    suggestedKey: beforeFormChange.suggestedKey,
    toneCount: 4,
    title: expect.stringMatching(/^Ger\+6 – V7/),
  });
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.writing.phase), { timeout: 60_000 }).toBe("READY");

  await page.getByRole("button", { name: "末尾和弦" }).click();
  await expect.poll(() => page.evaluate(() => {
    const update = window.__MECON_E2E__.snapshot().practiceUpdate;
    return update.selection.slotId === update.timeline.slots.at(-1)?.id;
  })).toBe(true);

  // Selecting another chord reprojects definitions without necessarily changing the background
  // catalog generation. The rendered memo must follow those authoritative definitions.
  await expect.poll(() => page.evaluate(() => {
    const plan = window.__MECON_E2E__.snapshot().practiceUpdate.plan;
    const expected = plan.idiomCatalog.definitions.flatMap((definition) => definition.choices
      .filter((choice) => choice.relatedToFocus)
      .map((choice) => choice.displayLabel));
    const rendered = [...document.querySelectorAll('[data-testid="idiom-catalog"] button')]
      .map((button) => button.textContent);
    return {
      loading: plan.idiomCatalog.loading,
      hasCandidates: expected.length > 0,
      matchesProjection: JSON.stringify(rendered) === JSON.stringify(expected),
    };
  }), { timeout: 60_000 }).toEqual({
    loading: false,
    hasCandidates: true,
    matchesProjection: true,
  });

  const continuation = idiomCatalog.getByRole("button", { name: /^V7 – I(?: ·|$)/ }).first();
  await expect(continuation).toBeEnabled();
  await continuation.click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.document.workspace.idiomInstances.length), { timeout: 30_000 }).toBe(2);
});
