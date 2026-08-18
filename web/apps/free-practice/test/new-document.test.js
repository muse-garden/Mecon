import assert from "node:assert/strict";
import test from "node:test";
import { loadMeconDocument, writeMeconDocument } from "@mecon/frozen-score";
import { createNewPracticeDocument } from "../src/new-document.js";

test("new practice container round-trips the shared preset and frozen geometry", async () => {
  const preset = {
    score: { id: "practice-score", metadata: { title: "自由练习" }, measures: [] },
    document: { settings: { polyphonyLimit: 4 }, workspace: { slots: [] } },
    module: { id: "free-practice", type: "exploration.free-practice", schemaVersion: 10 },
  };
  const created = createNewPracticeDocument(preset, 1234);
  const bundle = { schemaVersion: 1, surfaces: [] };
  const bytes = writeMeconDocument(created, {
    scores: new Map([[preset.score.id, preset.score]]),
    modules: created.modules,
    geometries: new Map([[preset.score.id, bundle]]),
  });
  const reopened = await loadMeconDocument(bytes);

  assert.equal(reopened.manifest.createdAt, 1234);
  assert.equal(reopened.manifest.activeScoreId, preset.score.id);
  assert.equal(reopened.manifest.workspace.activeModuleId, preset.module.id);
  assert.deepEqual(reopened.scores.get(preset.score.id), preset.score);
  assert.deepEqual(reopened.modules.get(preset.module.id).payload, preset.document);
  assert.ok(reopened.entries.has(`geometry/${preset.score.id}.json`));
});

test("new practice container rejects missing shared module metadata", () => {
  assert.throws(
    () => createNewPracticeDocument({ score: { id: "score" }, document: {}, module: {} }),
    /缺少模块描述/,
  );
});
