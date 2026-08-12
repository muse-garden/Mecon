import { expect, test } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { loadMeconDocument } from "../../../packages/frozen-score/index.js";

const here = dirname(fileURLToPath(import.meta.url));
const fixture = resolve(here, "../../../build/e2e/free-practice-f1.mecon");
const longFixture = resolve(here, "../../../build/e2e/free-practice-f1-64.mecon");

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

test("marks a selected note and enables shared harmonic-role filters", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");
  await page.getByLabel("打开 .mecon").setInputFiles(longFixture);
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
  await page.getByLabel("筛选和弦").click();
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.noteConstraints.chordCatalogFilterEnabled
  ))).toBe(true);
  await page.getByLabel("筛选惯用进行").click();
  await expect.poll(() => page.evaluate(() => (
    window.__MECON_E2E__.snapshot().practiceUpdate.noteConstraints
  ))).toMatchObject({ chordCatalogFilterEnabled: true, idiomCatalogFilterEnabled: true });
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
        const eventTime = frame.playbackAnchors.find((anchor) => sameTime(anchor.scoreTime, event.onset))?.time;
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
  await page.getByRole("button", { name: "新建自由练习" }).click();
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
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
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
    .filter((group) => group.id !== "mode")
    .flatMap((group) => group.controls)
    .filter((id) => id !== "playback.speed" && id !== "playback.audio-settings"));
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
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
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
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot().practiceUpdate.revision)).toBe(0);
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.hasUnsavedChanges())).toBe(false);
});

test("top toolbar uses desktop-style controls and wraps without scrollbars", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
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

test("wide right panel matches the desktop sections, avoids toolbar duplicates, and resizes", async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 });
  await page.goto("/");
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });

  const panel = page.locator(".workbench-side");
  await expect(panel.getByRole("heading", { name: "当前调性" })).toBeVisible();
  await expect(panel.getByRole("heading", { name: "和声选择" })).toBeVisible();
  await expect(panel.getByRole("heading", { name: "和弦详情" })).toBeVisible();
  await expect(panel.getByRole("heading", { name: "惯用进行" })).toBeVisible();
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
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });

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
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
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
  await page.getByLabel("打开 .mecon").setInputFiles(longFixture);
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
  expect(alignment.semanticWidth).toBeCloseTo(alignment.surfaceWidth, 0);
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
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });
  await expect(page.getByLabel("和声时间轴")).toBeVisible();

  const catalog = page.getByLabel("和弦目录");
  await expect(catalog.locator("option")).not.toHaveCount(0);
  await catalog.selectOption({ index: 1 });
  await page.getByRole("button", { name: "选用和弦" }).click();

  await expect.poll(async () => page.getByLabel("自由练习反馈").textContent(), { timeout: 60_000 })
    .toContain("结果：solved");
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.playbackTrace()
    .some((entry) => entry.kind === "edit-playback" && entry.type === "excerpt")))
    .toBe(true);
  await expect(page.locator(".score-playhead")).toHaveCount(0);
  await expect(page.getByText("检查", { exact: true })).toBeVisible();

  const alternate = page.getByRole("button", { name: "换结果" });
  await expect(alternate).toBeEnabled({ timeout: 60_000 });
  await alternate.click();
  await expect(page.getByRole("status")).toContainText("revision");

  await page.getByRole("button", { name: "试听" }).click();
  await page.getByRole("button", { name: "停止" }).click();

  await page.reload();
  await expect(page.getByLabel("和声时间轴")).toBeVisible({ timeout: 30_000 });
});

test("score note selection consumes shared edit audition playback", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("打开 .mecon").setInputFiles(longFixture);
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });

  await clickScoreNote(page, 0);
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.playbackTrace()
    .some((entry) => entry.kind === "edit-playback" && entry.type === "audition")))
    .toBe(true);
  await expect(page.locator(".score-playhead")).toHaveCount(0);
});

test("marquee rewrite and alternate keep the selected score time range", async ({ page }) => {
  await page.goto("/");
  await page.locator('input[type="file"]').setInputFiles(longFixture);
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
  await page.getByLabel("打开 .mecon").setInputFiles(longFixture);
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
  await page.getByLabel("打开 .mecon").setInputFiles(longFixture);
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
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });
  await expect(page.getByLabel("和声时间轴")).toHaveAttribute("data-axis-source", "renderer");
  await page.getByLabel("吸附单位").selectOption("16");

  const slot = page.locator('[data-slot-id="slot-0"]');
  await slot.locator("button").first().press("ArrowRight");
  await expect(page.getByRole("status")).not.toHaveText("revision 0", { timeout: 30_000 });
  await expect(page.getByLabel("自由练习反馈")).toContainText("READY", { timeout: 60_000 });
  // Moving a slot may start automatic writing. The intent queue deliberately holds settings while
  // that session-owned job is RUNNING, so wait on the authoritative phase rather than panel text.
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.writing.phase), { timeout: 60_000 }).not.toBe("RUNNING");
  await page.getByLabel("编辑后自动配声").click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.document.settings.writing.autoWritingEnabled)).toBe(false);
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
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
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
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
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
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
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
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
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
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
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
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
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
  await page.goto("/");
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
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
  const firstVariant = idiomCatalog.locator("button:not([disabled])").first();
  await expect(firstVariant).toBeEnabled();
  await firstVariant.click();
  await expect(page.getByRole("status")).not.toHaveText("revision 0", { timeout: 30_000 });
  await expect(page.locator(".practice-idiom-list li")).toHaveCount(1, { timeout: 30_000 });
  await expect(page.locator(".timeline-idiom-range")).toHaveCount(1);
  await expect(page.locator(".timeline-slot-control.locked")).not.toHaveCount(0);
  await expect(page.locator(".timeline-slot-control.locked .timeline-start-handle")).toHaveCount(0);

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

test("idiom catalog follows the selected chord after navigating to a progression tail", async ({ page }) => {
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
  const gerToV7 = idiomCatalog.getByRole("button", { name: /^Ger\+6 – V7(?: ·|$)/ }).first();
  await expect(gerToV7).toBeEnabled({ timeout: 60_000 });
  await gerToV7.click();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot()
    .practiceUpdate.document.workspace.idiomInstances.length), { timeout: 30_000 }).toBe(1);
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
    const expected = plan.idiomCatalog.definitions.flatMap((definition) => definition.variants
      .filter((variant) => variant.relatedToFocus)
      .map((variant) => variant.displayLabel));
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
