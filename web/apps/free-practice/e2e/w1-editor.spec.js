import { expect, test } from "@playwright/test";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { readFile } from "node:fs/promises";
import { loadMeconDocument } from "../../../packages/frozen-score/index.js";

const here = dirname(fileURLToPath(import.meta.url));
const fixture = resolve(here, "../../../build/e2e/free-practice.mecon");
const practiceFixture = resolve(here, "../../../build/e2e/free-practice-f1.mecon");

async function openFixture(page) {
  await page.goto("/");
  await page.getByLabel("打开 .mecon").setInputFiles(fixture);
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });
  await expect(page.locator("canvas")).toBeVisible();
}

async function openPracticeFixture(page) {
  await page.goto("/");
  await page.getByLabel("打开 .mecon").setInputFiles(practiceFixture);
  await expect(page.getByRole("status")).toHaveText("revision 0", { timeout: 30_000 });
  await expect(page.locator(".score-pane > .score-editor-toolbar")).toBeVisible();
}

async function revision(page) {
  return page.evaluate(() => window.__MECON_E2E__?.snapshot()?.update?.revision ?? -1);
}

async function waitForRevision(page, previous) {
  await expect.poll(() => revision(page)).toBeGreaterThan(previous);
  return revision(page);
}

async function act(page, action) {
  const before = await revision(page);
  await action();
  return waitForRevision(page, before);
}

async function elementPoint(page, type, occurrence = 0, xRatio = 0.5) {
  return page.evaluate(({ type, occurrence, xRatio }) => {
    const frame = window.__MECON_E2E__.snapshot();
    const surface = frame.bundle.surfaces[0];
    const element = surface.elements.filter((candidate) => candidate.type === type)[occurrence];
    if (!element) throw new Error(`Missing ${type} #${occurrence}`);
    const value = (item) => Number(item?.value ?? item ?? 0);
    const origin = frame.bundle.paginated ? { x: 0, y: surface.contentOffsetY ?? 0 } : {
      x: value(frame.bundle.bounds?.origin?.x), y: value(frame.bundle.bounds?.origin?.y),
    };
    return {
      x: value(element.hitBox.origin.x) + value(element.hitBox.width) * xRatio - origin.x,
      y: value(element.hitBox.origin.y) + value(element.hitBox.height) / 2 - origin.y,
      systemIndex: element.systemIndex,
      staffIndex: element.staffIndex,
    };
  }, { type, occurrence, xRatio });
}

async function clickElement(page, type, occurrence = 0, modifiers = []) {
  await page.locator("canvas").click({ position: await elementPoint(page, type, occurrence), modifiers });
}

async function clickEventElement(page, type, eventId, modifiers = []) {
  const occurrence = await page.evaluate(({ type, eventId }) => {
    const elements = window.__MECON_E2E__.snapshot().bundle.surfaces[0].elements
      .filter((element) => element.type === type);
    return elements.findIndex((element) => element.eventId === eventId);
  }, { type, eventId });
  expect(occurrence).toBeGreaterThanOrEqual(0);
  await clickElement(page, type, occurrence, modifiers);
}

async function barlinePoint(page, boundaryMeasure) {
  const result = await page.evaluate((measure) => {
    const barlines = window.__MECON_E2E__.snapshot().bundle.surfaces[0]
      .elements.filter((element) => element.type === "BARLINE");
    return { occurrence: barlines.findIndex((element) => element.measureNumber === measure),
      measures: barlines.map((element) => element.measureNumber) };
  }, boundaryMeasure);
  expect(result.occurrence, `available barlines: ${result.measures.join(",")}`).toBeGreaterThanOrEqual(0);
  return elementPoint(page, "BARLINE", result.occurrence);
}

async function staffCenterPoint(page, systemIndex, staffIndex) {
  return page.evaluate(({ systemIndex, staffIndex }) => {
    const frame = window.__MECON_E2E__.snapshot();
    const surface = frame.bundle.surfaces[0];
    const staff = surface.elements.find((element) => element.type === "STAFF" &&
      element.systemIndex === systemIndex && element.staffIndex === staffIndex);
    if (!staff) throw new Error(`Missing staff ${staffIndex} in system ${systemIndex}`);
    const value = (item) => Number(item?.value ?? item ?? 0);
    const originY = frame.bundle.paginated ? Number(surface.contentOffsetY ?? 0)
      : value(frame.bundle.bounds?.origin?.y);
    return {
      x: value(staff.hitBox.origin.x) - (frame.bundle.paginated ? 0 : value(frame.bundle.bounds?.origin?.x)),
      y: value(staff.hitBox.origin.y) + value(staff.hitBox.height) / 2 - originY,
    };
  }, { systemIndex, staffIndex });
}

async function noteInputPoint(page, timeCode) {
  return page.evaluate((wanted) => {
    const frame = window.__MECON_E2E__.snapshot();
    const surface = frame.bundle.surfaces[0];
    const value = (item) => Number(item?.value ?? item ?? 0);
    const staff = surface.elements.find((element) => element.type === "STAFF");
    const lines = staff.commands.filter((command) => command.type.split(".").at(-1) === "DrawLine")
      .map((command) => value(command.start.y)).sort((left, right) => left - right);
    const position = frame.bundle.timePositions.find((candidate) =>
      candidate.timeCode?.measure === wanted.measure &&
      value(candidate.timeCode?.beat?.numerator) * wanted.beat.denominator ===
        wanted.beat.numerator * value(candidate.timeCode?.beat?.denominator));
    if (!position) throw new Error(`Missing time position ${JSON.stringify(wanted)}`);
    const origin = frame.bundle.paginated ? { x: 0, y: value(surface.contentOffsetY) } : {
      x: value(frame.bundle.bounds?.origin?.x), y: value(frame.bundle.bounds?.origin?.y),
    };
    const halfSpace = (lines[1] - lines[0]) / 2;
    return {
      x: value(position.x) - origin.x,
      y: lines.reduce((sum, line) => sum + line, 0) / lines.length - origin.y - halfSpace * 2,
    };
  }, timeCode);
}

async function dragEventElement(page, type, eventId, deltaX, deltaY, expectedMode) {
  const occurrence = await page.evaluate(({ type, eventId }) => window.__MECON_E2E__.snapshot()
    .bundle.surfaces[0].elements.filter((element) => element.type === type)
    .findIndex((element) => element.eventId === eventId), { type, eventId });
  expect(occurrence).toBeGreaterThanOrEqual(0);
  return dragElement(page, type, deltaX, deltaY, occurrence, 0.5, expectedMode);
}

async function moveMouseToCanvasPoint(page, point) {
  const canvas = page.locator("canvas");
  await canvas.evaluate((node, target) => {
    const scroller = node.parentElement;
    scroller.scrollLeft = Math.max(0, target.x - scroller.clientWidth / 2);
    scroller.scrollTop = Math.max(0, target.y - scroller.clientHeight / 2);
    const rect = node.getBoundingClientRect();
    window.scrollTo({
      top: Math.max(0, window.scrollY + rect.top + target.y - window.innerHeight / 2),
      behavior: "instant",
    });
  }, point);
  const box = await canvas.boundingBox();
  const client = { x: box.x + point.x, y: box.y + point.y };
  await page.mouse.move(client.x, client.y);
  return client;
}

async function dragElement(
  page, type, deltaX, deltaY, occurrence = 0, xRatio = 0.5, expectedMode = type, expectedBoundary = null,
) {
  const before = await revision(page);
  const point = await elementPoint(page, type, occurrence, xRatio);
  const client = await moveMouseToCanvasPoint(page, point);
  await expect.poll(() => page.evaluate(({ x, y }) => document.elementFromPoint(x, y)?.tagName, client)).toBe("CANVAS");
  await page.mouse.down();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.dragState()?.mode)).toBe(expectedMode);
  await page.mouse.move(client.x + deltaX, client.y + deltaY);
  if (expectedBoundary != null) {
    await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.dragState()?.targetBoundary))
      .toBe(expectedBoundary);
  }
  await page.mouse.up();
  return waitForRevision(page, before);
}

async function elementBounds(page, types) {
  return page.evaluate((wanted) => {
    const frame = window.__MECON_E2E__.snapshot();
    const surface = frame.bundle.surfaces[0];
    const value = (item) => Number(item?.value ?? item ?? 0);
    const origin = frame.bundle.paginated ? { x: 0, y: surface.contentOffsetY ?? 0 } : {
      x: value(frame.bundle.bounds?.origin?.x), y: value(frame.bundle.bounds?.origin?.y),
    };
    const boxes = surface.elements.filter((element) => wanted.includes(element.type)).map((element) => ({
      left: value(element.hitBox.origin.x) - origin.x,
      top: value(element.hitBox.origin.y) - origin.y,
      right: value(element.hitBox.origin.x) + value(element.hitBox.width) - origin.x,
      bottom: value(element.hitBox.origin.y) + value(element.hitBox.height) - origin.y,
    }));
    if (!boxes.length) throw new Error(`Missing ${wanted.join(",")}`);
    return {
      left: Math.min(...boxes.map((box) => box.left)), top: Math.min(...boxes.map((box) => box.top)),
      right: Math.max(...boxes.map((box) => box.right)), bottom: Math.max(...boxes.map((box) => box.bottom)),
    };
  }, types);
}

test("W1 note editing, precise selection, keyboard commands and history run in a real browser", async ({ page }) => {
  await openFixture(page);
  await act(page, () => page.getByRole("button", { name: "插入音符", exact: true }).click());
  await page.getByLabel("四分拍").fill("1");
  await act(page, () => page.getByRole("button", { name: "插入和弦", exact: true }).click());
  const chordEventId = await page.evaluate(() => window.__MECON_E2E__.snapshot().update.selection[0].eventId);
  await act(page, () => clickEventElement(page, "NOTEHEAD", chordEventId));
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot().update.selection[0].pitchIndices))
    .toEqual([0]);
  await page.getByLabel("四分拍").fill("2");
  await act(page, () => page.getByRole("button", { name: "插入休止", exact: true }).click());

  await act(page, () => clickElement(page, "NOTEHEAD"));
  await page.getByLabel("目标谱表声部").selectOption("bassStaff|1");
  await act(page, () => page.getByRole("button", { name: "移动声部" }).click());
  await expect.poll(() => page.evaluate(() =>
    window.__MECON_E2E__.snapshot().update.selection.some(
      (target) => target.voiceTrackId === "bassVoice",
    ))).toBe(true);
  await act(page, () => page.getByRole("button", { name: "所选音符：八分音符" }).click());
  await act(page, () => page.getByRole("button", { name: "1 个附点" }).click());
  await act(page, () => page.getByRole("button", { name: "降号" }).click());
  await act(page, () => page.getByRole("button", { name: "添加连音线", exact: true }).click());
  await act(page, () => page.locator("header").getByLabel("符杠").selectOption("start"));
  await act(page, () => page.getByRole("button", { name: "切换发音法" }).click());
  await act(page, () => page.getByRole("button", { name: "设置琶音" }).click());
  await act(page, () => page.getByRole("button", { name: "自动临时记号" }).click());
  await act(page, () => page.getByRole("button", { name: "断开连音" }).click());
  await page.getByLabel("琶音").selectOption("");
  await act(page, () => page.getByRole("button", { name: "设置琶音" }).click());
  await act(page, () => page.getByLabel("符杠").selectOption("auto"));
  await act(page, () => page.keyboard.press("ArrowUp"));

  await page.keyboard.press("Control+C");
  await expect(page.getByRole("button", { name: "粘贴" })).toBeEnabled();
  await page.getByLabel("四分拍").fill("3");
  await page.getByRole("heading", { name: "反馈" }).click();
  await act(page, () => page.keyboard.press("Control+V"));
  const changed = await revision(page);
  await page.keyboard.press("Control+Z");
  await expect.poll(() => revision(page)).toBe(changed + 1);
  await page.keyboard.press("Control+Y");
  await expect.poll(() => revision(page)).toBe(changed + 2);

  const restored = await revision(page);
  await page.keyboard.press("Control+X");
  await waitForRevision(page, restored);
  const cutRevision = await revision(page);
  await page.keyboard.press("Control+Z");
  await expect.poll(() => revision(page)).toBe(cutRevision + 1);

  await page.getByLabel("小节", { exact: true }).fill("1");
  await page.getByLabel("四分拍").fill("3");
  await page.getByLabel("输入时值").selectOption("HALF");
  await act(page, () => page.getByRole("button", { name: "插入音符", exact: true }).click());

  const score = await page.evaluate(() => window.__MECON_E2E__.snapshot().update.score);
  expect(Object.values(score.voiceTracks)[0].events.length).toBeGreaterThanOrEqual(4);
  expect(Object.values(score.voiceTracks).flatMap((voice) => voice.events)
    .some((event) => event.onset?.measure === 2)).toBe(true);
  const beforeSelectAll = await revision(page);
  await page.keyboard.press("Control+A");
  await waitForRevision(page, beforeSelectAll);
  await expect.poll(() => page.evaluate(() => {
    const snapshot = window.__MECON_E2E__.snapshot().update;
    return snapshot.selection.length === Object.values(snapshot.score.voiceTracks)
      .reduce((count, voice) => count + voice.events.length, 0);
  })).toBe(true);
});

test("W1 duration palette enters note mode and a real canvas click inserts the chosen value", async ({ page }) => {
  await openPracticeFixture(page);
  const beforePitchCount = await page.evaluate(() => Object.values(
    window.__MECON_E2E__.snapshot().update.score.pitchTracks,
  ).flatMap((track) => track.events).reduce((count, event) => count + event.pitches.length, 0));
  if (await page.evaluate(() => window.__MECON_E2E__.snapshot().update.selection.length > 0)) {
    await act(page, () => page.locator("canvas").evaluate((canvas) => {
      const rect = canvas.getBoundingClientRect();
      canvas.dispatchEvent(new MouseEvent("click", {
        bubbles: true, clientX: rect.left + 2, clientY: rect.top + 2,
      }));
    }));
  }
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot().update.selection.length)).toBe(0);
  const point = await page.evaluate(() => {
    const frame = window.__MECON_E2E__.snapshot();
    const surface = frame.bundle.surfaces[0];
    const staff = surface.elements.find((element) => element.type === "STAFF");
    const lines = staff.commands.filter((command) => command.type.split(".").at(-1) === "DrawLine")
      .map((command) => Number(command.start.y?.value ?? command.start.y)).sort((a, b) => a - b);
    const position = frame.bundle.timePositions.find((candidate) => candidate.timeCode?.measure === 1);
    const origin = frame.bundle.paginated ? { x: 0, y: Number(surface.contentOffsetY ?? 0) } : {
      x: Number(frame.bundle.bounds?.origin?.x?.value ?? frame.bundle.bounds?.origin?.x ?? 0),
      y: Number(frame.bundle.bounds?.origin?.y?.value ?? frame.bundle.bounds?.origin?.y ?? 0),
    };
    const halfSpace = (lines[1] - lines[0]) / 2;
    return {
      x: Number(position.x?.value ?? position.x) - origin.x,
      y: lines.reduce((sum, value) => sum + value, 0) / lines.length - origin.y - halfSpace * 2,
    };
  });
  const durationButton = page.locator(
    '.score-pane > .score-editor-toolbar [data-control-id="duration.eighth"] button',
  );
  await expect(page.getByRole("region", { name: "和声时间轴" })).not.toContainText("正在生成时间轴");
  await page.waitForTimeout(500);
  const stableTarget = await durationButton.evaluate((button) => {
    const rect = button.getBoundingClientRect();
    const center = { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
    return {
      center,
      hitLabel: document.elementFromPoint(center.x, center.y)?.closest("button")?.ariaLabel,
    };
  });
  expect(stableTarget.hitLabel).toBe("八分音符");
  await page.mouse.click(stableTarget.center.x, stableTarget.center.y);
  await expect(durationButton).toHaveClass(/active/);
  await expect(durationButton).toHaveAttribute("aria-pressed", "true");
  await expect.poll(() => durationButton.evaluate((button) => getComputedStyle(button).backgroundColor))
    .toBe("rgb(49, 93, 158)");
  await expect.poll(() => page.evaluate(() => {
    const input = window.__MECON_E2E__.editorInput();
    return { duration: input.insertDuration, tool: input.editorTool };
  })).toEqual({ duration: "EIGHTH", tool: "note" });
  const canvas = page.locator("canvas");
  const beforeGhost = await canvas.evaluate((element) => element.toDataURL());
  await canvas.hover({ position: point });
  await expect.poll(() => canvas.evaluate((element) => element.toDataURL()))
    .not.toBe(beforeGhost);
  await page.keyboard.press("Escape");
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.editorInput().editorTool))
    .toBe("select");
  await expect(durationButton).not.toHaveClass(/active/);
  await expect.poll(() => canvas.evaluate((element) => element.toDataURL()))
    .toBe(beforeGhost);

  await page.mouse.click(stableTarget.center.x, stableTarget.center.y);
  await canvas.hover({ position: point });
  await expect.poll(() => canvas.evaluate((element) => element.toDataURL()))
    .not.toBe(beforeGhost);
  await act(page, () => page.locator("canvas").click({ position: point }));
  const inserted = await page.evaluate(() => {
    const update = window.__MECON_E2E__.snapshot().update;
    return {
      hasEighth: Object.values(update.score.voiceTracks).flatMap((voice) => voice.events)
        .some((event) => event.duration?.base === "EIGHTH"),
      durations: Object.values(update.score.voiceTracks).flatMap((voice) => voice.events)
        .map((event) => event.duration),
      pitchCount: Object.values(update.score.pitchTracks).flatMap((track) => track.events)
        .reduce((count, pitchEvent) => count + pitchEvent.pitches.length, 0),
    };
  });
  expect(inserted.hasEighth, JSON.stringify(inserted.durations)).toBe(true);
  expect(inserted.pitchCount).toBeGreaterThan(beforePitchCount);
  await page.keyboard.press("Escape");
  await expect.poll(() => page.evaluate(() => ({
    tool: window.__MECON_E2E__.editorInput().editorTool,
    selectionCount: window.__MECON_E2E__.snapshot().update.selection.length,
  }))).toEqual({ tool: "select", selectionCount: 0 });
});

test("W1 triplet pointer input switches to the shared continuation and renders an in-group ghost", async ({ page }) => {
  await openPracticeFixture(page);
  await page.keyboard.press("Escape");
  const quarterButton = page.locator(
    '.score-pane > .score-editor-toolbar [data-control-id="duration.quarter"] button',
  );
  await quarterButton.click();
  await page.getByLabel("连音数").selectOption("3");
  await page.getByRole("button", { name: "连音", exact: true }).click();
  await expect.poll(() => page.evaluate(() => {
    const input = window.__MECON_E2E__.editorInput();
    return { duration: input.insertDuration, dots: Number(input.insertDots), tuplet: Number(input.tupletCount) };
  })).toEqual({ duration: "QUARTER", dots: 0, tuplet: 3 });

  const canvas = page.locator("canvas");
  const firstPoint = await noteInputPoint(page, {
    measure: 1, beat: { numerator: 0, denominator: 1 },
  });
  await canvas.hover({ position: firstPoint });
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.noteInputPreview()?.commands
    ?.some((command) => command.glyph?.codepoint === "\uE883") ?? false)).toBe(true);
  expect(await page.evaluate(() => window.__MECON_E2E__.noteInputPreview().commands
    .some((command) => ["\uE240", "\uE241"].includes(command.glyph?.codepoint)))).toBe(true);
  await act(page, () => canvas.click({ position: firstPoint }));

  await expect.poll(() => page.evaluate(() => {
    const input = window.__MECON_E2E__.editorInput();
    return { duration: input.insertDuration, dots: Number(input.insertDots), tuplet: Number(input.tupletCount) };
  })).toEqual({ duration: "EIGHTH", dots: 0, tuplet: 0 });
  const continuation = await page.evaluate(() => window.__MECON_E2E__.snapshot().update.nextInputPosition);
  expect(continuation).toEqual({ measure: 1, beat: { numerator: 1, denominator: 12 } });

  const secondPoint = await noteInputPoint(page, continuation);
  await canvas.hover({ position: secondPoint });
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.noteInputPreview()?.commands?.length ?? 0))
    .toBeGreaterThan(0);
  expect(await page.evaluate(() => window.__MECON_E2E__.noteInputPreview().commands
    .some((command) => command.glyph?.codepoint === "\uE883"))).toBe(false);
  await act(page, () => canvas.click({ position: secondPoint }));

  const tuplets = await page.evaluate(() => {
    const score = window.__MECON_E2E__.snapshot().update.score;
    const events = Object.values(score.voiceTracks)
      .flatMap((voice) => voice.events);
    const pitchedIds = new Set(Object.values(score.pitchTracks).flatMap((track) => track.events)
      .filter((event) => event.pitches.length > 0).map((event) => event.id));
    return {
      spans: events.filter((event) => event.tupletSpan?.count === 3).length,
      notes: events.filter((event) => pitchedIds.has(event.pitchEventId) &&
        event.duration?.tuplet?.actual === 3).length,
    };
  });
  expect(tuplets).toEqual({ spans: 1, notes: 2 });
});

test("W1 structure, layout, expressions and mecon export run through Web UI", async ({ page }) => {
  await openFixture(page);
  await page.getByLabel("谱号").selectOption("BASS");
  await act(page, () => page.getByRole("button", { name: "设置谱号" }).click());
  await page.getByLabel("调号").selectOption("7|MAJOR");
  await act(page, () => page.getByRole("button", { name: "设置调号" }).click());
  await page.getByLabel("拍号分子").fill("3");
  await act(page, () => page.getByRole("button", { name: "设置拍号" }).click());
  await page.getByLabel("小节数量").fill("3");
  await act(page, () => page.getByRole("button", { name: "插入小节" }).click());
  await page.getByLabel("边界").fill("1");
  await act(page, () => page.getByRole("button", { name: "设置断点" }).click());
  await act(page, () => page.getByRole("button", { name: "隐藏目标谱表" }).click());
  await act(page, () => page.getByRole("button", { name: "显示目标谱表" }).click());

  await page.getByLabel("边界").fill("2");
  await page.getByLabel("小节线").selectOption("REPEAT_RIGHT");
  await act(page, () => page.getByRole("button", { name: "设置小节线" }).click());
  await page.getByLabel("反复次数").fill("3");
  await act(page, () => page.getByRole("button", { name: "更新反复次数" }).click());
  await page.getByLabel("边界").fill("1");
  await act(page, () => page.getByRole("button", { name: "切换 1/2 房子" }).click());
  const volta = await elementPoint(page, "VOLTA_ENDING", 1, 0.9);
  const fourthBoundary = await barlinePoint(page, 4);
  const voltaTargetStaff = await staffCenterPoint(page, fourthBoundary.systemIndex, volta.staffIndex);
  await dragElement(
    page, "VOLTA_ENDING", fourthBoundary.x - volta.x, voltaTargetStaff.y - volta.y, 1, 0.9, "VOLTA", 4,
  );
  await act(page, () => page.getByRole("button", { name: "删除所选房子" }).click());
  await page.getByLabel("边界").fill("4");
  await act(page, () => page.getByRole("button", { name: "切换导航记号" }).click());
  const navigation = await elementPoint(page, "NAVIGATION_MARK");
  const firstBoundary = await barlinePoint(page, 1);
  const navigationTargetStaff = await staffCenterPoint(page, firstBoundary.systemIndex, navigation.staffIndex);
  await dragElement(
    page, "NAVIGATION_MARK", firstBoundary.x - navigation.x, navigationTargetStaff.y - navigation.y,
    0, 0.5, "NAVIGATION", 1,
  );
  await act(page, () => page.getByRole("button", { name: "删除所选导航" }).click());
  await act(page, () => page.getByRole("button", { name: "清除断点" }).click());

  await act(page, () => page.getByRole("button", { name: "添加力度" }).click());
  await act(page, () => clickElement(page, "DYNAMIC"));
  await act(page, () => page.getByRole("button", { name: "删除所选记号" }).click());
  await act(page, () => page.getByRole("button", { name: "添加发夹" }).click());
  await dragElement(page, "HAIRPIN", 16, -8, 0, 0.9, "ATTACHMENT");
  await expect.poll(() => page.evaluate(() => Object.values(
    window.__MECON_E2E__.snapshot().geometry.attachments,
  ).some((geometry) => geometry.manuallyAdjustedY))).toBe(true);
  await dragElement(page, "HAIRPIN", 8, -6, 0, 0.5, "ATTACHMENT");
  await act(page, () => page.getByRole("button", { name: "添加八度线" }).click());
  await act(page, () => page.getByRole("button", { name: "添加速度" }).click());
  await act(page, () => clickElement(page, "TEMPO_MARKING"));
  await page.getByLabel("BPM").fill("144");
  await page.getByLabel("速度显示").selectOption("TEXT");
  await act(page, () => page.getByRole("button", { name: "更新所选速度" }).click());
  await act(page, () => page.getByRole("button", { name: "渐快" }).click());
  await act(page, () => page.getByRole("button", { name: "延长记号" }).click());
  await page.getByLabel("延长/换气量").fill("1.5");
  await act(page, () => page.getByRole("button", { name: "更新所选停顿量" }).click());
  await act(page, () => page.getByRole("button", { name: "换气记号" }).click());
  await page.getByLabel("延长/换气量").fill("0.5");
  await act(page, () => page.getByRole("button", { name: "更新所选停顿量" }).click());

  await page.getByLabel("小节", { exact: true }).fill("5");
  await page.getByLabel("小节数量").fill("1");
  page.once("dialog", (dialog) => dialog.accept());
  await act(page, () => page.getByRole("button", { name: "删除小节" }).click());
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot().update.score.measures.length))
    .toBe(4);

  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("button", { name: "导出 .mecon" }).click(),
  ]);
  expect(download.suggestedFilename()).toBe("free-practice.mecon");
  const exportPath = resolve(here, "../../../build/e2e/browser-export.mecon");
  await download.saveAs(exportPath);
  await expect(page.getByRole("status")).toHaveText("已生成 free-practice.mecon");
  const reopened = await loadMeconDocument(new Uint8Array(await readFile(exportPath)));
  expect(reopened.scores.get("inactive-score").metadata.title).toBe("Inactive");
  expect(reopened.modules.get("future").payload.futureField).toBe("preserved");
  expect(reopened.manifest.workspace.activeModuleId).toBe("future");
});

test("W1 pointer selection creates and edits a slur", async ({ page }) => {
  await openFixture(page);
  await act(page, () => page.getByRole("button", { name: "插入音符", exact: true }).click());
  await page.getByLabel("四分拍").fill("1");
  await page.getByLabel("C4 起音级").fill("2");
  await act(page, () => page.getByRole("button", { name: "插入音符", exact: true }).click());
  await act(page, () => clickElement(page, "NOTEHEAD", 0));
  await act(page, () => clickElement(page, "NOTEHEAD", 1, ["Shift"]));
  await act(page, () => page.getByRole("button", { name: "连接所选音符" }).click());
  await dragElement(page, "SLUR", 0, -18);
  const apexGeometry = await page.evaluate(() => Object.values(window.__MECON_E2E__.snapshot().geometry.slurs)[0]);
  expect(apexGeometry.manuallyAdjusted).toBe(true);
  await dragElement(page, "SLUR", 12, -5, 0, 0.1);
  const endpointGeometry = await page.evaluate(() => Object.values(window.__MECON_E2E__.snapshot().geometry.slurs)[0]);
  expect(endpointGeometry.startDx).not.toBe(apexGeometry.startDx);
  await act(page, () => page.getByRole("button", { name: "删除所选连音线" }).click());
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot().bundle.surfaces[0].elements
    .some((element) => element.type === "SLUR"))).toBe(false);
});

test("W1 tie, beam and articulation geometry are pointer-editable", async ({ page }) => {
  await openFixture(page);
  await page.getByLabel("输入时值").selectOption("EIGHTH");
  await act(page, () => page.getByRole("button", { name: "插入音符", exact: true }).click());
  await act(page, () => page.getByRole("button", { name: "插入音符", exact: true }).click());

  await act(page, () => clickElement(page, "NOTEHEAD", 0));
  await act(page, () => page.getByRole("button", { name: "添加连音线", exact: true }).click());
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot().bundle.surfaces[0].elements
    .some((element) => element.type === "TIE"))).toBe(true);
  await dragElement(page, "TIE", 0, -12);
  const tieApex = await page.evaluate(() => Object.values(window.__MECON_E2E__.snapshot().geometry.ties)[0][0]);
  await dragElement(page, "TIE", 8, -4, 0, 0.1);
  const tieEndpoint = await page.evaluate(() => Object.values(window.__MECON_E2E__.snapshot().geometry.ties)[0][0]);
  expect(tieEndpoint.startDx).not.toBe(tieApex.startDx);

  await act(page, () => clickElement(page, "NOTEHEAD", 0));
  await act(page, () => page.getByLabel("符杠").selectOption("start"));
  await act(page, () => clickElement(page, "NOTEHEAD", 1));
  await act(page, () => page.getByLabel("符杠").selectOption("end"));
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot().bundle.surfaces[0].elements
    .some((element) => element.type === "BEAM"))).toBe(true);
  await dragElement(page, "BEAM", 0, -10, 0, 0.1);

  await act(page, () => clickElement(page, "NOTEHEAD", 0));
  await act(page, () => page.getByRole("button", { name: "切换发音法" }).click());
  await dragElement(page, "ARTICULATION", 6, -8, 0, 0.5, "ARTICULATION");
  const articulation = await page.evaluate(() => Object.values(
    window.__MECON_E2E__.snapshot().geometry.articulations,
  )[0].marks[0]);
  expect(Math.abs(articulation.dx) + Math.abs(articulation.dy)).toBeGreaterThan(0);
});

test("W1 keyboard and Web MIDI step input use the same insertion path", async ({ page }) => {
  await page.addInitScript(() => {
    const input = { onmidimessage: null };
    const access = { inputs: new Map([["mock", input]]), onstatechange: null };
    Object.defineProperty(navigator, "requestMIDIAccess", { value: async () => access });
    window.__sendMidiNote = (note) => input.onmidimessage?.({ data: new Uint8Array([0x90, note, 100]) });
  });
  await openFixture(page);
  await page.getByLabel("键盘步进（A–J，R 休止）").check();
  await page.getByRole("heading", { name: "反馈" }).click();
  await act(page, () => page.keyboard.press("a"));
  await page.getByRole("button", { name: "连接 MIDI" }).click();
  await expect(page.getByText("MIDI 已连接（1）")).toBeVisible();
  await act(page, () => page.evaluate(() => window.__sendMidiNote(61)));
  const score = await page.evaluate(() => window.__MECON_E2E__.snapshot().update.score);
  expect(Object.values(score.pitchTracks).flatMap((track) => track.events)
    .filter((event) => (event.pitches ?? []).length > 0).length).toBeGreaterThanOrEqual(2);
});

test("W1 grace, tuplets, small notes, ornaments, marquee and note/rest drags are browser-operable", async ({ page }) => {
  await openFixture(page);
  await page.getByLabel("输入时值").selectOption("EIGHTH");
  for (let index = 0; index < 3; index++) {
    await act(page, () => page.getByRole("button", { name: "插入音符", exact: true }).click());
  }
  await act(page, () => clickElement(page, "NOTEHEAD", 0));
  await act(page, () => clickElement(page, "NOTEHEAD", 1, ["Shift"]));
  await act(page, () => clickElement(page, "NOTEHEAD", 2, ["Shift"]));
  await page.getByLabel("连音组").selectOption("3");
  await act(page, () => page.getByRole("button", { name: "所选设为连音组" }).click());
  await act(page, () => page.getByRole("button", { name: "添加到所选音符" }).click());
  await act(page, () => clickElement(page, "ORNAMENT"));
  await page.getByLabel("装饰振荡次数").fill("6");
  await act(page, () => page.getByRole("button", { name: "更新所选装饰" }).click());
  await act(page, () => page.getByRole("button", { name: "删除所选记号" }).click());
  await page.getByLabel("连音组").selectOption("0");

  await page.getByLabel("倚音输入").check();
  await act(page, () => page.getByRole("button", { name: "插入音符", exact: true }).click());
  await expect.poll(() => page.evaluate(() => Object.values(
    window.__MECON_E2E__.snapshot().update.score.voiceTracks,
  ).flatMap((voice) => voice.events).some((event) => event.onset?.grace != null))).toBe(true);
  await page.getByLabel("倚音组总时值").selectOption("HALF");
  await page.getByLabel("占用时值").selectOption("PREVIOUS");
  await act(page, () => page.getByRole("button", { name: "更新所选倚音组" }).click());
  await page.getByLabel("倚音输入").uncheck();
  await act(page, () => page.getByRole("button", { name: "插入休止", exact: true }).click());
  const firstRestId = await page.evaluate(() => window.__MECON_E2E__.snapshot().update.selection[0].eventId);
  await act(page, () => page.getByRole("button", { name: "插入休止", exact: true }).click());
  const secondRestId = await page.evaluate(() => window.__MECON_E2E__.snapshot().update.selection[0].eventId);
  await dragEventElement(page, "REST", firstRestId, 0, 10, "NOTE");
  await expect.poll(() => page.evaluate(() => Object.values(
    window.__MECON_E2E__.snapshot().update.score.voiceTracks,
  ).flatMap((voice) => voice.events).some(
    (event) => Number(event.rendering?.restStaffPosition ?? 0) !== 0,
  ))).toBe(true);
  await act(page, () => clickEventElement(page, "REST", firstRestId));
  await act(page, () => clickEventElement(page, "REST", secondRestId, ["Shift"]));
  await act(page, () => page.getByRole("button", { name: "创建小音符休止区" }).click());

  const bounds = await elementBounds(page, ["NOTEHEAD", "REST"]);
  const start = { x: Math.max(1, bounds.left - 12), y: Math.max(1, bounds.top - 12) };
  const startClient = await moveMouseToCanvasPoint(page, start);
  const beforeMarquee = await revision(page);
  await page.mouse.down();
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.dragState()?.mode)).toBe("MARQUEE");
  await page.mouse.move(startClient.x + (bounds.right - start.x) + 12, startClient.y + (bounds.bottom - start.y) + 12);
  const marquee = page.locator(".score-marquee-preview");
  await expect(marquee).toBeVisible();
  const marqueeBox = await marquee.boundingBox();
  expect(marqueeBox?.width).toBeGreaterThan(0);
  expect(marqueeBox?.height).toBeGreaterThan(0);
  await page.keyboard.press("Escape");
  await expect(marquee).toHaveCount(0);
  await page.mouse.up();

  const selectionStartClient = await moveMouseToCanvasPoint(page, start);
  await page.mouse.down();
  await page.mouse.move(
    selectionStartClient.x + (bounds.right - start.x) + 12,
    selectionStartClient.y + (bounds.bottom - start.y) + 12,
  );
  await expect(marquee).toBeVisible();
  await page.mouse.up();
  await expect(marquee).toHaveCount(0);
  await waitForRevision(page, beforeMarquee);
  await expect.poll(() => page.evaluate(() => window.__MECON_E2E__.snapshot().update.selection.length)).toBeGreaterThan(2);

  await dragElement(page, "NOTEHEAD", 0, -10, 0, 0.5, "NOTE");

  await act(page, () => clickElement(page, "NOTEHEAD", 0));
  const beforeDelete = await revision(page);
  await page.keyboard.press("Delete");
  await waitForRevision(page, beforeDelete);
  const afterDelete = await revision(page);
  await page.keyboard.press("Control+Z");
  await expect.poll(() => revision(page)).toBe(afterDelete + 1);
});
