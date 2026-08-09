import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { engineSkip, requireEngineModule } from "./engine-module.js";

const trace = JSON.parse(readFileSync(new URL(
  "../../../../features/free-practice/testdata/timeline-raw-input-trace.json",
  import.meta.url,
), "utf8"));

function partialEqual(actual, expected, path) {
  if (Array.isArray(expected)) {
    assert.deepEqual(actual, expected, path);
    return;
  }
  if (expected && typeof expected === "object") {
    for (const [key, value] of Object.entries(expected)) {
      assert.ok(Object.hasOwn(actual ?? {}, key), `${path} is missing ${key}`);
      partialEqual(actual[key], value, `${path}.${key}`);
    }
    return;
  }
  assert.deepEqual(actual, expected, path);
}

test("generated Kotlin/JS replays the shared raw timeline input trace", {
  skip: engineSkip,
}, async () => {
  const { MeconFreePracticeTimeline } = await import(await requireEngineModule());
  const timeline = new MeconFreePracticeTimeline();
  const request = trace.request;
  for (const entry of trace.cases) {
    const scene = JSON.parse(timeline.projectJson(JSON.stringify(request)));
    const target = scene.hitObjects.find((item) => item.id === entry.targetId);
    assert.ok(target, `${entry.name}: ${entry.targetId}`);
    const pointerId = 9;
    const x = target.bounds.x + target.bounds.width / 2;
    const y = target.bounds.y + target.bounds.height / 2;
    const results = [];
    const handle = (activeRequest, input) => JSON.parse(timeline.handleJson(
      JSON.stringify(scene), JSON.stringify(activeRequest), JSON.stringify(input),
    ));
    // The browser shell resolves hover locally against the pre-sorted scene rather than calling the
    // controller, so the trace must prove that shortcut agrees with the JVM controller result.
    const hover = entry.operation === "hover"
      ? scene.hoverTargets.find((candidate) => x >= candidate.bounds.x
        && x <= candidate.bounds.x + candidate.bounds.width
        && y >= candidate.bounds.y
        && y <= candidate.bounds.y + candidate.bounds.height) ?? null
      : null;
    if (entry.operation === "hover") {
      // no interaction results
    } else if (entry.operation === "activate") {
      results.push(handle(request, {
        type: "ACTIVATE", sceneGeneration: scene.generation, actionTargetId: entry.targetId,
      }));
    } else if (entry.operation === "down") {
      results.push(handle(request, {
        type: "DOWN", sceneGeneration: scene.generation, pointerId, x, y,
      }));
    } else if (entry.operation === "key") {
      results.push(handle(request, {
        type: "KEY", sceneGeneration: scene.generation,
        actionTargetId: entry.targetId, key: entry.key,
      }));
    } else {
      const down = handle(request, {
        type: "DOWN", sceneGeneration: scene.generation, pointerId, x, y, ctrl: entry.ctrl === true,
      });
      results.push(down);
      const moved = handle({ ...request, gesture: down.gesture }, {
        type: "MOVE", sceneGeneration: scene.generation, pointerId,
        x: x + (entry.deltaX ?? 0), y,
      });
      results.push(moved);
      results.push(handle({ ...request, gesture: moved.gesture }, {
        type: entry.operation === "cancel" ? "CANCEL" : "UP",
        sceneGeneration: scene.generation,
        pointerId,
      }));
    }
    const last = (field) => results.map((item) => item[field]).filter((value) => value != null).at(-1) ?? null;
    const actual = {
      selectSlotId: last("selectSlotId"),
      selectTonalLayoutId: last("selectTonalLayoutId"),
      selectIdiomId: last("selectIdiomId"),
      removeSlotId: last("removeSlotId"),
      appendAt: last("appendAt"),
      appendDuration: last("appendDuration"),
      previewEdit: last("previewEdit"),
      commitEdit: last("commitEdit"),
      effectTypes: [...new Set(results.flatMap((item) => item.effects ?? []).map((effect) => effect.type))],
      hoverHitId: hover?.hitId ?? null,
      hoverCursor: hover?.cursor ?? null,
      hoverOverlayIds: (hover?.overlay ?? []).map((object) => object.id),
    };
    partialEqual(actual, entry.expected, entry.name);
  }
});
